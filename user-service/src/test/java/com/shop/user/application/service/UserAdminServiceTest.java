package com.shop.user.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shop.common.web.error.BusinessException;
import com.shop.user.application.access.Caller;
import com.shop.user.application.dto.UserDtos.CreateUserCommand;
import com.shop.user.application.port.IdentityProvider;
import com.shop.user.application.port.IdentityProvider.IdentityUser;
import com.shop.user.domain.repository.UserProfileRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Lớp phân quyền thứ hai (AccessPolicy theo user đích) + bù trừ khi tạo user. */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    static final Caller ADMIN = new Caller("admin-id", Set.of("ROLE_ADMIN", "user:read", "user:create",
        "user:update", "user:delete", "user:reset-password", "user:assign-role"));
    static final Caller SUPPORT = new Caller("support-id", Set.of("ROLE_SUPPORT", "user:read", "user:reset-password"));
    /** Có user:create nhưng KHÔNG có user:assign-role (vd một role "HR" tạo tài khoản). */
    static final Caller CREATOR = new Caller("creator-id", Set.of("user:create"));

    @Mock
    IdentityProvider identity;

    @Mock
    UserProfileRepository profiles;

    UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(identity, profiles);
    }

    @Test
    void supportCannotResetPasswordOfAdmin() {
        givenUser("target-admin", "ROLE_ADMIN", "ROLE_USER");

        assertForbidden(() -> service.resetPassword(SUPPORT, "target-admin", "Hacked@123", false));
        verify(identity, never()).resetPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void supportResetsPasswordOfCustomerAndKillsSessions() {
        givenUser("customer", "ROLE_USER");

        service.resetPassword(SUPPORT, "customer", "NewPass@123", true);

        verify(identity).resetPassword("customer", "NewPass@123", true);
        verify(identity).logout("customer");
    }

    @Test
    void creatingWithNonDefaultRoleNeedsAssignRolePermission() {
        assertForbidden(() -> service.create(CREATOR, command(Set.of("ROLE_SELLER"))));
        verify(identity, never()).create(any());
    }

    @Test
    void creatingWithUnknownRoleIsRejected() {
        assertThatThrownBy(() -> service.create(ADMIN, command(Set.of("ROLE_SERVICE"))))
            .isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo("INVALID_ROLE");
    }

    @Test
    void createIsCompensatedWhenProfileCannotBeSaved() {
        given(identity.create(any())).willReturn("new-id");
        given(identity.findById("new-id")).willReturn(Optional.of(user("new-id")));
        given(profiles.save(any())).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.create(ADMIN, command(null))).hasMessage("db down");
        verify(identity).delete("new-id");
    }

    @Test
    void cannotDeleteYourself() {
        given(identity.findById("admin-id")).willReturn(Optional.of(user("admin-id")));

        assertThatThrownBy(() -> service.delete(ADMIN, "admin-id"))
            .isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo("SELF_MODIFICATION");
        verify(identity, never()).delete(anyString());
    }

    @Test
    void onlyAdminCanGrantAdmin() {
        Caller manager = new Caller("manager-id", Set.of("user:assign-role"));
        givenUser("customer", "ROLE_USER");

        assertForbidden(() -> service.assignRoles(manager, "customer", Set.of("ROLE_ADMIN")));
        verify(identity, never()).addRealmRoles(anyString(), any());
    }

    @Test
    void assignRolesAppliesDiffAndRevokesSessionsWhenRemoving() {
        givenUser("u1", "ROLE_USER", "ROLE_SELLER", "default-roles-shop");

        service.assignRoles(ADMIN, "u1", Set.of("ROLE_USER", "ROLE_SUPPORT"));

        verify(identity).addRealmRoles("u1", Set.of("ROLE_SUPPORT"));
        verify(identity).removeRealmRoles("u1", Set.of("ROLE_SELLER"));   // default-roles-shop không bị đụng tới
        verify(identity).logout("u1");
    }

    private void givenUser(String id, String... roles) {
        given(identity.findById(id)).willReturn(Optional.of(user(id)));
        given(identity.getRealmRoles(id)).willReturn(Set.of(roles));
    }

    private static IdentityUser user(String id) {
        return new IdentityUser(id, id, id + "@shop.local", "First", "Last", true, true, Instant.now());
    }

    private static CreateUserCommand command(Set<String> roles) {
        return new CreateUserCommand("jane", "jane@shop.local", "Jane", "Doe", null, "Secret@123", true, roles);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(BusinessException.class)
            .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
