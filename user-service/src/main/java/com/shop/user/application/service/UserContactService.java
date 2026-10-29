package com.shop.user.application.service;

import com.shop.common.web.error.NotFoundException;
import com.shop.user.application.dto.UserDtos.UserContact;
import com.shop.user.application.port.IdentityProvider;
import com.shop.user.application.port.IdentityProvider.IdentityUser;
import com.shop.user.domain.model.UserProfile;
import com.shop.user.domain.repository.UserProfileRepository;
import org.springframework.stereotype.Service;

/**
 * Tra thông tin liên hệ cho service khác gọi bằng token MÁY (docs/10 T8): ví dụ notification-service cần email
 * của khách khi xử lý order.paid trong Kafka consumer, nơi không có user nào đăng nhập.
 *
 * Email lấy từ Keycloak (nguồn sự thật, kể cả user chưa từng đăng nhập vào shop nên chưa có profile);
 * ngôn ngữ lấy từ profile ở db_user nếu có.
 */
@Service
public class UserContactService {

    private final IdentityProvider identity;
    private final UserProfileRepository profiles;

    public UserContactService(IdentityProvider identity, UserProfileRepository profiles) {
        this.identity = identity;
        this.profiles = profiles;
    }

    public UserContact contact(String id) {
        IdentityUser user = identity.findById(id).orElseThrow(() -> new NotFoundException("User", id));
        String language = profiles.findById(id).map(UserProfile::getLanguageKey).orElse(UserProfile.DEFAULT_LANGUAGE);
        return new UserContact(user.id(), user.email(), user.firstName(), language);
    }
}
