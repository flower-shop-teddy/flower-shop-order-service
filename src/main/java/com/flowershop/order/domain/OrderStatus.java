package com.flowershop.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING ──confirm──► CONFIRMED ──prepare──► PREPARING ──ship──► DELIVERING ──complete──► DELIVERED
 *    │                     │
 *    └──────cancel─────────┴──► CANCELLED
 * Đã cắm hoa (PREPARING) thì không hủy được nữa: hoa đã dùng, không trả lại kho.
 */
public enum OrderStatus {
    PENDING, CONFIRMED, PREPARING, DELIVERING, DELIVERED, CANCELLED;

    private static final Set<OrderStatus> CANCELLABLE = EnumSet.of(PENDING, CONFIRMED);

    public boolean isCancellable() {
        return CANCELLABLE.contains(this);
    }

    /** Bước tiếp theo trong luồng giao hàng bình thường (không tính hủy). */
    public OrderStatus next() {
        return switch (this) {
            case CONFIRMED -> PREPARING;
            case PREPARING -> DELIVERING;
            case DELIVERING -> DELIVERED;
            default -> null;
        };
    }
}
