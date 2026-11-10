package com.flowershop.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Mỗi phút: hủy đơn quá hạn xác nhận, và nhả hàng cho các đơn hủy mà lần trước nhả lỗi. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderJobs {

    private final OrderLifecycleService lifecycle;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${order.jobs.interval-ms:60000}", initialDelayString = "${order.jobs.interval-ms:60000}")
    public void run() {
        try {
            lifecycle.expireOverduePending(clock.instant());
            lifecycle.retryUnreleasedStock();
        } catch (RuntimeException e) {
            log.error("Order jobs failed", e);   // không để lỗi một lần làm dừng lịch chạy
        }
    }
}
