package com.flowershop.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowershop.common.security.ApiSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * /api/v1/orders/me/**: khách hàng (đã đăng nhập; controller kiểm tra user_type = CUSTOMER, id lấy từ token).
 * Còn lại: nhân viên, đọc cần order:read, thao tác cần order:manage.
 * Tự kiểm tra JWT dù gateway đã kiểm tra (zero trust).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        ApiSecurity.configure(http, objectMapper);
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/orders/me", "/api/v1/orders/me/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/**").hasAuthority("order:read")
                .requestMatchers("/api/v1/orders", "/api/v1/orders/**").hasAuthority("order:manage")
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
