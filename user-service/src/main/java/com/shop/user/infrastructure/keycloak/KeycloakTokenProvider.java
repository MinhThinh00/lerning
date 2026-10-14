package com.shop.user.infrastructure.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shop.common.web.error.BusinessException;
import com.shop.user.application.port.TokenProvider;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Adapter: implement TokenProvider bằng endpoint OIDC chuẩn của Keycloak (không phải Admin API).
 * Dùng client PUBLIC "shop-app" (không secret) → app tự gọi được, không lộ secret của service account.
 * Request KHÔNG đi qua OAuth2ClientHttpRequestInterceptor như KeycloakAdminClientConfig, vì ở đây
 * chính request này LÀ hành động xin token, không phải request cần đính kèm token có sẵn.
 */
@Component
class KeycloakTokenProvider implements TokenProvider {

    private final RestClient rest;
    private final String clientId;

    KeycloakTokenProvider(KeycloakProperties props) {
        this.clientId = props.appClientId();
        this.rest = RestClient.builder().baseUrl(props.openidConnectBaseUrl()).build();
    }

    @Override
    public TokenSet passwordLogin(String username, String password) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        return requestToken(form);
    }

    @Override
    public TokenSet refresh(String refreshToken) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form);
    }

    @Override
    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = baseForm();
        form.add("refresh_token", refreshToken);
        try {
            rest.post().uri("/logout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.BadRequest e) {
            // refresh token không hợp lệ/đã hết hạn -> coi như đã đăng xuất, không cần báo lỗi cho client
        }
    }

    private TokenSet requestToken(MultiValueMap<String, String> form) {
        KcTokenResponse r = call(() -> rest.post().uri("/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(KcTokenResponse.class));
        return new TokenSet(r.accessToken(), r.refreshToken(), r.expiresIn(), r.refreshExpiresIn(), r.tokenType());
    }

    private MultiValueMap<String, String> baseForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        return form;
    }

    /** Keycloak trả lỗi dạng 400 {"error":"invalid_grant","error_description":"..."} cho mọi lỗi liên quan tới credential. */
    private static KcTokenResponse call(Supplier<KcTokenResponse> action) {
        try {
            return action.get();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", errorMessage(e));
        }
    }

    private static String errorMessage(HttpClientErrorException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            Object detail = body != null ? body.get("error_description") : null;
            return detail != null ? detail.toString() : "Invalid username or password";
        } catch (RuntimeException ignored) {
            return "Invalid username or password";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KcTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType) {
    }
}
