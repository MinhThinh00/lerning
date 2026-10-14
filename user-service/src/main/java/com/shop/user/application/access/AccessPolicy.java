package com.shop.user.application.access;

import com.shop.common.web.error.BusinessException;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Luật phân quyền mà @PreAuthorize KHÔNG diễn đạt được, vì phụ thuộc vào user ĐÍCH chứ không chỉ người gọi.
 *
 * @PreAuthorize trả lời: "anh có được reset password không?"
 * AccessPolicy trả lời: "anh có được reset password CỦA NGƯỜI NÀY không?"
 * Thiếu lớp này → support (có user:reset-password) reset được password của admin rồi đăng nhập
 * bằng tài khoản admin = leo thang đặc quyền.
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /** Chỉ admin mới được tác động lên tài khoản admin. */
    public static void checkCanManage(Caller caller, Set<String> targetRoles) {
        if (targetRoles.contains(Roles.ADMIN) && !caller.isAdmin()) {
            throw forbidden("Only an admin can manage an admin account");
        }
    }

    /** Không tự xóa / tự khóa / tự đổi role của mình (tránh tự khóa mình khỏi hệ thống). */
    public static void checkNotSelf(Caller caller, String targetId, String action) {
        if (caller.id().equals(targetId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "SELF_MODIFICATION", "You cannot " + action + " your own account");
        }
    }

    /**
     * Gán role: role phải nằm trong danh sách cho phép, và chỉ admin mới trao được ROLE_ADMIN.
     * Gán role khác mặc định cần permission user:assign-role (kể cả lúc tạo user).
     */
    public static void checkCanAssign(Caller caller, Set<String> roles) {
        for (String role : roles) {
            if (!Roles.ASSIGNABLE.contains(role)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role " + role + " cannot be assigned");
            }
        }
        if (!roles.equals(Roles.DEFAULT) && !caller.has(Permissions.USER_ASSIGN_ROLE)) {
            throw forbidden("Missing permission " + Permissions.USER_ASSIGN_ROLE);
        }
        if (roles.contains(Roles.ADMIN) && !caller.isAdmin()) {
            throw forbidden("Only an admin can grant " + Roles.ADMIN);
        }
    }

    private static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }
}
