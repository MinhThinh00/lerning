package com.shop.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param userServiceUrl gốc URL user-service (IDE: localhost:8081, Docker: http://user-service:8081)
 * @param mailFrom       địa chỉ người gửi
 * @param maxAttempts    số lần thử gửi tối đa, quá thì FAILED
 * @param retryAfter     lỗi tạm thời → chờ ít nhất bấy nhiêu rồi retry job mới thử lại
 * @param retryInterval  chu kỳ retry job
 * @param claimTimeout   SENDING quá lâu (worker chết giữa chừng) → coi như bỏ dở, cho nhận lại
 */
@ConfigurationProperties("notification")
public record NotificationProperties(
    @DefaultValue("http://localhost:8081") String userServiceUrl,
    @DefaultValue("Shop <no-reply@shop.local>") String mailFrom,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("30s") Duration retryAfter,
    @DefaultValue("30s") Duration retryInterval,
    @DefaultValue("5m") Duration claimTimeout) {
}
