package com.shop.user.application.service;

import com.shop.user.application.port.TokenProvider;
import com.shop.user.application.port.TokenProvider.TokenSet;
import org.springframework.stereotype.Service;

/** Use case đăng nhập/làm mới token. Không permission nào cả: chưa có token thì chưa có gì để check. */
@Service
public class AuthService {

    private final TokenProvider tokens;

    public AuthService(TokenProvider tokens) {
        this.tokens = tokens;
    }

    public TokenSet login(String username, String password) {
        return tokens.passwordLogin(username, password);
    }

    public TokenSet refresh(String refreshToken) {
        return tokens.refresh(refreshToken);
    }

    public void logout(String refreshToken) {
        tokens.logout(refreshToken);
    }
}
