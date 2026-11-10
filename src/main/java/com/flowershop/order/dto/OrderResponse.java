package com.flowershop.order.dto;

import com.flowershop.order.domain.DeliverySlot;
import com.flowershop.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OrderResponse(
        String orderCode,
        OrderStatus status,
        Long customerId,
        List<Item> items,
        BigDecimal subtotal,
        BigDecimal shippingFee,
        BigDecimal total,
        String currency,
        Recipient recipient,
        LocalDate deliveryDate,
        DeliverySlot deliverySlot,
        String cardMessage,
        String cancelReason,
        Instant placedAt,
        Instant confirmedAt,
        Instant deliveredAt,
        Instant cancelledAt
) {
    public record Item(String sku, String flowerName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    }

    public record Recipient(String name, String phone, String addressLine, String ward, String province,
                            String note) {
    }
}
