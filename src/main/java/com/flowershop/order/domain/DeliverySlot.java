package com.flowershop.order.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Khung giờ giao hàng, theo giờ Việt Nam. */
public enum DeliverySlot {
    MORNING(LocalTime.of(8, 0), LocalTime.of(12, 0)),
    AFTERNOON(LocalTime.of(13, 0), LocalTime.of(17, 0)),
    EVENING(LocalTime.of(18, 0), LocalTime.of(21, 0));

    public static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final LocalTime start;
    private final LocalTime end;

    DeliverySlot(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public ZonedDateTime startOn(LocalDate date) {
        return date.atTime(start).atZone(SHOP_ZONE);
    }

    /** Hoa phải còn tươi tới hết khung giờ giao: dùng làm freshUntil khi giữ hàng. */
    public ZonedDateTime endOn(LocalDate date) {
        return date.atTime(end).atZone(SHOP_ZONE);
    }
}
