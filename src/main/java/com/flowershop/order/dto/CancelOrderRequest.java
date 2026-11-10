package com.flowershop.order.dto;

import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@Size(max = 200) String reason) {
}
