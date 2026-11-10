package com.flowershop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Chỉ thêm. @Immutable: Hibernate không bao giờ sinh câu UPDATE cho entity này. */
@Entity
@Immutable
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private OrderStatus toStatus;

    @Column(length = 200)
    private String reason;

    @Column(length = 100)
    private String actor;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static OrderStatusHistory of(Long orderId, OrderStatus from, OrderStatus to, String reason,
                                        String actor, String correlationId, Instant now) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.orderId = orderId;
        h.fromStatus = from;
        h.toStatus = to;
        h.reason = reason;
        h.actor = actor;
        h.correlationId = correlationId;
        h.occurredAt = now;
        return h;
    }
}
