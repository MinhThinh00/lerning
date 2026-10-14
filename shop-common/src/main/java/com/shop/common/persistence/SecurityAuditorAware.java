package com.shop.common.persistence;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Người thực hiện = Authentication#getName().
 * Với JWT (JwtAuthenticationToken) giá trị này mặc định là claim "sub" = user id toàn hệ thống.
 * Không có user (job, Kafka consumer) → "system".
 */
public class SecurityAuditorAware implements AuditorAware<String> {

    public static final String SYSTEM = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.of(SYSTEM);
        }
        return Optional.of(auth.getName());
    }
}
