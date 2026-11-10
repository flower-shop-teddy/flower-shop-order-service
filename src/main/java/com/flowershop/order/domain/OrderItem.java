package com.flowershop.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Bản chụp sản phẩm lúc đặt: tên và giá KHÔNG đổi theo catalog. */
@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private CustomerOrder order;

    @Column(nullable = false, length = 30, updatable = false)
    private String sku;

    @Column(name = "flower_name", nullable = false, length = 150, updatable = false)
    private String flowerName;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineTotal;

    static OrderItem of(CustomerOrder order, String sku, String flowerName, BigDecimal unitPrice, int quantity) {
        OrderItem item = new OrderItem();
        item.order = order;
        item.sku = sku;
        item.flowerName = flowerName;
        item.unitPrice = unitPrice;
        item.quantity = quantity;
        item.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return item;
    }
}
