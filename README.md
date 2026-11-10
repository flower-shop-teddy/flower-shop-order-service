# flower-shop-order-service

Đơn hàng: khách đặt hoa (COD), shop gọi xác nhận, chuẩn bị, giao. Lưu **bản chụp** giá và địa chỉ lúc đặt.
PostgreSQL + Flyway.

Cổng local: `8085`

## Trạng thái đơn

```
PENDING ──confirm──► CONFIRMED ──prepare──► PREPARING ──ship──► DELIVERING ──complete──► DELIVERED
   │                     │
   └──────cancel─────────┴──► CANCELLED          (đã PREPARING thì không hủy được: hoa đã cắm)
PENDING quá 60 phút chưa xác nhận ──job──► CANCELLED
```

## Đặt đơn: một nghiệp vụ, bốn service

```
khách ──POST /api/v1/orders/me (Idempotency-Key)──► order-service
   1. giữ chỗ Idempotency-Key                         (order_db)
   2. giá + tên hoa          ──GET /api/v1/flowers/sku/{sku}──► catalog   (public)
      địa chỉ người nhận     ──GET /api/v1/customers/{id}/addresses──► customer (service token: customer:read)
   3. giữ hàng FEFO tới hết khung giờ giao ──POST /internal/v1/reservations──► inventory (inventory:reserve)
   4. lưu đơn PENDING + lịch sử                        (order_db)
```

- **Giá do server lấy**, request không có giá. Đơn lưu bản chụp tên + giá + người nhận + địa chỉ: catalog đổi giá hay khách sửa sổ địa chỉ không ảnh hưởng đơn cũ.
- **customerId lấy từ token** của khách. Địa chỉ phải thuộc đúng khách đó, nếu không trả `ORD-404-002`.
- **Idempotency-Key** (bắt buộc): gửi lại cùng key và cùng nội dung trả `200` kèm đơn cũ; cùng key mà khác nội dung trả `ORD-422-001`; hai request cùng key đến cùng lúc thì request sau nhận `ORD-409-002`.
- **Bù trừ**: lỗi sau bước 3, kể cả timeout không rõ đã giữ hàng chưa, sẽ gọi `release` (idempotent) rồi trả lại key để khách thử lại. Nhả không được thì phiếu giữ cũng tự hết hạn bên inventory.
- **Service token**: order-service xin token của chính nó ở identity (client_credentials, `ServiceTokenClient`). Khách không có và không cần quyền `customer:read`, `inventory:reserve`.
- Không gọi service khác trong lúc đang giữ transaction DB.

## Giữ cho đơn và kho luôn khớp

| Thao tác | Thứ tự | Vì sao |
|---|---|---|
| Xác nhận | inventory `confirm` **trước**, thành công mới ghi `CONFIRMED` | ghi trước mà confirm lỗi thì phiếu giữ hết hạn trong khi đơn đã xác nhận, tức là bán thứ không còn giữ |
| Hủy | ghi `CANCELLED` **trước** (`stock_released = false`), rồi mới `release` | nhả trước thì đơn có thể vừa bị chuyển `PREPARING`, đơn vẫn chạy mà hàng đã trả kho. Inventory lỗi thì job gọi lại tới khi nhả được |
| Hết hạn xác nhận | job hủy đơn `PENDING` sau 60 phút; inventory giữ 70 phút | đơn luôn hủy trước khi phiếu giữ hết hạn |

Xác nhận muộn khi phiếu giữ đã hết hạn thì đơn tự hủy và trả `ORD-409-005`.

## API

Khách (`user_type = CUSTOMER`, chỉ đơn của mình):

| Method | Path | |
|---|---|---|
| POST | `/api/v1/orders/me` + header `Idempotency-Key` · `{addressId, items[{sku, quantity}], deliveryDate, deliverySlot: MORNING\|AFTERNOON\|EVENING, cardMessage}` | 201 / 200 (gửi lại) |
| GET | `/api/v1/orders/me` · `/api/v1/orders/me/{orderCode}` | |
| POST | `/api/v1/orders/me/{orderCode}/cancel` `{reason}` | chỉ khi PENDING/CONFIRMED |

Nhân viên:

| Method | Path | Quyền |
|---|---|---|
| GET | `/api/v1/orders?status=` · `/api/v1/orders/{orderCode}` · `/{orderCode}/history` | `order:read` |
| POST | `/api/v1/orders/{orderCode}/confirm` · `prepare` · `ship` · `complete` · `cancel` | `order:manage` |

`prepare`/`ship`/`complete` chỉ chạy được từ đúng trạng thái liền trước, nên bấm hai lần không làm đơn nhảy hai bước. `order:read`/`manage`: ADMIN, SHOP_MANAGER, SHOP_STAFF.

Luật đặt: giao trong khung MORNING 8–12h, AFTERNOON 13–17h, EVENING 18–21h (giờ VN); đặt trước ít nhất 2 giờ so với đầu khung, tối đa 7 ngày; phí giao 30.000đ; tối đa 20 sản phẩm, mỗi sản phẩm 1–99.

Mã lỗi: `ORD-400-001` thiếu/sai Idempotency-Key · `ORD-400-002` thời gian giao không hợp lệ · `ORD-400-003` SKU trùng · `ORD-404-001` không thấy đơn · `ORD-404-002` không thấy địa chỉ · `ORD-409-001` không đủ hàng · `ORD-409-002` request cùng key đang xử lý · `ORD-409-003` chuyển trạng thái sai · `ORD-409-004` không hủy được · `ORD-409-005` quá hạn giữ hàng, đơn đã hủy · `ORD-422-001` key dùng cho đơn khác · `ORD-422-002` sản phẩm không có/ngừng bán · `ORD-422-003` khác loại tiền · `ORD-503-001` service khác không phản hồi.

## Cấu hình

| Khóa | Mặc định | |
|---|---|---|
| `order.rules.pending-ttl` / `hold-buffer` | `60m` / `10m` | Hạn xác nhận / inventory giữ dư thêm |
| `order.rules.min-lead-time` / `max-advance-days` | `2h` / `7` | |
| `order.rules.shipping-fee` / `currency` | `30000` / `VND` | |
| `order.jobs.enabled` / `interval-ms` | `true` / `60000` | |
| `order.service-client.secret` | `${ORDER_SERVICE_CLIENT_SECRET}` | Phải khớp client `order-service` bên identity |

Biến môi trường: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `EUREKA_URL`, `IDENTITY_JWKS_URI`, `ORDER_SERVICE_CLIENT_SECRET`.
Profile `local` đã có sẵn secret khớp với identity, không cần đặt.

## Giới hạn đã biết

- Chưa có thanh toán online: đơn là COD, shop xác nhận bằng tay (giai đoạn 3: payment-service).
- Hết hàng chỉ báo chung `ORD-409-001`, chưa nói SKU nào thiếu (inventory có message chi tiết nhưng client chưa chuyển tiếp).
- Gọi đồng bộ, chưa có retry/circuit breaker (Resilience4j); giai đoạn sau chuyển sang sự kiện + outbox.

## Chạy

Cách khuyên dùng: chạy cả hệ thống từ repo `flower-shop-workspace` (`./clone-all.sh` rồi `./run-local.sh`).

```bash
mvn verify     # cần Docker (Testcontainers bật Postgres thật)
```

## Release và Docker image

Merge PR `dev → production` thì GitHub Actions publish image `ghcr.io/flower-shop-teddy/flower-shop-order-service:X.Y.Z` và tạo tag `vX.Y.Z`.
Chi tiết quy trình: README của repo `flower-shop-workspace`, mục "CI/CD và release".
