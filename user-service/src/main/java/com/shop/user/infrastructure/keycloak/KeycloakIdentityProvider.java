package com.shop.user.infrastructure.keycloak;

import com.shop.common.web.error.BusinessException;
import com.shop.common.web.error.NotFoundException;
import com.shop.user.application.port.IdentityProvider;
import com.shop.user.infrastructure.keycloak.KeycloakRepresentations.KcCredential;
import com.shop.user.infrastructure.keycloak.KeycloakRepresentations.KcRole;
import com.shop.user.infrastructure.keycloak.KeycloakRepresentations.KcUser;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Adapter: implement port IdentityProvider bằng Keycloak Admin REST API.
 * Service account shop-service cần client role realm-management: view-users, query-users,
 * manage-users (đọc/ghi user) và view-realm (đọc role để gán).
 */
@Component
class KeycloakIdentityProvider implements IdentityProvider {

    private static final ParameterizedTypeReference<List<KcUser>> USER_LIST = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KcRole>> ROLE_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient rest;

    KeycloakIdentityProvider(RestClient keycloakAdminRestClient) {
        this.rest = keycloakAdminRestClient;
    }

    @Override
    public IdentityPage search(String keyword, int page, int size) {
        Optional<String> search = Optional.ofNullable(keyword).filter(StringUtils::hasText).map(String::trim);
        // GET /users?search=&first=&max= : first = offset, max = limit
        List<KcUser> users = rest.get()
            .uri(b -> b.path("/users")
                .queryParamIfPresent("search", search)
                .queryParam("first", page * size)
                .queryParam("max", size)
                .queryParam("briefRepresentation", true)
                .build())
            .retrieve()
            .body(USER_LIST);
        Long total = rest.get()
            .uri(b -> b.path("/users/count").queryParamIfPresent("search", search).build())
            .retrieve()
            .body(Long.class);
        return new IdentityPage(users == null ? List.of() : users.stream().map(KeycloakIdentityProvider::toUser).toList(),
            total == null ? 0 : total);
    }

    @Override
    public Optional<IdentityUser> findById(String id) {
        try {
            return Optional.ofNullable(rest.get().uri("/users/{id}", id).retrieve().body(KcUser.class))
                .map(KeycloakIdentityProvider::toUser);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public String create(NewIdentityUser user) {
        KcUser body = new KcUser(null, user.username(), user.email(), user.firstName(), user.lastName(),
            true, false, null, List.of(KcCredential.password(user.password(), user.temporaryPassword())));
        // 201 Created, id của user nằm ở cuối header Location: .../users/{id}
        URI location = call(null, () -> rest.post().uri("/users").body(body).retrieve().toBodilessEntity())
            .getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return Location header for created user");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void update(String id, IdentityChanges changes) {
        KcUser body = new KcUser(null, null, changes.email(), changes.firstName(), changes.lastName(),
            changes.enabled(), null, null, null);
        call(id, () -> rest.put().uri("/users/{id}", id).body(body).retrieve().toBodilessEntity());
    }

    @Override
    public void delete(String id) {
        call(id, () -> rest.delete().uri("/users/{id}", id).retrieve().toBodilessEntity());
    }

    @Override
    public void resetPassword(String id, String password, boolean temporary) {
        call(id, () -> rest.put().uri("/users/{id}/reset-password", id)
            .body(KcCredential.password(password, temporary))
            .retrieve().toBodilessEntity());
    }

    @Override
    public void logout(String id) {
        call(id, () -> rest.post().uri("/users/{id}/logout", id).retrieve().toBodilessEntity());
    }

    @Override
    public Set<String> getRealmRoles(String id) {
        List<KcRole> roles = call(id, () -> rest.get().uri("/users/{id}/role-mappings/realm", id).retrieve().body(ROLE_LIST));
        return roles == null ? Set.of() : roles.stream().map(KcRole::name).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void addRealmRoles(String id, Set<String> roles) {
        List<KcRole> body = resolveRoles(roles);
        call(id, () -> rest.post().uri("/users/{id}/role-mappings/realm", id).body(body).retrieve().toBodilessEntity());
    }

    @Override
    public void removeRealmRoles(String id, Set<String> roles) {
        List<KcRole> body = resolveRoles(roles);
        // DELETE có body: RestClient.delete() không cho body nên dùng method(DELETE)
        call(id, () -> rest.method(HttpMethod.DELETE).uri("/users/{id}/role-mappings/realm", id)
            .body(body).retrieve().toBodilessEntity());
    }

    /** API gán role cần RoleRepresentation đầy đủ (id + name), không nhận mỗi tên. */
    private List<KcRole> resolveRoles(Set<String> names) {
        return names.stream()
            .map(name -> {
                try {
                    return rest.get().uri("/roles/{name}", name).retrieve().body(KcRole.class);
                } catch (HttpClientErrorException.NotFound e) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Role " + name + " does not exist");
                }
            })
            .toList();
    }

    /** Dịch lỗi HTTP của Keycloak sang lỗi nghiệp vụ; lỗi khác (5xx, mất kết nối) để nguyên → 500. */
    private static <T> T call(String userId, Supplier<T> action) {
        try {
            return action.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("User", userId);
        } catch (HttpClientErrorException.Conflict e) {
            throw new BusinessException(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", errorMessage(e, "User already exists"));
        } catch (HttpClientErrorException.BadRequest e) {
            // vd mật khẩu không đạt password policy của realm
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDENTITY_REJECTED", errorMessage(e, "Rejected by identity provider"));
        }
    }

    /** Keycloak trả lỗi dạng {"errorMessage": "..."} hoặc {"error": "..."}. */
    private static String errorMessage(HttpClientErrorException e, String fallback) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body != null) {
                Object msg = body.get("errorMessage") != null ? body.get("errorMessage") : body.get("error");
                if (msg != null) {
                    return msg.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // body không phải JSON
        }
        return fallback;
    }

    private static IdentityUser toUser(KcUser u) {
        return new IdentityUser(u.id(), u.username(), u.email(), u.firstName(), u.lastName(),
            Boolean.TRUE.equals(u.enabled()), Boolean.TRUE.equals(u.emailVerified()),
            u.createdTimestamp() == null ? null : Instant.ofEpochMilli(u.createdTimestamp()));
    }
}
