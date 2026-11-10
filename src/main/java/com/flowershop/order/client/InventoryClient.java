package com.flowershop.order.client;

import com.flowershop.common.api.ApiResponse;
import com.flowershop.common.client.RemoteServiceException;
import com.flowershop.common.client.ServiceClientFactory;
import com.flowershop.common.client.ServiceTokenClient;
import com.flowershop.common.error.BusinessException;
import com.flowershop.order.config.AppConfig;
import com.flowershop.order.config.OrderProperties;
import com.flowershop.order.error.OrderErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Giữ / xác nhận / nhả hàng bên inventory-service (API /internal, cần inventory:reserve).
 * Cả ba thao tác đều idempotent theo orderCode bên inventory: gọi lại an toàn, nên mọi chỗ "không rõ kết quả"
 * (timeout...) đều xử lý được bằng cách gọi lại hoặc gọi release để bù trừ.
 */
@Slf4j
@Component
public class InventoryClient {

    record ReserveBody(String orderCode, List<Item> items, Integer holdMinutes, Instant freshUntil) {
        record Item(String sku, int quantity) {
        }
    }

    record Reservation(String orderCode, String status) {
    }

    interface ReservationApi {
        @PostExchange("/internal/v1/reservations")
        ApiResponse<Reservation> reserve(@RequestBody ReserveBody body);

        @PostExchange("/internal/v1/reservations/{orderCode}/confirm")
        ApiResponse<Reservation> confirm(@PathVariable String orderCode);

        @PostExchange("/internal/v1/reservations/{orderCode}/release")
        ApiResponse<Reservation> release(@PathVariable String orderCode);
    }

    private final ReservationApi api;

    public InventoryClient(ServiceClientFactory clients, OrderProperties props, ServiceTokenClient serviceToken) {
        this.api = clients.create(ReservationApi.class, AppConfig.endpoint("inventory-service", props.inventory()),
                serviceToken);
    }

    /** @param items sku → số lượng */
    public void reserve(String orderCode, Map<String, Integer> items, int holdMinutes, Instant freshUntil) {
        List<ReserveBody.Item> lines = items.entrySet().stream()
                .map(e -> new ReserveBody.Item(e.getKey(), e.getValue())).toList();
        try {
            api.reserve(new ReserveBody(orderCode, lines, holdMinutes, freshUntil));
        } catch (RemoteServiceException e) {
            if (e.hasCode("INV-409-001")) {
                throw new BusinessException(OrderErrorCode.OUT_OF_STOCK);
            }
            throw unavailable("reserve", orderCode, e);
        }
    }

    /** Phiếu đã hết hạn / đã nhả / không tồn tại thì ném HOLD_EXPIRED: đơn không xác nhận được nữa. */
    public void confirm(String orderCode) {
        try {
            api.confirm(orderCode);
        } catch (RemoteServiceException e) {
            if (e.hasCode("INV-409-003") || e.hasCode("INV-404-002")) {
                throw new BusinessException(OrderErrorCode.HOLD_EXPIRED);
            }
            throw unavailable("confirm", orderCode, e);
        }
    }

    /** Phiếu không tồn tại (giữ hàng chưa thành công) cũng coi như đã nhả. */
    public void release(String orderCode) {
        try {
            api.release(orderCode);
        } catch (RemoteServiceException e) {
            if (e.hasCode("INV-404-002")) {
                return;
            }
            throw unavailable("release", orderCode, e);
        }
    }

    private static BusinessException unavailable(String action, String orderCode, RemoteServiceException e) {
        log.error("inventory {} failed for order={}: {}", action, orderCode, e.getMessage(), e);
        return new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
