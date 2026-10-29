package com.shop.notification.application.port;

import java.util.Optional;

/**
 * Port tra thông tin liên hệ của user ở user-service. Gọi bằng danh tính của CHÍNH notification-service
 * (token client_credentials), vì chạy trong Kafka consumer, không có user nào đăng nhập (docs/10 T8).
 */
public interface UserDirectory {

    /**
     * @return empty khi user không tồn tại (lỗi vĩnh viễn, thử lại cũng vô ích)
     * @throws UserDirectoryUnavailableException user-service / Keycloak không trả lời được (lỗi tạm thời)
     */
    Optional<Contact> findContact(String userId);

    record Contact(String email, String firstName, String languageKey) {
    }

    class UserDirectoryUnavailableException extends RuntimeException {

        public UserDirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
