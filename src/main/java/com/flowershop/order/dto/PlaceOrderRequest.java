package com.flowershop.order.dto;

import com.flowershop.order.domain.DeliverySlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Khách đặt đơn. KHÔNG có giá trong request: giá lấy từ catalog phía server (client sửa giá được thì nguy).
 * customerId cũng không có: lấy từ token.
 */
public record PlaceOrderRequest(
        @NotNull @Positive Long addressId,
        @NotEmpty @Size(max = 20) List<@Valid Item> items,
        @NotNull LocalDate deliveryDate,
        @NotNull DeliverySlot deliverySlot,
        @Size(max = 300) String cardMessage
) {
    public record Item(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,30}$") String sku,
            @NotNull @Min(1) @Max(99) Integer quantity
    ) {
    }
}
