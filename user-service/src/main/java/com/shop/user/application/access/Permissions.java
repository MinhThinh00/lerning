package com.shop.user.application.access;

/**
 * Permission = client role của client shop-service trong Keycloak.
 * User KHÔNG được gán permission trực tiếp: user → realm role (ROLE_*) → permission
 * (composite role, xem deploy/infra/keycloak/realm/shop-realm.json).
 * Code chỉ kiểm tra permission, nên đổi "role nào có quyền gì" không cần sửa code.
 */
public final class Permissions {

    public static final String USER_READ = "user:read";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_RESET_PASSWORD = "user:reset-password";
    public static final String USER_ASSIGN_ROLE = "user:assign-role";

    private Permissions() {
    }
}
