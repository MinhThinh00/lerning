package com.shop.user.application.service;

import com.shop.common.web.PageResponse;
import com.shop.common.web.error.NotFoundException;
import com.shop.user.application.access.AccessPolicy;
import com.shop.user.application.access.Caller;
import com.shop.user.application.access.Roles;
import com.shop.user.application.dto.UserDtos.CreateUserCommand;
import com.shop.user.application.dto.UserDtos.UpdateUserCommand;
import com.shop.user.application.dto.UserDtos.UserDetail;
import com.shop.user.application.dto.UserDtos.UserSummary;
import com.shop.user.application.port.IdentityProvider;
import com.shop.user.application.port.IdentityProvider.IdentityChanges;
import com.shop.user.application.port.IdentityProvider.IdentityPage;
import com.shop.user.application.port.IdentityProvider.IdentityUser;
import com.shop.user.application.port.IdentityProvider.NewIdentityUser;
import com.shop.user.domain.model.UserProfile;
import com.shop.user.domain.model.UserProfile.Identity;
import com.shop.user.domain.repository.UserProfileRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Quản trị user. Dữ liệu nằm ở 2 nơi (Keycloak + db_user) và KHÔNG có transaction chung,
 * nên mỗi thao tác ghi đi theo thứ tự: Keycloak trước (nguồn sự thật), DB sau; lỗi giữa chừng thì bù trừ.
 *
 * Phân quyền 2 lớp:
 *  1. @PreAuthorize ở controller: người gọi có permission cho LOẠI thao tác này không.
 *  2. AccessPolicy ở đây: người gọi có được làm thao tác đó lên USER ĐÍCH này không.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final IdentityProvider identity;
    private final UserProfileRepository profiles;

    public UserAdminService(IdentityProvider identity, UserProfileRepository profiles) {
        this.identity = identity;
        this.profiles = profiles;
    }

    /**
     * Tìm trên Keycloak (nguồn sự thật, có cả user chưa từng đăng nhập),
     * rồi ghép thêm hồ sơ mở rộng từ db_user bằng 1 query.
     */
    public PageResponse<UserSummary> search(String keyword, int page, int size) {
        IdentityPage result = identity.search(keyword, page, size);
        List<String> ids = result.users().stream().map(IdentityUser::id).toList();
        Map<String, UserProfile> profileById = profiles.findByIdIn(ids).stream()
            .collect(Collectors.toMap(UserProfile::getId, Function.identity()));

        List<UserSummary> content = result.users().stream()
            .map(u -> toSummary(u, profileById.get(u.id())))
            .toList();
        int totalPages = (int) ((result.total() + size - 1) / size);
        return new PageResponse<>(content, page, size, result.total(), totalPages);
    }

    public UserDetail get(String id) {
        IdentityUser user = requireUser(id);
        return toDetail(user, identity.getRealmRoles(id), profiles.findById(id).orElse(null));
    }

    public UserDetail create(Caller caller, CreateUserCommand cmd) {
        Set<String> roles = cmd.roles() == null || cmd.roles().isEmpty() ? Roles.DEFAULT : Set.copyOf(cmd.roles());
        AccessPolicy.checkCanAssign(caller, roles);

        String id = identity.create(new NewIdentityUser(cmd.username(), cmd.email(), cmd.firstName(),
            cmd.lastName(), cmd.password(), cmd.temporaryPassword()));
        try {
            identity.addRealmRoles(id, roles);
            UserProfile profile = UserProfile.create(id, identityOf(requireUser(id)));
            profile.updateContact(cmd.phoneNumber(), null, null);
            profiles.save(profile);
        } catch (RuntimeException e) {
            compensateCreate(id, e);
            throw e;
        }
        log.info("User {} created by {} with roles {}", id, caller.id(), roles);
        return get(id);
    }

    public UserDetail update(Caller caller, String id, UpdateUserCommand cmd) {
        IdentityUser user = requireUser(id);
        AccessPolicy.checkCanManage(caller, identity.getRealmRoles(id));
        if (!cmd.enabled()) {
            AccessPolicy.checkNotSelf(caller, id, "disable");
        }

        identity.update(id, new IdentityChanges(cmd.email(), cmd.firstName(), cmd.lastName(), cmd.enabled()));
        if (user.enabled() && !cmd.enabled()) {
            identity.logout(id);   // khóa tài khoản thì đá luôn các session đang mở
        }

        Identity updated = identityOf(requireUser(id));
        UserProfile profile = profiles.findById(id).orElseGet(() -> UserProfile.create(id, updated));
        profile.syncIdentity(updated);
        profile.updateContact(cmd.phoneNumber(), cmd.avatarUrl(), cmd.languageKey());
        profiles.save(profile);
        return get(id);
    }

    public void delete(Caller caller, String id) {
        requireUser(id);
        AccessPolicy.checkNotSelf(caller, id, "delete");
        AccessPolicy.checkCanManage(caller, identity.getRealmRoles(id));

        identity.delete(id);
        profiles.deleteById(id);
        log.info("User {} deleted by {}", id, caller.id());
    }

    public void resetPassword(Caller caller, String id, String password, boolean temporary) {
        requireUser(id);
        AccessPolicy.checkCanManage(caller, identity.getRealmRoles(id));

        identity.resetPassword(id, password, temporary);
        identity.logout(id);   // ai đang giữ session bằng password cũ đều bị đăng xuất
        log.info("Password of user {} reset by {}", id, caller.id());
    }

    /** Đặt lại TOÀN BỘ role (trong phạm vi Roles.ASSIGNABLE) của user thành đúng tập roles. */
    public UserDetail assignRoles(Caller caller, String id, Set<String> roles) {
        requireUser(id);
        AccessPolicy.checkNotSelf(caller, id, "change roles of");
        Set<String> current = identity.getRealmRoles(id);
        AccessPolicy.checkCanManage(caller, current);
        AccessPolicy.checkCanAssign(caller, roles);

        Set<String> currentAssignable = new HashSet<>(current);
        currentAssignable.retainAll(Roles.ASSIGNABLE);
        Set<String> toAdd = new HashSet<>(roles);
        toAdd.removeAll(currentAssignable);
        Set<String> toRemove = new HashSet<>(currentAssignable);
        toRemove.removeAll(roles);

        if (!toAdd.isEmpty()) {
            identity.addRealmRoles(id, toAdd);
        }
        if (!toRemove.isEmpty()) {
            identity.removeRealmRoles(id, toRemove);
            // Access token cũ vẫn mang role cũ tới khi hết hạn (15 phút); logout chặn việc refresh lấy token mới
            identity.logout(id);
        }
        log.info("Roles of user {} set to {} by {} (+{} -{})", id, roles, caller.id(), toAdd, toRemove);
        return get(id);
    }

    private IdentityUser requireUser(String id) {
        return identity.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    /** Bù trừ: bước sau lỗi → xóa user vừa tạo trên Keycloak để không để lại user "mồ côi". */
    private void compensateCreate(String id, RuntimeException cause) {
        log.warn("Creating user {} failed, deleting it from identity provider", id, cause);
        try {
            identity.delete(id);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("Compensation failed: user {} left in identity provider without profile", id, e);
        }
    }

    private static Identity identityOf(IdentityUser u) {
        return new Identity(u.username(), u.email(), u.firstName(), u.lastName());
    }

    private static UserSummary toSummary(IdentityUser u, UserProfile p) {
        return new UserSummary(u.id(), u.username(), u.email(), u.firstName(), u.lastName(), u.enabled(),
            p != null ? p.getPhoneNumber() : null);
    }

    private static UserDetail toDetail(IdentityUser u, Set<String> roles, UserProfile p) {
        Set<String> visibleRoles = roles.stream().filter(Roles.ASSIGNABLE::contains).collect(Collectors.toSet());
        return new UserDetail(u.id(), u.username(), u.email(), u.firstName(), u.lastName(), u.enabled(),
            u.emailVerified(), u.createdAt(), visibleRoles,
            p != null ? p.getPhoneNumber() : null,
            p != null ? p.getAvatarUrl() : null,
            p != null ? p.getLanguageKey() : UserProfile.DEFAULT_LANGUAGE);
    }
}
