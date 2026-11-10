package com.flowershop.order;

import com.flowershop.common.error.BusinessException;
import com.flowershop.order.client.CatalogClient;
import com.flowershop.order.client.CustomerClient;
import com.flowershop.order.client.InventoryClient;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.DeliverySlot;
import com.flowershop.order.error.OrderErrorCode;
import com.flowershop.order.service.OrderLifecycleService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test tích hợp với Postgres thật (Testcontainers), cần Docker đang chạy.
 * catalog / customer / inventory thay bằng mock: test kiểm tra order-service phản ứng đúng với từng kết quả
 * của service khác (thành công, hết hàng, không phản hồi...). Job tắt, test gọi thẳng service.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:postgresql://unused:5432/unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "ORDER_SERVICE_CLIENT_SECRET=unused-in-tests",
        "eureka.client.enabled=false",
        "order.jobs.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger(1000);
    private static final BigDecimal PRICE = new BigDecimal("350000");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderLifecycleService lifecycle;

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    CustomerClient customers;

    @MockitoBean
    InventoryClient inventory;

    /** Mỗi test một khách riêng: test không thấy đơn của nhau. */
    private long customerId;

    @BeforeEach
    void defaults() {
        reset(catalog, customers, inventory);
        customerId = SEQ.incrementAndGet();
        given(catalog.getActive(anyString())).willAnswer(inv -> {
            String sku = inv.getArgument(0);
            return new CatalogClient.Product(sku, "Hoa " + sku, PRICE, "VND");
        });
        given(customers.findAddress(anyLong(), anyLong())).willReturn(new CustomerOrder.Recipient(
                "Trần Văn Bình", "0987654321", "12 Lý Thường Kiệt", "Phường Cửa Nam", "Hà Nội", "Gọi trước"));
    }

    // ---------------------------------------------------------------- tokens giả (bỏ qua chữ ký)

    private RequestPostProcessor customer() {
        return customer(customerId);
    }

    private static RequestPostProcessor customer(long id) {
        return jwt().jwt(j -> j.subject("customer-" + id).claim("user_type", "CUSTOMER").claim("customer_id", id));
    }

    private static RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject("staff-uuid").claim("user_type", "STAFF"))
                .authorities(new SimpleGrantedAuthority("order:read"), new SimpleGrantedAuthority("order:manage"));
    }

    // ---------------------------------------------------------------- đặt đơn

    @Test
    void place_snapshotsPriceAndAddress_andReservesUntilEndOfDeliverySlot() throws Exception {
        LocalDate tomorrow = tomorrow();
        String body = place(key(), Map.of("ROSE-RED-10", 2), tomorrow, DeliverySlot.AFTERNOON)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderCode").value(org.hamcrest.Matchers.matchesPattern("DH_\\d{7}")))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(350000))
                .andExpect(jsonPath("$.data.items[0].lineTotal").value(700000))
                .andExpect(jsonPath("$.data.subtotal").value(700000))
                .andExpect(jsonPath("$.data.shippingFee").value(30000))
                .andExpect(jsonPath("$.data.total").value(730000))
                .andExpect(jsonPath("$.data.recipient.name").value("Trần Văn Bình"))
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.data.orderCode");

        Instant slotEnd = DeliverySlot.AFTERNOON.endOn(tomorrow).toInstant();
        // Giữ hàng 70 phút (60 chờ xác nhận + 10 dự phòng), hoa phải còn hạn tới 17:00 ngày giao
        verify(inventory).reserve(eq(code), eq(Map.of("ROSE-RED-10", 2)), eq(70), eq(slotEnd));
        verify(customers).findAddress(customerId, 7L);
    }

    @Test
    void place_sameKeySamePayload_returnsSameOrder_differentPayloadIsRejected() throws Exception {
        String key = key();
        String first = code(place(key, Map.of("LILY-01", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isCreated()));

        place(key, Map.of("LILY-01", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isOk())   // 200: đơn cũ
                .andExpect(jsonPath("$.data.orderCode").value(first));
        verify(inventory, times(1)).reserve(anyString(), any(), anyInt(), any());

        place(key, Map.of("LILY-01", 3), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORD-422-001"));
    }

    @Test
    void place_outOfStock_compensates_andSameKeyCanRetryLater() throws Exception {
        String key = key();
        willThrow(new BusinessException(OrderErrorCode.OUT_OF_STOCK))
                .given(inventory).reserve(anyString(), any(), anyInt(), any());

        place(key, Map.of("TULIP-01", 5), tomorrow(), DeliverySlot.EVENING)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORD-409-001"));
        verify(inventory).release(anyString());   // bù trừ (idempotent, phiếu không có thì inventory bỏ qua)
        assertThat(countOrders()).isZero();

        // Kho có hàng trở lại: bấm lại với CÙNG key thì đặt được (key đã được trả lại)
        willDoNothing().given(inventory).reserve(anyString(), any(), anyInt(), any());
        place(key, Map.of("TULIP-01", 5), tomorrow(), DeliverySlot.EVENING).andExpect(status().isCreated());
    }

    @Test
    void place_inventoryUnavailable_compensates_andReturns503() throws Exception {
        willThrow(new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE))
                .given(inventory).reserve(anyString(), any(), anyInt(), any());

        place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORD-503-001"));
        verify(inventory).release(anyString());   // timeout: không biết đã giữ chưa, nhả cho chắc
        assertThat(countOrders()).isZero();
    }

    @Test
    void place_unavailableProduct_stopsBeforeReservingStock() throws Exception {
        given(catalog.getActive("OLD-SKU")).willThrow(new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE));

        place(key(), Map.of("OLD-SKU", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORD-422-002"));
        verify(inventory, never()).reserve(anyString(), any(), anyInt(), any());
        verify(inventory, never()).release(anyString());
    }

    @Test
    void place_validatesKeyDeliveryTimeAndCaller() throws Exception {
        place(null, Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-400-001"));

        place(key(), Map.of("ROSE-RED-10", 1), LocalDate.now(DeliverySlot.SHOP_ZONE).minusDays(1), DeliverySlot.MORNING)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-400-002"));

        place(key(), Map.of("ROSE-RED-10", 1), LocalDate.now(DeliverySlot.SHOP_ZONE).plusDays(30), DeliverySlot.MORNING)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chỉ nhận đặt trước tối đa 7 ngày"));

        // Nhân viên không đặt đơn qua API /me (không có customer_id trong token)
        mockMvc.perform(post("/api/v1/orders/me").with(staff()).header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON).content(placeJson(Map.of("ROSE-RED-10", 1), tomorrow(),
                                DeliverySlot.MORNING)))
                .andExpect(status().isForbidden());
    }

    @Test
    void place_acceptsLongestAddressThatAddressBookAllows() throws Exception {
        // Sổ địa chỉ cho phép tên người nhận 150 ký tự, ghi chú 500 ký tự: bản chụp trong đơn phải chứa được
        given(customers.findAddress(anyLong(), anyLong())).willReturn(new CustomerOrder.Recipient(
                "N".repeat(150), "0987654321", "A".repeat(255), "W".repeat(100), "P".repeat(100), "G".repeat(500)));

        place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recipient.name").value("N".repeat(150)));
    }

    // ---------------------------------------------------------------- khách chỉ thấy đơn của mình

    @Test
    void customer_seesOnlyOwnOrders() throws Exception {
        String code = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING));
        long stranger = SEQ.incrementAndGet();

        mockMvc.perform(get("/api/v1/orders/me/" + code).with(customer())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/me/" + code).with(customer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORD-404-001"));
        mockMvc.perform(post("/api/v1/orders/me/" + code + "/cancel").with(customer(stranger)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders/me").with(customer(stranger)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        // Khách không gọi được API nhân viên
        mockMvc.perform(get("/api/v1/orders").with(customer())).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- vòng đời đơn

    @Test
    void fullLifecycle_withHistory_andNoDoubleStep() throws Exception {
        String code = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING));

        staffAction(code, "confirm").andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        verify(inventory).confirm(code);
        staffAction(code, "prepare").andExpect(jsonPath("$.data.status").value("PREPARING"));
        staffAction(code, "prepare")   // bấm hai lần: không nhảy sang DELIVERING
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORD-409-003"));
        mockMvc.perform(post("/api/v1/orders/me/" + code + "/cancel").with(customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORD-409-004"));   // đã cắm hoa: không hủy được
        staffAction(code, "ship").andExpect(jsonPath("$.data.status").value("DELIVERING"));
        staffAction(code, "complete")
                .andExpect(jsonPath("$.data.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.deliveredAt").exists());

        mockMvc.perform(get("/api/v1/orders/" + code + "/history").with(staff()))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].to").value("PENDING"))
                .andExpect(jsonPath("$.data[0].actor").value("customer-" + customerId))
                .andExpect(jsonPath("$.data[1].to").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[1].actor").value("staff-uuid"))
                .andExpect(jsonPath("$.data[4].to").value("DELIVERED"));
    }

    @Test
    void confirm_afterHoldExpired_cancelsOrder() throws Exception {
        String code = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING));
        willThrow(new BusinessException(OrderErrorCode.HOLD_EXPIRED)).given(inventory).confirm(code);

        staffAction(code, "confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORD-409-005"));
        mockMvc.perform(get("/api/v1/orders/" + code).with(staff()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        verify(inventory).release(code);
    }

    @Test
    void cancel_releasesStock_andRetriesWhenInventoryWasDown() throws Exception {
        String code = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING));
        willThrow(new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE)).given(inventory).release(code);

        // Inventory đang lỗi: đơn VẪN hủy được (khách không phải đợi), hàng nhả sau
        mockMvc.perform(post("/api/v1/orders/me/" + code + "/cancel").with(customer())
                        .contentType(APPLICATION_JSON).content("{\"reason\":\"Đổi ý\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("Đổi ý"));
        assertThat(stockReleased(code)).isFalse();

        // Inventory hoạt động lại: job nhả hàng
        willDoNothing().given(inventory).release(code);
        lifecycle.retryUnreleasedStock();
        assertThat(stockReleased(code)).isTrue();
    }

    @Test
    void expiryJob_cancelsOnlyOverduePending_andReleasesStock() throws Exception {
        String pending = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.MORNING));
        String confirmed = code(place(key(), Map.of("ROSE-RED-10", 1), tomorrow(), DeliverySlot.EVENING));
        staffAction(confirmed, "confirm");

        lifecycle.expireOverduePending(Instant.now().plus(Duration.ofHours(2)));

        mockMvc.perform(get("/api/v1/orders/" + pending).with(staff()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("Quá hạn xác nhận, hệ thống tự hủy"));
        mockMvc.perform(get("/api/v1/orders/" + confirmed).with(staff()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        verify(inventory).release(pending);
        assertThat(stockReleased(pending)).isTrue();
        mockMvc.perform(get("/api/v1/orders/" + pending + "/history").with(staff()))
                .andExpect(jsonPath("$.data[1].actor").value("system"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions place(String key, Map<String, Integer> items, LocalDate date, DeliverySlot slot)
            throws Exception {
        var request = post("/api/v1/orders/me").with(customer()).contentType(APPLICATION_JSON)
                .content(placeJson(items, date, slot));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private static String placeJson(Map<String, Integer> items, LocalDate date, DeliverySlot slot) {
        StringBuilder json = new StringBuilder("{\"addressId\":7,\"items\":[");
        items.forEach((sku, qty) -> json.append("{\"sku\":\"%s\",\"quantity\":%d},".formatted(sku, qty)));
        json.setLength(json.length() - 1);
        return json.append("],\"deliveryDate\":\"").append(date).append("\",\"deliverySlot\":\"").append(slot)
                .append("\",\"cardMessage\":\"Chúc mừng sinh nhật!\"}").toString();
    }

    private ResultActions staffAction(String code, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + code + "/" + action).with(staff()));
    }

    private long countOrders() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE customer_id = ?", Long.class, customerId);
        return n == null ? 0 : n;
    }

    private boolean stockReleased(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT stock_released FROM customer_order WHERE order_code = ?", Boolean.class, code));
    }

    private static String code(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.orderCode");
    }

    private static LocalDate tomorrow() {
        return LocalDate.now(DeliverySlot.SHOP_ZONE).plusDays(1);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
