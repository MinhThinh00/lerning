package com.shop.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ShopJwtAuthoritiesConverterTest {

    private final ShopJwtAuthoritiesConverter converter = new ShopJwtAuthoritiesConverter();

    @Test
    void mapsRealmRolesAndClientRoles() {
        Jwt jwt = jwt()
            .claim("roles", List.of("ROLE_SUPPORT", "offline_access", "default-roles-shop"))
            .claim("client_roles", List.of("user:read", "user:reset-password"))
            .build();

        assertThat(converter.convert(jwt))
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_SUPPORT", "user:read", "user:reset-password");
    }

    @Test
    void missingClaimsGiveNoAuthorities() {
        assertThat(converter.convert(jwt().build())).isEmpty();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").subject("u1");
    }
}
