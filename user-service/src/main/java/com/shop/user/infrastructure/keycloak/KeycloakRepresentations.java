package com.shop.user.infrastructure.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON của Keycloak Admin REST API (chỉ các field dùng tới).
 * Tài liệu: https://www.keycloak.org/docs-api/latest/rest-api/
 */
final class KeycloakRepresentations {

    private KeycloakRepresentations() {
    }

    /** UserRepresentation. Field null không gửi đi → khi PUT, Keycloak giữ nguyên giá trị cũ. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KcUser(String id, String username, String email, String firstName, String lastName,
                  Boolean enabled, Boolean emailVerified, Long createdTimestamp,
                  List<KcCredential> credentials) {
    }

    /** CredentialRepresentation. temporary=true → user phải đổi mật khẩu ở lần đăng nhập kế tiếp. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KcCredential(String type, String value, Boolean temporary) {

        static KcCredential password(String value, boolean temporary) {
            return new KcCredential("password", value, temporary);
        }
    }

    /** RoleRepresentation. Gán role cần cả id lẫn name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KcRole(String id, String name) {
    }
}
