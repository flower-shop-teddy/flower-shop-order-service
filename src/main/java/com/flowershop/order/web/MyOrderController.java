package com.flowershop.order.web;

import com.flowershop.common.api.ApiResponse;
import com.flowershop.common.api.PageResponse;
import com.flowershop.common.security.CurrentUser;
import com.flowershop.order.dto.CancelOrderRequest;
import com.flowershop.order.dto.OrderResponse;
import com.flowershop.order.dto.PlaceOrderRequest;
import com.flowershop.order.service.OrderLifecycleService;
import com.flowershop.order.service.OrderPlacementService;
import com.flowershop.order.service.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API của khách: customerId LUÔN lấy từ token, không nhận từ URL hay body. */
@RestController
@RequestMapping("/api/v1/orders/me")
@RequiredArgsConstructor
public class MyOrderController {

    private final OrderPlacementService placement;
    private final OrderLifecycleService lifecycle;
    private final OrderQueryService query;

    /** 201: đơn mới. 200: gửi lại cùng Idempotency-Key, trả đơn đã tạo (không tạo thêm). */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> place(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest req) {
        OrderPlacementService.PlaceResult result =
                placement.place(CurrentUser.requireCustomerId(), idempotencyKey, req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(result.order()));
    }

    @GetMapping
    public ApiResponse<PageResponse<OrderResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(query.listForCustomer(CurrentUser.requireCustomerId(), page, size));
    }

    @GetMapping("/{orderCode}")
    public ApiResponse<OrderResponse> get(@PathVariable String orderCode) {
        return ApiResponse.success(query.getForCustomer(orderCode, CurrentUser.requireCustomerId()));
    }

    @PostMapping("/{orderCode}/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable String orderCode,
                                             @Valid @RequestBody(required = false) CancelOrderRequest req) {
        return ApiResponse.success(lifecycle.cancel(orderCode, CurrentUser.requireCustomerId(),
                req == null ? null : req.reason()));
    }
}
