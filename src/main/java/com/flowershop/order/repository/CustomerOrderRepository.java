package com.flowershop.order.repository;

import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    @Query(value = "SELECT nextval('order_code_seq')", nativeQuery = true)
    long nextOrderCodeNumber();

    @EntityGraph(attributePaths = "items")
    Optional<CustomerOrder> findByOrderCode(String orderCode);

    /** Khách chỉ thấy đơn của mình: sai chủ trả 404 như không tồn tại (không lộ mã đơn của người khác). */
    @EntityGraph(attributePaths = "items")
    Optional<CustomerOrder> findByOrderCodeAndCustomerId(String orderCode, Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.orderCode = :orderCode")
    Optional<CustomerOrder> findByOrderCodeForUpdate(@Param("orderCode") String orderCode);

    Page<CustomerOrder> findByCustomerId(Long customerId, Pageable pageable);

    Page<CustomerOrder> findByStatus(OrderStatus status, Pageable pageable);

    /** Đơn PENDING quá hạn xác nhận. SKIP LOCKED: nhiều instance chạy job không giẫm lên nhau. */
    @Query(value = """
            SELECT * FROM customer_order
            WHERE status = 'PENDING' AND placed_at < :placedBefore
            ORDER BY placed_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<CustomerOrder> findOverduePendingForUpdate(@Param("placedBefore") Instant placedBefore,
                                                    @Param("limit") int limit);

    /** Đơn đã hủy mà chưa nhả được hàng (inventory lỗi lúc hủy). */
    @Query("""
            select o.orderCode from CustomerOrder o
            where o.status = com.flowershop.order.domain.OrderStatus.CANCELLED and o.stockReleased = false
            order by o.cancelledAt
            """)
    List<String> findUnreleasedOrderCodes(Pageable pageable);

    /** Đánh dấu đã nhả hàng. Điều kiện stock_released = false: gọi nhiều lần cũng chỉ đổi một lần. */
    @Modifying
    @Query("""
            update CustomerOrder o set o.stockReleased = true
            where o.orderCode = :orderCode and o.stockReleased = false
            """)
    int markStockReleased(@Param("orderCode") String orderCode);
}
