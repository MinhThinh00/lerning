package com.shop.notification.infrastructure.user;

import com.shop.notification.application.port.UserDirectory;
import com.shop.notification.config.NotificationProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * GET user-service /api/internal/users/{sub}/contact bằng token MÁY (docs/10 T8):
 * OAuth2ClientHttpRequestInterceptor xin token client_credentials của service account shop-service
 * (realm role ROLE_SERVICE), cache lại tới khi hết hạn, gắn "Authorization: Bearer ..." vào mỗi request.
 *
 * Không chuyển tiếp token của user: consumer Kafka không có user nào. Dùng
 * AuthorizedClientServiceOAuth2AuthorizedClientManager (không gắn với HTTP request/session) và 1 principal cố
 * định → mọi lời gọi dùng chung 1 token.
 *
 * Phân loại lỗi cho dispatcher: 404 → empty (vĩnh viễn); còn lại (timeout, 5xx, 401/403 do cấu hình sai,
 * Keycloak chết) → UserDirectoryUnavailableException (tạm thời, thử lại sau).
 */
@Component
class RestUserDirectory implements UserDirectory {

    static final String REGISTRATION_ID = "shop-service";

    private static final Authentication SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
        "notification-service", "notification-service", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private final RestClient http;

    RestUserDirectory(NotificationProperties props, ClientRegistrationRepository registrations,
                      OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        OAuth2ClientHttpRequestInterceptor oauth = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        oauth.setPrincipalResolver(request -> SERVICE_PRINCIPAL);

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder()
            .baseUrl(props.userServiceUrl())
            .requestFactory(factory)
            .requestInterceptor(oauth)
            .build();
    }

    record ContactResponse(String id, String email, String firstName, String languageKey) {
    }

    @Override
    public Optional<Contact> findContact(String userId) {
        try {
            ContactResponse r = http.get().uri("/api/internal/users/{id}/contact", userId)
                .retrieve().body(ContactResponse.class);
            return Optional.ofNullable(r).map(c -> new Contact(c.email(), c.firstName(), c.languageKey()));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new UserDirectoryUnavailableException("user-service returned " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            // Gồm cả lỗi xin token (Keycloak chết / sai secret) do interceptor ném ra
            throw new UserDirectoryUnavailableException("user-service call failed: " + e.getMessage(), e);
        }
    }
}
