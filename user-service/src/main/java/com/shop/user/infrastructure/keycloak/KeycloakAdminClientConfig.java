package com.shop.user.infrastructure.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * RestClient gọi Keycloak Admin API bằng token của service account shop-service.
 *
 * Luồng: request đi qua OAuth2ClientHttpRequestInterceptor → interceptor hỏi AuthorizedClientManager
 * → chưa có token / token hết hạn thì gọi token endpoint (grant client_credentials) → gắn
 * "Authorization: Bearer ..." vào request. Token được cache, không xin lại mỗi request.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakProperties.class)
class KeycloakAdminClientConfig {

    static final String REGISTRATION_ID = "keycloak-admin";

    /**
     * Token của service account không thuộc về user đang gọi API. Mặc định interceptor lấy principal
     * từ SecurityContext → mỗi user sẽ có 1 token riêng trong cache. Cố định 1 principal → dùng chung 1 token.
     */
    private static final Authentication SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
        "user-service", "user-service", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    /** Manager không gắn với HTTP request/session — đúng cho client_credentials. */
    @Bean
    OAuth2AuthorizedClientManager keycloakAdminClientManager(ClientRegistrationRepository registrations,
                                                            OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient keycloakAdminRestClient(OAuth2AuthorizedClientManager keycloakAdminClientManager, KeycloakProperties props) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(keycloakAdminClientManager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        interceptor.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        return RestClient.builder()
            .baseUrl(props.adminBaseUrl())
            .requestInterceptor(interceptor)
            .build();
    }
}
