package com.flowershop.order.service;

import com.flowershop.common.api.PageResponse;
import com.flowershop.common.error.BusinessException;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.OrderStatus;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.dto.StatusHistoryResponse;
import com.flowershop.order.error.OrderErrorCode;
import com.flowershop.order.repository.CustomerOrderRepository;
import com.flowershop.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerOrderRepository orders;
    private final OrderStatusHistoryRepository history;

    @Transactional(readOnly = true)
    public OrderResponse getForCustomer(String orderCode, long customerId) {
        return OrderMapper.toResponse(orders.findByOrderCodeAndCustomerId(orderCode, customerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listForCustomer(long customerId, int page, int size) {
        return toPage(orders.findByCustomerId(customerId, pageable(page, size)));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String orderCode) {
        return OrderMapper.toResponse(findOrThrow(orderCode));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(OrderStatus status, int page, int size) {
        return toPage(status == null ? orders.findAll(pageable(page, size))
                : orders.findByStatus(status, pageable(page, size)));
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> history(String orderCode) {
        return history.findByOrderIdOrderByIdAsc(findOrThrow(orderCode).getId()).stream()
                .map(OrderMapper::toResponse).toList();
    }

    private CustomerOrder findOrThrow(String orderCode) {
        return orders.findByOrderCode(orderCode)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "placedAt"));
    }

    private static PageResponse<OrderResponse> toPage(Page<CustomerOrder> result) {
        return new PageResponse<>(result.getContent().stream().map(OrderMapper::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
