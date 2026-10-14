package com.shop.user.application.service;

import com.shop.user.application.dto.UserDtos.AccountView;
import com.shop.user.application.dto.UserDtos.CurrentUser;
import com.shop.user.application.dto.UserDtos.UpdateAccountCommand;
import com.shop.user.domain.model.UserProfile;
import com.shop.user.domain.model.UserProfile.Identity;
import com.shop.user.domain.repository.UserProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Use case "tài khoản của tôi". User có thể được tạo ở nhiều nơi (trang đăng ký Keycloak, admin,
 * import) nên profile được tạo LƯỜI ở lần gọi đầu tiên (lazy provisioning), không đợi event.
 */
@Service
public class AccountService {

    private final UserProfileRepository profiles;

    public AccountService(UserProfileRepository profiles) {
        this.profiles = profiles;
    }

    public AccountView getCurrent(CurrentUser user) {
        return toView(getOrCreate(user));
    }

    public AccountView updateCurrent(CurrentUser user, UpdateAccountCommand cmd) {
        UserProfile profile = getOrCreate(user);
        profile.updateContact(cmd.phoneNumber(), cmd.avatarUrl(), cmd.languageKey());
        return toView(profiles.save(profile));
    }

    private UserProfile getOrCreate(CurrentUser user) {
        Identity identity = new Identity(user.username(), user.email(), user.firstName(), user.lastName());
        return profiles.findById(user.id())
            .map(p -> p.syncIdentity(identity) ? profiles.save(p) : p)   // đổi email/tên trong Keycloak → cập nhật bản sao
            .orElseGet(() -> createOrReload(user.id(), identity));
    }

    /**
     * 2 request đầu tiên của cùng 1 user có thể đến song song: cả 2 đều không thấy profile và cùng INSERT.
     * Khóa chính chặn bản ghi thứ 2 → bắt lỗi rồi đọc lại bản đã được request kia tạo.
     */
    private UserProfile createOrReload(String id, Identity identity) {
        try {
            return profiles.saveAndFlush(UserProfile.create(id, identity));
        } catch (DataIntegrityViolationException e) {
            return profiles.findById(id).orElseThrow(() -> e);
        }
    }

    private static AccountView toView(UserProfile p) {
        return new AccountView(p.getId(), p.getUsername(), p.getEmail(), p.getFirstName(), p.getLastName(),
            p.getPhoneNumber(), p.getAvatarUrl(), p.getLanguageKey());
    }
}
