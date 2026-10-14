package com.shop.user.infrastructure.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** keycloak.url / keycloak.realm / keycloak.app-client-id trong application.yml. */
@ConfigurationProperties("keycloak")
public record KeycloakProperties(String url, String realm, @DefaultValue("shop-app") String appClientId) {

    public String adminBaseUrl() {
        return url + "/admin/realms/" + realm;
    }

    /** openid-connect/token và /logout: client PUBLIC shop-app, directAccessGrantsEnabled=true. */
    public String openidConnectBaseUrl() {
        return url + "/realms/" + realm + "/protocol/openid-connect";
    }
}
