package com.flowershop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Một Idempotency-Key của khách. orderCode null = request đầu tiên với key này đang xử lý. */
@Entity
@Table(name = "order_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "order_code", length = 20)
    private String orderCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static OrderRequest claim(Long customerId, String key, String requestHash, Instant now) {
        OrderRequest r = new OrderRequest();
        r.customerId = customerId;
        r.idempotencyKey = key;
        r.requestHash = requestHash;
        r.createdAt = now;
        return r;
    }

    public void complete(String orderCode) {
        this.orderCode = orderCode;
    }
}
