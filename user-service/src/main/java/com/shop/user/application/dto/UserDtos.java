package com.shop.user.application.dto;

import java.time.Instant;
import java.util.Set;

/** Input/output của use case (không phải DTO HTTP; request DTO có validation nằm ở tầng web). */
public final class UserDtos {

    private UserDtos() {
    }

    /** Thông tin người dùng hiện tại lấy từ token. */
    public record CurrentUser(String id, String username, String email, String firstName, String lastName) {
    }

    public record AccountView(String id, String username, String email, String firstName, String lastName,
                              String phoneNumber, String avatarUrl, String languageKey) {
    }

    public record UpdateAccountCommand(String phoneNumber, String avatarUrl, String languageKey) {
    }

    public record UserSummary(String id, String username, String email, String firstName, String lastName,
                              boolean enabled, String phoneNumber) {
    }

    public record UserDetail(String id, String username, String email, String firstName, String lastName,
                             boolean enabled, boolean emailVerified, Instant createdAt, Set<String> roles,
                             String phoneNumber, String avatarUrl, String languageKey) {
    }

    public record CreateUserCommand(String username, String email, String firstName, String lastName,
                                    String phoneNumber, String password, boolean temporaryPassword,
                                    Set<String> roles) {
    }

    public record UpdateUserCommand(String email, String firstName, String lastName, boolean enabled,
                                    String phoneNumber, String avatarUrl, String languageKey) {
    }

    /** Thông tin liên hệ cho service khác (notification gửi email): chỉ field cần thiết, không lộ role/trạng thái. */
    public record UserContact(String id, String email, String firstName, String languageKey) {
    }
}
