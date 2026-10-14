package com.shop.user.web.rest;

import com.shop.common.web.PageResponse;
import com.shop.user.application.dto.UserDtos.CreateUserCommand;
import com.shop.user.application.dto.UserDtos.UpdateUserCommand;
import com.shop.user.application.dto.UserDtos.UserDetail;
import com.shop.user.application.dto.UserDtos.UserSummary;
import com.shop.user.application.service.UserAdminService;
import com.shop.user.web.rest.UserRequests.AssignRolesRequest;
import com.shop.user.web.rest.UserRequests.CreateUserRequest;
import com.shop.user.web.rest.UserRequests.ResetPasswordRequest;
import com.shop.user.web.rest.UserRequests.UpdateUserRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị user. Mỗi endpoint yêu cầu đúng 1 permission (RBAC):
 *
 * | Endpoint                                   | Permission           | ADMIN | SUPPORT |
 * |--------------------------------------------|----------------------|:-----:|:-------:|
 * | GET    /api/admin/users?keyword=           | user:read            |   x   |    x    |
 * | GET    /api/admin/users/{id}               | user:read            |   x   |    x    |
 * | POST   /api/admin/users                    | user:create          |   x   |         |
 * | PUT    /api/admin/users/{id}               | user:update          |   x   |         |
 * | DELETE /api/admin/users/{id}               | user:delete          |   x   |         |
 * | POST   /api/admin/users/{id}/reset-password| user:reset-password  |   x   |    x    |
 * | PUT    /api/admin/users/{id}/roles         | user:assign-role     |   x   |         |
 *
 * Luật theo user đích (support không đụng được admin, không tự xóa mình...) nằm ở AccessPolicy.
 */
@RestController
@RequestMapping("/api/admin/users")
class UserAdminController {

    private final UserAdminService users;

    UserAdminController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    PageResponse<UserSummary> search(@RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.search(keyword, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    UserDetail get(@PathVariable String id) {
        return users.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    ResponseEntity<UserDetail> create(JwtAuthenticationToken auth, @Valid @RequestBody CreateUserRequest req) {
        UserDetail created = users.create(JwtPrincipals.caller(auth), new CreateUserCommand(
            req.username(), req.email(), req.firstName(), req.lastName(), req.phoneNumber(),
            req.password(), req.temporaryPassword() == null || req.temporaryPassword(), req.roles()));
        return ResponseEntity.created(URI.create("/api/admin/users/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    UserDetail update(JwtAuthenticationToken auth, @PathVariable String id, @Valid @RequestBody UpdateUserRequest req) {
        return users.update(JwtPrincipals.caller(auth), id, new UpdateUserCommand(
            req.email(), req.firstName(), req.lastName(), req.enabled(),
            req.phoneNumber(), req.avatarUrl(), req.languageKey()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable String id) {
        users.delete(JwtPrincipals.caller(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:reset-password')")
    ResponseEntity<Void> resetPassword(JwtAuthenticationToken auth, @PathVariable String id,
                                       @Valid @RequestBody ResetPasswordRequest req) {
        users.resetPassword(JwtPrincipals.caller(auth), id, req.password(), req.temporary() == null || req.temporary());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:assign-role')")
    UserDetail assignRoles(JwtAuthenticationToken auth, @PathVariable String id, @Valid @RequestBody AssignRolesRequest req) {
        return users.assignRoles(JwtPrincipals.caller(auth), id, req.roles());
    }
}
