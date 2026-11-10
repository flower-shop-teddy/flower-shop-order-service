package com.flowershop.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowershop.common.client.ServiceClientFactory;
import com.flowershop.common.client.ServiceEndpoint;
import com.flowershop.common.client.ServiceTokenClient;
import com.flowershop.common.security.JwtAuditorAware;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AppConfig {

    /** created_by / updated_by = sub của người (hoặc service) đang gọi API, không có thì "system". */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return new JwtAuditorAware();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** @LoadBalanced: URL dạng http://inventory-service được tra trên Eureka. */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public ServiceClientFactory serviceClientFactory(RestClient.Builder loadBalancedRestClientBuilder,
                                                     ObjectMapper objectMapper) {
        return new ServiceClientFactory(loadBalancedRestClientBuilder, objectMapper);
    }

    /**
     * Token của CHÍNH order-service (không phải token của khách) để gọi customer/inventory.
     * Khách không có quyền customer:read hay inventory:reserve, và không nên có.
     */
    @Bean
    public ServiceTokenClient serviceTokenClient(ServiceClientFactory factory, OrderProperties props, Clock clock) {
        return new ServiceTokenClient(factory, endpoint("identity-service", props.identity()),
                props.serviceClient().clientId(), props.serviceClient().secret(), clock);
    }

    public static ServiceEndpoint endpoint(String name, OrderProperties.Endpoint cfg) {
        return new ServiceEndpoint(name, cfg.baseUrl(), cfg.connectTimeout(), cfg.readTimeout());
    }
}
