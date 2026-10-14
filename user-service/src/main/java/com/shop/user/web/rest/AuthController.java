package com.shop.user.web.rest;

import com.shop.user.application.port.TokenProvider.TokenSet;
import com.shop.user.application.service.AuthService;
import com.shop.user.web.rest.AuthRequests.LoginRequest;
import com.shop.user.web.rest.AuthRequests.RefreshRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App tự đăng nhập qua API này thay vì redirect sang trang login của Keycloak (direct access grant,
 * client public "shop-app"). Không cần token để gọi: đây chính là nơi XIN token.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return toResponse(auth.login(req.username(), req.password()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return toResponse(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static TokenResponse toResponse(TokenSet t) {
        return new TokenResponse(t.accessToken(), t.refreshToken(), t.expiresIn(), t.refreshExpiresIn(), t.tokenType());
    }

    record TokenResponse(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn, String tokenType) {
    }
}
