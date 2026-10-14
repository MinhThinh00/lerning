package com.shop.user.application.port;

/**
 * Port xin/đổi access token từ Keycloak thay mặt app (client public shop-app, direct access grant).
 * Application không biết gì về grant_type/form-urlencoded; chuyện đó nằm ở adapter infrastructure.
 */
public interface TokenProvider {

    /** grant_type=password: đổi username/password lấy token. Sai mật khẩu → BusinessException 401. */
    TokenSet passwordLogin(String username, String password);

    /** grant_type=refresh_token: xin access token mới khi token cũ hết hạn, không bắt đăng nhập lại. */
    TokenSet refresh(String refreshToken);

    /** Thu hồi refresh token (đăng xuất). Refresh token đã hết hạn/bị thu hồi rồi thì coi như xong, không lỗi. */
    void logout(String refreshToken);

    record TokenSet(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn, String tokenType) {
    }
}
