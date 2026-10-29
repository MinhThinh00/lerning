package com.shop.payment.application.callback;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chữ ký callback: hex(HMAC-SHA256(secret, raw body)), gửi ở header {@value #HEADER}.
 *
 * - Ký trên BYTE THÔ của body, không ký trên object đã parse rồi serialize lại (thứ tự field, khoảng trắng
 *   khác đi là lệch chữ ký).
 * - So sánh bằng {@link MessageDigest#isEqual} (thời gian không phụ thuộc vị trí byte sai đầu tiên).
 *   {@code String.equals} dừng ngay ở byte khác đầu tiên → đo thời gian phản hồi là đoán dần được chữ ký
 *   (timing attack, bug của SX trong docs/10 T7).
 */
public final class CallbackSignature {

    public static final String HEADER = "X-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public CallbackSignature(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("HMAC secret must not be blank");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(byte[] body) {
        return HexFormat.of().formatHex(mac(body));
    }

    public boolean verify(byte[] body, String signatureHex) {
        if (signatureHex == null) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHex.trim().toLowerCase());
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(mac(body), given);
    }

    private byte[] mac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);   // Mac không thread-safe: tạo mới mỗi lần (rẻ)
            mac.init(key);
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
