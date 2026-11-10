package com.flowershop.order.service;

import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.OrderStatusHistory;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.dto.StatusHistoryResponse;

final class OrderMapper {

    private OrderMapper() {
    }

    static OrderResponse toResponse(CustomerOrder o) {
        return new OrderResponse(o.getOrderCode(), o.getStatus(), o.getCustomerId(),
                o.getItems().stream().map(i -> new OrderResponse.Item(i.getSku(), i.getFlowerName(), i.getUnitPrice(),
                        i.getQuantity(), i.getLineTotal())).toList(),
                o.getSubtotal(), o.getShippingFee(), o.getTotal(), o.getCurrency(),
                new OrderResponse.Recipient(o.getRecipientName(), o.getRecipientPhone(), o.getAddressLine(),
                        o.getWard(), o.getProvince(), o.getAddressNote()),
                o.getDeliveryDate(), o.getDeliverySlot(), o.getCardMessage(), o.getCancelReason(),
                o.getPlacedAt(), o.getConfirmedAt(), o.getDeliveredAt(), o.getCancelledAt());
    }

    static StatusHistoryResponse toResponse(OrderStatusHistory h) {
        return new StatusHistoryResponse(h.getFromStatus(), h.getToStatus(), h.getReason(), h.getActor(),
                h.getOccurredAt());
    }
}
