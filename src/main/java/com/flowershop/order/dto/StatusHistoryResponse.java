package com.flowershop.order.dto;

import com.flowershop.order.domain.OrderStatus;

import java.time.Instant;

public record StatusHistoryResponse(OrderStatus from, OrderStatus to, String reason, String actor,
                                    Instant occurredAt) {
}
