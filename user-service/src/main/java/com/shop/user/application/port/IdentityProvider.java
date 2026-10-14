package com.shop.user.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Port ra hệ thống quản lý danh tính (Keycloak). Application chỉ biết interface này;
 * đổi Keycloak sang IdP khác chỉ phải viết lại adapter ở infrastructure.
 */
public interface IdentityProvider {

    /** Tìm theo username / email / họ tên (chứa chuỗi). page bắt đầu từ 0. */
    IdentityPage search(String keyword, int page, int size);

    Optional<IdentityUser> findById(String id);

    /** @return id (sub) của user vừa tạo */
    String create(NewIdentityUser user);

    void update(String id, IdentityChanges changes);

    void delete(String id);

    void resetPassword(String id, String password, boolean temporary);

    /** Đăng xuất mọi session (refresh token cũ hết hiệu lực). */
    void logout(String id);

    /** Realm role gán TRỰC TIẾP cho user. */
    Set<String> getRealmRoles(String id);

    void addRealmRoles(String id, Set<String> roles);

    void removeRealmRoles(String id, Set<String> roles);

    record IdentityUser(String id, String username, String email, String firstName, String lastName,
                        boolean enabled, boolean emailVerified, Instant createdAt) {
    }

    record IdentityPage(List<IdentityUser> users, long total) {
    }

    record NewIdentityUser(String username, String email, String firstName, String lastName,
                           String password, boolean temporaryPassword) {
    }

    record IdentityChanges(String email, String firstName, String lastName, boolean enabled) {
    }
}
