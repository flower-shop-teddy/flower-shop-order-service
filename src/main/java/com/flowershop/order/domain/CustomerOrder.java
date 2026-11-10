package com.flowershop.order.domain;

import com.flowershop.common.error.BusinessException;
import com.flowershop.order.error.OrderErrorCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Đơn hàng. Trạng thái chỉ đổi qua các method bên dưới (xem {@link OrderStatus}). */
@Entity
@Table(name = "customer_order")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerOrder {

    public static final long MAX_CODE_NUMBER = 9_999_999L;   // khớp MAXVALUE của order_code_seq

    /** Người nhận + địa chỉ, chụp lại từ sổ địa chỉ lúc đặt. */
    public record Recipient(String name, String phone, String addressLine, String ward, String province,
                            String note) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 20, updatable = false)
    private String orderCode;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal shippingFee;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "recipient_name", nullable = false, length = 150, updatable = false)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 10, updatable = false)
    private String recipientPhone;

    @Column(name = "address_line", nullable = false, updatable = false)
    private String addressLine;

    @Column(length = 100, updatable = false)
    private String ward;

    @Column(nullable = false, length = 100, updatable = false)
    private String province;

    @Column(name = "address_note", length = 500, updatable = false)
    private String addressNote;

    @Column(name = "delivery_date", nullable = false, updatable = false)
    private LocalDate deliveryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_slot", nullable = false, length = 20, updatable = false)
    private DeliverySlot deliverySlot;

    @Column(name = "card_message", length = 300, updatable = false)
    private String cardMessage;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "stock_released", nullable = false)
    private boolean stockReleased;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public static CustomerOrder place(String orderCode, Long customerId, String currency, BigDecimal shippingFee,
                                      Recipient recipient, LocalDate deliveryDate, DeliverySlot slot,
                                      String cardMessage, Instant now) {
        CustomerOrder o = new CustomerOrder();
        o.orderCode = orderCode;
        o.customerId = customerId;
        o.status = OrderStatus.PENDING;
        o.currency = currency;
        o.subtotal = BigDecimal.ZERO;
        o.shippingFee = shippingFee;
        o.total = shippingFee;
        o.recipientName = recipient.name();
        o.recipientPhone = recipient.phone();
        o.addressLine = recipient.addressLine();
        o.ward = recipient.ward();
        o.province = recipient.province();
        o.addressNote = recipient.note();
        o.deliveryDate = deliveryDate;
        o.deliverySlot = slot;
        o.cardMessage = cardMessage;
        o.placedAt = now;
        return o;
    }

    /** Tổng tiền tính lại mỗi lần thêm dòng: không bao giờ lệch với các dòng (DB cũng có CHECK). */
    public void addItem(String sku, String flowerName, BigDecimal unitPrice, int quantity) {
        OrderItem item = OrderItem.of(this, sku, flowerName, unitPrice, quantity);
        items.add(item);
        subtotal = subtotal.add(item.getLineTotal());
        total = subtotal.add(shippingFee);
    }

    public void confirm(Instant now) {
        requireStatus(OrderStatus.PENDING);
        status = OrderStatus.CONFIRMED;
        confirmedAt = now;
    }

    /** CONFIRMED → PREPARING → DELIVERING → DELIVERED. @return trạng thái trước khi chuyển */
    public OrderStatus advance(Instant now) {
        OrderStatus from = status;
        OrderStatus next = status.next();
        if (next == null) {
            throw new BusinessException(OrderErrorCode.INVALID_TRANSITION);
        }
        status = next;
        if (next == OrderStatus.DELIVERED) {
            deliveredAt = now;
        }
        return from;
    }

    /** Hủy trong DB. Hàng bên inventory nhả SAU (stockReleased = false tới khi nhả xong). */
    public OrderStatus cancel(String reason, Instant now) {
        if (!status.isCancellable()) {
            throw new BusinessException(OrderErrorCode.NOT_CANCELLABLE);
        }
        OrderStatus from = status;
        status = OrderStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = now;
        stockReleased = false;
        return from;
    }

    public void markStockReleased() {
        stockReleased = true;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new BusinessException(OrderErrorCode.INVALID_TRANSITION);
        }
    }
}
