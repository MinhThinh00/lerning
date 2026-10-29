package com.shop.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API tra cứu cần token (permission kiểm tra bằng @PreAuthorize). Hai ngoại lệ không có token:
 * - /api/payments/callback: cổng gọi về, xác thực bằng chữ ký HMAC (PaymentCallbackService);
 * - /fake-gateway/**: cổng GIẢ, đóng vai hệ thống bên ngoài (chỉ bật ở dev/test).
 * Luồng thu tiền chạy qua Kafka, không qua filter này.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())   // API stateless dùng Bearer token, không dùng cookie → không bị CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/error").permitAll()    // để lỗi 404/400 hiện đúng, không bị đổi thành 401
                .requestMatchers(HttpMethod.POST, "/api/payments/callback").permitAll()
                .requestMatchers("/fake-gateway/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
