package com.shop.user.web.rest;

import jakarta.validation.constraints.NotBlank;

final class AuthRequests {

    private AuthRequests() {
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }
}
