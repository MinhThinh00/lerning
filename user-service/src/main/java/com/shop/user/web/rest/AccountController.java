package com.shop.user.web.rest;

import com.shop.user.application.dto.UserDtos.AccountView;
import com.shop.user.application.dto.UserDtos.UpdateAccountCommand;
import com.shop.user.application.service.AccountService;
import com.shop.user.web.rest.UserRequests.UpdateAccountRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tài khoản của chính mình: chỉ cần đăng nhập, không cần permission.
 * Đổi mật khẩu: dùng Account Console của Keycloak (/realms/shop/account), không làm API riêng.
 */
@RestController
@RequestMapping("/api/account")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    AccountView get(@AuthenticationPrincipal Jwt jwt) {
        return accounts.getCurrent(JwtPrincipals.currentUser(jwt));
    }

    @PutMapping
    AccountView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateAccountRequest req) {
        return accounts.updateCurrent(JwtPrincipals.currentUser(jwt),
            new UpdateAccountCommand(req.phoneNumber(), req.avatarUrl(), req.languageKey()));
    }
}
