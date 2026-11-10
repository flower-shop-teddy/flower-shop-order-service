package com.flowershop.order.service;

import com.flowershop.common.security.AuthenticatedUser;
import com.flowershop.common.security.CurrentUser;
import com.flowershop.common.web.CorrelationId;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.domain.OrderStatus;
import com.flowershop.order.domain.OrderStatusHistory;
import com.flowershop.order.repository.OrderStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Ghi lịch sử trạng thái TRONG CÙNG transaction với việc đổi trạng thái. */
@Component
@RequiredArgsConstructor
public class HistoryRecorder {

    static final String SYSTEM_ACTOR = "system";

    private final OrderStatusHistoryRepository history;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CustomerOrder order, OrderStatus from, OrderStatus to, String reason) {
        String actor = CurrentUser.get().map(AuthenticatedUser::subject).orElse(SYSTEM_ACTOR);
        history.save(OrderStatusHistory.of(order.getId(), from, to, reason, actor,
                MDC.get(CorrelationId.MDC_KEY), clock.instant()));
    }
}
