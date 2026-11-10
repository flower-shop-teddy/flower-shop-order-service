package com.flowershop.order.repository;

import com.flowershop.order.domain.OrderRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRequestRepository extends JpaRepository<OrderRequest, Long> {

    Optional<OrderRequest> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);
}
