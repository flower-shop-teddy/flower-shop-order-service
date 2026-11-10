package com.flowershop.order.service;

import com.flowershop.common.error.BusinessException;
import com.flowershop.order.client.InventoryClient;
import com.flowershop.order.config.OrderProperties;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.OrderStatus;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.error.OrderErrorCode;
import com.flowershop.order.repository.CustomerOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Đổi trạng thái đơn sau khi đặt. Hai quy tắc giữ cho đơn và kho luôn khớp nhau:
 * <ul>
 *   <li><b>Xác nhận</b>: gọi inventory confirm TRƯỚC, thành công mới ghi CONFIRMED. Ngược lại (ghi trước, confirm lỗi)
 *       thì phiếu giữ hàng hết hạn trong khi đơn đã xác nhận: bán thứ không còn giữ.</li>
 *   <li><b>Hủy</b>: ghi CANCELLED TRƯỚC (stock_released = false), rồi mới nhả hàng. Inventory lỗi thì job gọi lại
 *       tới khi xong (release idempotent). Ngược lại (nhả trước) thì đơn có thể vừa bị chuyển sang PREPARING
 *       trong lúc đó: đơn vẫn chạy mà hàng đã trả về kho.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderLifecycleService {

    static final String EXPIRED_REASON = "Quá hạn xác nhận, hệ thống tự hủy";

    private final CustomerOrderRepository orders;
    private final HistoryRecorder history;
    private final InventoryClient inventory;
    private final OrderProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderLifecycleService(CustomerOrderRepository orders, HistoryRecorder history, InventoryClient inventory,
                                 OrderProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.history = history;
        this.inventory = inventory;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public OrderResponse confirm(String orderCode) {
        CustomerOrder current = orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (current.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(OrderErrorCode.INVALID_TRANSITION);
        }
        try {
            inventory.confirm(orderCode);
        } catch (BusinessException e) {
            if (e.getErrorCode() == OrderErrorCode.HOLD_EXPIRED) {
                cancelInDb(orderCode, null, "Phiếu giữ hàng đã hết hạn trước khi xác nhận");
                releaseStock(orderCode);
            }
            throw e;
        }
        return tx.execute(s -> {
            // Đọc lại có khóa: trong lúc gọi inventory, đơn có thể đã bị hủy (khi đó confirm() ném INVALID_TRANSITION)
            CustomerOrder order = lockOrThrow(orderCode);
            order.confirm(clock.instant());
            history.record(order, OrderStatus.PENDING, OrderStatus.CONFIRMED, null);
            log.info("Confirmed order={}", orderCode);
            return OrderMapper.toResponse(order);
        });
    }

    /**
     * CONFIRMED → PREPARING → DELIVERING → DELIVERED. {@code expectedFrom}: nhân viên bấm hai lần
     * không làm đơn nhảy hai bước.
     */
    public OrderResponse advance(String orderCode, OrderStatus expectedFrom) {
        return tx.execute(s -> {
            CustomerOrder order = lockOrThrow(orderCode);
            if (order.getStatus() != expectedFrom) {
                throw new BusinessException(OrderErrorCode.INVALID_TRANSITION);
            }
            OrderStatus from = order.advance(clock.instant());
            history.record(order, from, order.getStatus(), null);
            log.info("Order={} {} -> {}", orderCode, from, order.getStatus());
            return OrderMapper.toResponse(order);
        });
    }

    /** @param customerId khách tự hủy: chỉ hủy được đơn của mình. null = nhân viên hủy. */
    public OrderResponse cancel(String orderCode, Long customerId, String reason) {
        OrderResponse cancelled = cancelInDb(orderCode, customerId, reason);
        releaseStock(orderCode);
        return cancelled;
    }

    /** Job: hủy đơn PENDING quá hạn xác nhận. @return số đơn đã hủy */
    public int expireOverduePending(Instant now) {
        List<String> codes = tx.execute(s -> orders
                .findOverduePendingForUpdate(now.minus(props.rules().pendingTtl()), props.jobs().batchSize())
                .stream()
                .map(order -> {
                    OrderStatus from = order.cancel(EXPIRED_REASON, now);
                    history.record(order, from, OrderStatus.CANCELLED, EXPIRED_REASON);
                    return order.getOrderCode();
                })
                .toList());
        codes.forEach(this::releaseStock);
        if (!codes.isEmpty()) {
            log.info("Expired {} pending orders", codes.size());
        }
        return codes.size();
    }

    /** Job: đơn đã hủy mà lần nhả hàng trước bị lỗi. */
    public void retryUnreleasedStock() {
        orders.findUnreleasedOrderCodes(PageRequest.of(0, props.jobs().batchSize())).forEach(this::releaseStock);
    }

    private OrderResponse cancelInDb(String orderCode, Long customerId, String reason) {
        return tx.execute(s -> {
            CustomerOrder order = lockOrThrow(orderCode);
            if (customerId != null && !customerId.equals(order.getCustomerId())) {
                throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);   // không lộ đơn của người khác
            }
            String why = reason == null || reason.isBlank() ? null : reason.strip();
            OrderStatus from = order.cancel(why, clock.instant());
            history.record(order, from, OrderStatus.CANCELLED, why);
            log.info("Cancelled order={} from={} byCustomer={}", orderCode, from, customerId != null);
            return OrderMapper.toResponse(order);
        });
    }

    /** Nhả hàng rồi đánh dấu. Lỗi thì để nguyên stock_released = false cho job lần sau. */
    void releaseStock(String orderCode) {
        try {
            inventory.release(orderCode);
            tx.executeWithoutResult(s -> orders.markStockReleased(orderCode));
        } catch (RuntimeException e) {
            log.warn("Release stock for order={} failed, will retry: {}", orderCode, e.getMessage());
        }
    }

    private CustomerOrder lockOrThrow(String orderCode) {
        return orders.findByOrderCodeForUpdate(orderCode)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }
}
