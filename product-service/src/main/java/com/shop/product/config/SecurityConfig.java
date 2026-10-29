package com.shop.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * GET sản phẩm / danh mục là public (docs/03-SECURITY.md §5): khách chưa đăng nhập xem được,
 * order-service lấy giá không cần token. Mọi thao tác ghi cần token, permission kiểm tra bằng @PreAuthorize.
 *
 * Lưu ý: trang quản lý của seller nằm ở /api/seller/products chứ không ở GET /api/products/...,
 * vì mọi GET dưới /api/products/** đều public.
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
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**", "/api/categories").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
