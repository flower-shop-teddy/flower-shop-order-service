package com.flowershop.order.web;

import com.flowershop.common.api.ApiResponse;
import com.flowershop.common.api.PageResponse;
import com.flowershop.order.domain.OrderStatus;
import com.flowershop.order.dto.CancelOrderRequest;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.dto.StatusHistoryResponse;
import com.flowershop.order.service.OrderLifecycleService;
import com.flowershop.order.service.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API của nhân viên cửa hàng. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class StaffOrderController {

    private final OrderLifecycleService lifecycle;
    private final OrderQueryService query;

    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResponse<OrderResponse>> list(@RequestParam(required = false) OrderStatus status,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(query.list(status, page, size));
    }

    @GetMapping("/{orderCode}")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<OrderResponse> get(@PathVariable String orderCode) {
        return ApiResponse.success(query.get(orderCode));
    }

    @GetMapping("/{orderCode}/history")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<List<StatusHistoryResponse>> history(@PathVariable String orderCode) {
        return ApiResponse.success(query.history(orderCode));
    }

    /** Shop đã gọi khách xác nhận đơn (COD). */
    @PostMapping("/{orderCode}/confirm")
    @PreAuthorize("hasAuthority('order:manage')")
    public ApiResponse<OrderResponse> confirm(@PathVariable String orderCode) {
        return ApiResponse.success(lifecycle.confirm(orderCode));
    }

    @PostMapping("/{orderCode}/prepare")
    @PreAuthorize("hasAuthority('order:manage')")
    public ApiResponse<OrderResponse> prepare(@PathVariable String orderCode) {
        return ApiResponse.success(lifecycle.advance(orderCode, OrderStatus.CONFIRMED));
    }

    @PostMapping("/{orderCode}/ship")
    @PreAuthorize("hasAuthority('order:manage')")
    public ApiResponse<OrderResponse> ship(@PathVariable String orderCode) {
        return ApiResponse.success(lifecycle.advance(orderCode, OrderStatus.PREPARING));
    }

    @PostMapping("/{orderCode}/complete")
    @PreAuthorize("hasAuthority('order:manage')")
    public ApiResponse<OrderResponse> complete(@PathVariable String orderCode) {
        return ApiResponse.success(lifecycle.advance(orderCode, OrderStatus.DELIVERING));
    }

    @PostMapping("/{orderCode}/cancel")
    @PreAuthorize("hasAuthority('order:manage')")
    public ApiResponse<OrderResponse> cancel(@PathVariable String orderCode,
                                             @Valid @RequestBody(required = false) CancelOrderRequest req) {
        return ApiResponse.success(lifecycle.cancel(orderCode, null, req == null ? null : req.reason()));
    }
}
