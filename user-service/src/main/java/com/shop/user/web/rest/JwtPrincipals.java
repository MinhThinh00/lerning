package com.shop.user.web.rest;

import com.shop.user.application.access.Caller;
import com.shop.user.application.dto.UserDtos.CurrentUser;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Chuyển token (khái niệm của tầng web/security) thành object của tầng application. */
final class JwtPrincipals {

    private JwtPrincipals() {
    }

    static CurrentUser currentUser(Jwt jwt) {
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("preferred_username"),
            jwt.getClaimAsString("email"), jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"));
    }

    static Caller caller(JwtAuthenticationToken auth) {
        return new Caller(auth.getToken().getSubject(),
            auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }
}
