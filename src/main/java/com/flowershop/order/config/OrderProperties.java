package com.flowershop.order.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/** Cấu hình order.* trong application.yml. Sai cấu hình thì service không khởi động (fail fast). */
@Validated
@ConfigurationProperties(prefix = "order")
public record OrderProperties(
        @Valid @NotNull Rules rules,
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull ServiceClient serviceClient,
        @Valid @NotNull Endpoint identity,
        @Valid @NotNull Endpoint catalog,
        @Valid @NotNull Endpoint customer,
        @Valid @NotNull Endpoint inventory
) {
    /**
     * @param pendingTtl     shop phải xác nhận đơn trong thời gian này, quá thì đơn tự hủy
     * @param holdBuffer     inventory giữ hàng lâu hơn pendingTtl một chút: đơn hủy trước, phiếu giữ hết hạn sau
     * @param minLeadTime    đặt trước tối thiểu bao lâu so với đầu khung giờ giao (thời gian cắm hoa + đi đường)
     * @param maxAdvanceDays đặt trước tối đa bao nhiêu ngày (hoa tươi, kho chỉ có hàng vài ngày tới)
     */
    public record Rules(
            @NotNull Duration pendingTtl,
            @NotNull Duration holdBuffer,
            @NotNull Duration minLeadTime,
            @Min(1) @Max(30) int maxAdvanceDays,
            @NotNull @PositiveOrZero BigDecimal shippingFee,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency
    ) {
    }

    public record Jobs(boolean enabled, @Min(1) @Max(1000) int batchSize) {
    }

    /** Tài khoản của order-service ở identity (client_credentials). Secret lấy từ biến môi trường. */
    public record ServiceClient(@NotBlank String clientId, @NotBlank String secret) {
        @Override
        public String toString() {
            return "ServiceClient[clientId=" + clientId + "]";
        }
    }

    public record Endpoint(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }
}
