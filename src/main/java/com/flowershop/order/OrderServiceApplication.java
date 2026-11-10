package com.flowershop.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Quét thêm package common để nhận GlobalExceptionHandler, CorrelationIdFilter, SecurityExceptionHandler từ common-lib
@SpringBootApplication(scanBasePackages = {"com.flowershop.order", "com.flowershop.common"})
@ConfigurationPropertiesScan
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
