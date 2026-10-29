package com.shop.order.application.access;

import java.util.Set;

/**
 * Người đang gọi use case: id (sub) + authorities (realm role + permission).
 * Tầng application nhận object này thay vì Jwt, để không phụ thuộc Spring Security.
 */
public record Caller(String id, Set<String> authorities) {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    public Caller {
        authorities = Set.copyOf(authorities);
    }

    public boolean has(String authority) {
        return authorities.contains(authority);
    }

    public boolean isAdmin() {
        return has(ROLE_ADMIN);
    }
}
