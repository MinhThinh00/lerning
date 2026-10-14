package com.shop.common.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Map claim của token Keycloak → GrantedAuthority của Spring:
 * <ul>
 *   <li>{@code roles} (realm role, vd ROLE_ADMIN) → giữ nguyên, chỉ lấy tiền tố ROLE_
 *       (bỏ role mặc định của Keycloak như offline_access) → dùng được hasRole('ADMIN')</li>
 *   <li>{@code client_roles} (permission của client shop-service, vd user:read) → giữ nguyên
 *       → dùng được hasAuthority('user:read')</li>
 * </ul>
 * Mặc định Spring chỉ đọc claim "scope" → authority SCOPE_xxx, không biết gì về role Keycloak.
 * Hai claim trên do protocol mapper của client shop-app tạo ra (docs/03-SECURITY.md).
 */
public class ShopJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String REALM_ROLES_CLAIM = "roles";
    public static final String CLIENT_ROLES_CLAIM = "client_roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Stream<String> realmRoles = claimAsList(jwt, REALM_ROLES_CLAIM).stream()
            .filter(r -> r.startsWith("ROLE_"));
        Stream<String> clientRoles = claimAsList(jwt, CLIENT_ROLES_CLAIM).stream();
        return Stream.concat(realmRoles, clientRoles)
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> claimAsList(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values != null ? values : List.of();
    }
}
