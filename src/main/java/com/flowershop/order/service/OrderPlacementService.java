package com.flowershop.order.service;

import com.flowershop.common.error.BusinessException;
import com.flowershop.order.client.CatalogClient;
import com.flowershop.order.client.CustomerClient;
import com.flowershop.order.client.InventoryClient;
import com.flowershop.order.config.OrderProperties;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.DeliverySlot;
import com.flowershop.order.domain.OrderRequest;
import com.flowershop.order.domain.OrderStatus;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.dto.PlaceOrderRequest;
import com.flowershop.order.error.OrderErrorCode;
import com.flowershop.order.repository.CustomerOrderRepository;
import com.flowershop.order.repository.OrderRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Đặt đơn: một nghiệp vụ trải trên 4 service, không transaction nào bao được hết. Các bước (saga):
 * <ol>
 *   <li>giữ chỗ Idempotency-Key (DB order)</li>
 *   <li>lấy giá từ catalog, địa chỉ từ customer (chỉ đọc, lỗi thì dừng, không cần bù trừ)</li>
 *   <li>giữ hàng bên inventory theo mã đơn</li>
 *   <li>lưu đơn PENDING (DB order)</li>
 * </ol>
 * Lỗi sau bước 3 (kể cả timeout không rõ đã giữ được chưa): <b>bù trừ</b> bằng release (idempotent),
 * rồi trả Idempotency-Key để khách thử lại. Nhả không được thì phiếu giữ cũng tự hết hạn bên inventory.
 * Không gọi service khác trong lúc đang giữ transaction DB.
 */
@Slf4j
@Service
public class OrderPlacementService {

    public record PlaceResult(OrderResponse order, boolean created) {
    }

    private record Claim(Long requestId, String existingOrderCode) {
    }

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final CustomerOrderRepository orders;
    private final OrderRequestRepository requests;
    private final HistoryRecorder history;
    private final CatalogClient catalog;
    private final CustomerClient customers;
    private final InventoryClient inventory;
    private final OrderProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderPlacementService(CustomerOrderRepository orders, OrderRequestRepository requests,
                                 HistoryRecorder history, CatalogClient catalog, CustomerClient customers,
                                 InventoryClient inventory, OrderProperties props, Clock clock,
                                 PlatformTransactionManager txManager) {
        this.orders = orders;
        this.requests = requests;
        this.history = history;
        this.catalog = catalog;
        this.customers = customers;
        this.inventory = inventory;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public PlaceResult place(long customerId, String idempotencyKey, PlaceOrderRequest req) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        Map<String, Integer> items = toSortedMap(req.items());
        Instant now = clock.instant();

        Claim claim = tx.execute(s -> claim(customerId, idempotencyKey, hash(req, items), now));
        if (claim.existingOrderCode() != null) {
            log.info("Idempotent replay: key reused for order={}", claim.existingOrderCode());
            return new PlaceResult(OrderMapper.toResponse(orders.findByOrderCodeAndCustomerId(
                    claim.existingOrderCode(), customerId).orElseThrow()), false);
        }

        String orderCode = null;
        try {
            Instant freshUntil = validateDelivery(req.deliveryDate(), req.deliverySlot(), now);
            Map<String, CatalogClient.Product> products = loadProducts(items);
            CustomerOrder.Recipient recipient = customers.findAddress(customerId, req.addressId());

            orderCode = tx.execute(s -> "DH_%07d".formatted(orders.nextOrderCodeNumber()));
            int holdMinutes = (int) props.rules().pendingTtl().plus(props.rules().holdBuffer()).toMinutes();
            inventory.reserve(orderCode, items, holdMinutes, freshUntil);

            String code = orderCode;
            OrderResponse placed = tx.execute(s -> save(code, customerId, claim.requestId(), items, products,
                    recipient, req, now));
            log.info("Placed order={} customer={} total={}", code, customerId, placed.total());
            return new PlaceResult(placed, true);
        } catch (RuntimeException e) {
            if (orderCode != null) {
                compensate(orderCode);
            }
            // Trả lại key: khách bấm lại (cùng key) sẽ được thử lại từ đầu thay vì kẹt ở "đang xử lý"
            tx.executeWithoutResult(s -> requests.deleteById(claim.requestId()));
            throw e;
        }
    }

    private Claim claim(long customerId, String key, String hash, Instant now) {
        var existing = requests.findByCustomerIdAndIdempotencyKey(customerId, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw new BusinessException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            if (existing.get().getOrderCode() == null) {
                throw new BusinessException(OrderErrorCode.REQUEST_IN_PROGRESS);
            }
            return new Claim(existing.get().getId(), existing.get().getOrderCode());
        }
        try {
            return new Claim(requests.saveAndFlush(OrderRequest.claim(customerId, key, hash, now)).getId(), null);
        } catch (DataIntegrityViolationException e) {
            // Hai request cùng key đến cùng lúc: UNIQUE(customer_id, idempotency_key) chặn request sau
            throw new BusinessException(OrderErrorCode.REQUEST_IN_PROGRESS);
        }
    }

    /** @return freshUntil = cuối khung giờ giao: hoa phải còn hạn tới lúc đó */
    private Instant validateDelivery(LocalDate date, DeliverySlot slot, Instant now) {
        Instant start = slot.startOn(date).toInstant();
        if (start.isBefore(now.plus(props.rules().minLeadTime()))) {
            throw new BusinessException(OrderErrorCode.INVALID_DELIVERY_TIME,
                    "Cần đặt trước ít nhất " + props.rules().minLeadTime().toHours() + " giờ so với giờ giao");
        }
        LocalDate today = now.atZone(DeliverySlot.SHOP_ZONE).toLocalDate();
        if (date.isAfter(today.plusDays(props.rules().maxAdvanceDays()))) {
            throw new BusinessException(OrderErrorCode.INVALID_DELIVERY_TIME,
                    "Chỉ nhận đặt trước tối đa " + props.rules().maxAdvanceDays() + " ngày");
        }
        return slot.endOn(date).toInstant();
    }

    private Map<String, CatalogClient.Product> loadProducts(Map<String, Integer> items) {
        Map<String, CatalogClient.Product> products = new LinkedHashMap<>();
        for (String sku : items.keySet()) {
            CatalogClient.Product p = catalog.getActive(sku);
            if (!props.rules().currency().equals(p.currency())) {
                throw new BusinessException(OrderErrorCode.MIXED_CURRENCY);
            }
            products.put(sku, p);
        }
        return products;
    }

    private OrderResponse save(String orderCode, long customerId, Long requestId, Map<String, Integer> items,
                               Map<String, CatalogClient.Product> products, CustomerOrder.Recipient recipient,
                               PlaceOrderRequest req, Instant now) {
        CustomerOrder order = CustomerOrder.place(orderCode, customerId, props.rules().currency(),
                props.rules().shippingFee(), recipient, req.deliveryDate(), req.deliverySlot(),
                blankToNull(req.cardMessage()), now);
        items.forEach((sku, qty) -> {
            CatalogClient.Product p = products.get(sku);
            order.addItem(sku, p.name(), p.unitPrice(), qty);
        });
        orders.saveAndFlush(order);
        requests.findById(requestId).orElseThrow().complete(orderCode);
        history.record(order, null, OrderStatus.PENDING, null);
        return OrderMapper.toResponse(order);
    }

    private void compensate(String orderCode) {
        try {
            inventory.release(orderCode);
            log.warn("Placement of order={} failed, reservation released", orderCode);
        } catch (RuntimeException e) {
            // Không nhả được: phiếu giữ hàng vẫn tự hết hạn bên inventory, hàng không bị kẹt mãi
            log.error("Compensation failed for order={}, inventory hold will expire by itself", orderCode, e);
        }
    }

    private static Map<String, Integer> toSortedMap(List<PlaceOrderRequest.Item> items) {
        Map<String, Integer> result = new TreeMap<>();
        for (PlaceOrderRequest.Item item : items) {
            if (result.put(item.sku(), item.quantity()) != null) {
                throw new BusinessException(OrderErrorCode.DUPLICATE_SKU);
            }
        }
        return result;
    }

    /** Dạng chuẩn hóa của request (sản phẩm đã sắp xếp), để so "cùng key, cùng nội dung" không phụ thuộc thứ tự. */
    private static String hash(PlaceOrderRequest req, Map<String, Integer> items) {
        String canonical = req.addressId() + "|" + req.deliveryDate() + "|" + req.deliverySlot() + "|"
                + (req.cardMessage() == null ? "" : req.cardMessage()) + "|" + items;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
