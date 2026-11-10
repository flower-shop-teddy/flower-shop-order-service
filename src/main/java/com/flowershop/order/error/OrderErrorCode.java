package com.flowershop.order.error;

import com.flowershop.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OrderErrorCode implements ErrorCode {
    IDEMPOTENCY_KEY_REQUIRED("ORD-400-001", HttpStatus.BAD_REQUEST,
            "Thiếu hoặc sai header Idempotency-Key (8-64 ký tự chữ, số, '-')"),
    INVALID_DELIVERY_TIME("ORD-400-002", HttpStatus.BAD_REQUEST, "Thời gian giao hàng không hợp lệ"),
    DUPLICATE_SKU("ORD-400-003", HttpStatus.BAD_REQUEST, "Mỗi sản phẩm chỉ được xuất hiện một lần trong đơn"),
    ORDER_NOT_FOUND("ORD-404-001", HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng"),
    ADDRESS_NOT_FOUND("ORD-404-002", HttpStatus.NOT_FOUND, "Không tìm thấy địa chỉ giao hàng"),
    OUT_OF_STOCK("ORD-409-001", HttpStatus.CONFLICT, "Một số sản phẩm không đủ hàng cho thời gian giao đã chọn"),
    REQUEST_IN_PROGRESS("ORD-409-002", HttpStatus.CONFLICT, "Đơn hàng đang được xử lý, vui lòng đợi giây lát"),
    INVALID_TRANSITION("ORD-409-003", HttpStatus.CONFLICT, "Không thể chuyển đơn sang trạng thái này"),
    NOT_CANCELLABLE("ORD-409-004", HttpStatus.CONFLICT, "Đơn đã được chuẩn bị hoặc đã kết thúc, không thể hủy"),
    HOLD_EXPIRED("ORD-409-005", HttpStatus.CONFLICT, "Đơn đã quá hạn giữ hàng và đã được hủy"),
    IDEMPOTENCY_KEY_REUSED("ORD-422-001", HttpStatus.UNPROCESSABLE_ENTITY,
            "Idempotency-Key đã dùng cho một đơn khác"),
    PRODUCT_UNAVAILABLE("ORD-422-002", HttpStatus.UNPROCESSABLE_ENTITY, "Sản phẩm không tồn tại hoặc đã ngừng bán"),
    MIXED_CURRENCY("ORD-422-003", HttpStatus.UNPROCESSABLE_ENTITY, "Các sản phẩm trong đơn phải cùng loại tiền"),
    DEPENDENCY_UNAVAILABLE("ORD-503-001", HttpStatus.SERVICE_UNAVAILABLE,
            "Hệ thống đang bận, vui lòng thử lại sau");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    OrderErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override public String code() { return code; }
    @Override public HttpStatus status() { return status; }
    @Override public String defaultMessage() { return defaultMessage; }
}
