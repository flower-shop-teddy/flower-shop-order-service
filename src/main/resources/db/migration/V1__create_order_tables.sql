-- Quy tắc Flyway: file migration đã chạy thì KHÔNG BAO GIỜ sửa. Muốn đổi thì tạo V2__..., V3__...
--
-- Đơn hàng lưu BẢN CHỤP (snapshot) tại thời điểm đặt: giá, tên hoa, người nhận, địa chỉ.
-- Sau này catalog đổi giá hay khách sửa sổ địa chỉ, đơn cũ vẫn đúng như lúc khách đặt.

-- Mã đơn cho người đọc (DH_0000001). Cũng là khóa idempotency khi giữ hàng bên inventory-service.
CREATE SEQUENCE order_code_seq START WITH 1 INCREMENT BY 1 MAXVALUE 9999999 NO CYCLE;

-- "order" là từ khóa SQL nên bảng tên customer_order
CREATE TABLE customer_order (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_code      VARCHAR(20)    NOT NULL,
    -- Id hồ sơ bên customer-service. Không có FOREIGN KEY vì nằm ở DATABASE KHÁC.
    customer_id     BIGINT         NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    subtotal        NUMERIC(19, 4) NOT NULL,
    shipping_fee    NUMERIC(19, 4) NOT NULL,
    total           NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    recipient_name  VARCHAR(100)   NOT NULL,
    recipient_phone VARCHAR(10)    NOT NULL,
    address_line    VARCHAR(255)   NOT NULL,
    ward            VARCHAR(100),
    province        VARCHAR(100)   NOT NULL,
    address_note    VARCHAR(255),
    delivery_date   DATE           NOT NULL,
    delivery_slot   VARCHAR(20)    NOT NULL,
    card_message    VARCHAR(300),            -- lời nhắn trên thiệp
    cancel_reason   VARCHAR(200),
    -- Đơn đã hủy nhưng CHƯA nhả được hàng bên inventory (inventory lỗi lúc hủy): job gọi lại tới khi xong
    stock_released  BOOLEAN        NOT NULL DEFAULT FALSE,
    placed_at       TIMESTAMPTZ    NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL,
    created_by      VARCHAR(100)   NOT NULL,
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),

    CONSTRAINT uk_customer_order_code UNIQUE (order_code),
    CONSTRAINT ck_customer_order_status CHECK (status IN
        ('PENDING', 'CONFIRMED', 'PREPARING', 'DELIVERING', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_customer_order_slot CHECK (delivery_slot IN ('MORNING', 'AFTERNOON', 'EVENING')),
    CONSTRAINT ck_customer_order_amounts CHECK (subtotal > 0 AND shipping_fee >= 0),
    -- Tổng tiền luôn khớp: dù code tính sai cũng không lưu được
    CONSTRAINT ck_customer_order_total CHECK (total = subtotal + shipping_fee),
    CONSTRAINT ck_customer_order_cancel CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX idx_customer_order_customer ON customer_order (customer_id, placed_at DESC);
CREATE INDEX idx_customer_order_status ON customer_order (status, placed_at);
-- Hai job: đơn PENDING quá hạn xác nhận, và đơn đã hủy chưa nhả hàng
CREATE INDEX idx_customer_order_pending ON customer_order (placed_at) WHERE status = 'PENDING';
CREATE INDEX idx_customer_order_unreleased ON customer_order (cancelled_at)
    WHERE status = 'CANCELLED' AND stock_released = FALSE;

CREATE TABLE order_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT         NOT NULL REFERENCES customer_order (id),
    sku         VARCHAR(30)    NOT NULL,
    flower_name VARCHAR(150)   NOT NULL,
    unit_price  NUMERIC(19, 4) NOT NULL,
    quantity    INT            NOT NULL,
    line_total  NUMERIC(19, 4) NOT NULL,
    CONSTRAINT uk_order_item_sku UNIQUE (order_id, sku),
    CONSTRAINT ck_order_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_order_item_price CHECK (unit_price > 0),
    CONSTRAINT ck_order_item_total CHECK (line_total = unit_price * quantity)
);

-- Lịch sử trạng thái: CHỈ THÊM. Trả lời được: ai hủy đơn, ai xác nhận, lúc nào.
CREATE TABLE order_status_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id       BIGINT       NOT NULL REFERENCES customer_order (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    reason         VARCHAR(200),
    actor          VARCHAR(100),
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, id);

-- Idempotency-Key của request đặt đơn. Khách bấm "Đặt hàng" hai lần (hoặc app tự gửi lại khi mạng chập chờn)
-- với cùng key thì chỉ có MỘT đơn. order_code NULL = request đầu tiên đang xử lý.
CREATE TABLE order_request (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT      NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,     -- SHA-256: cùng key mà nội dung khác thì từ chối
    order_code      VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_order_request_key UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_order_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);
