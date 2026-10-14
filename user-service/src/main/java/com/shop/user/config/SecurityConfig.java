package com.shop.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server: mỗi request phải mang "Authorization: Bearer <access token của Keycloak>".
 * Spring tự verify chữ ký (public key lấy từ JWKS của issuer), iss, aud, exp.
 * Role → authority do bean JwtAuthenticationConverter của shop-common lo (jwt(withDefaults()) tự nhặt).
 *
 * Phân quyền chi tiết theo permission đặt bằng @PreAuthorize trên controller (@EnableMethodSecurity).
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
                .requestMatchers("/api/auth/**").permitAll()   // đây là nơi XIN token, chưa có token để mà authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
