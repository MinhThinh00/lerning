package com.shop.user.web.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Request body của API. Validation ở đây; lỗi → 400 kèm danh sách field (GlobalExceptionHandler). */
final class UserRequests {

    static final String PHONE = "^\\+?[0-9]{8,15}$";
    static final String LANGUAGE = "^[a-z]{2}(-[A-Z]{2})?$";

    private UserRequests() {
    }

    record UpdateAccountRequest(
        @Pattern(regexp = PHONE) String phoneNumber,
        @Size(max = 500) String avatarUrl,
        @Pattern(regexp = LANGUAGE) String languageKey) {
    }

    record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Pattern(regexp = PHONE) String phoneNumber,
        @NotBlank @Size(min = 8, max = 128) String password,
        /** null → true: user phải đổi mật khẩu ở lần đăng nhập đầu. */
        Boolean temporaryPassword,
        /** null/rỗng → [ROLE_USER]. Role khác cần permission user:assign-role. */
        Set<@NotBlank String> roles) {
    }

    record UpdateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @NotNull Boolean enabled,
        @Pattern(regexp = PHONE) String phoneNumber,
        @Size(max = 500) String avatarUrl,
        @Pattern(regexp = LANGUAGE) String languageKey) {
    }

    record ResetPasswordRequest(
        @NotBlank @Size(min = 8, max = 128) String password,
        /** null → true. */
        Boolean temporary) {
    }

    record AssignRolesRequest(@NotEmpty Set<@NotBlank String> roles) {
    }
}
