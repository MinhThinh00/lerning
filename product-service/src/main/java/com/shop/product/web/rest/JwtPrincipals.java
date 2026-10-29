package com.shop.product.web.rest;

import com.shop.product.application.access.Caller;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Chuyển token (khái niệm của tầng web/security) thành object của tầng application. */
final class JwtPrincipals {

    private JwtPrincipals() {
    }

    static Caller caller(JwtAuthenticationToken auth) {
        return new Caller(auth.getToken().getSubject(),
            auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }
}
