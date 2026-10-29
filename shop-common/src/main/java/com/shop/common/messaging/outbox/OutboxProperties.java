package com.shop.common.messaging.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình outbox. Chu kỳ poll của relay đọc trực tiếp trong @Scheduled:
 * {@code shop.outbox.poll-interval-ms} (mặc định 500).
 *
 * @param batchSize   số event tối đa mỗi lượt relay
 * @param sendTimeout chờ broker ack tối đa. Đặt LỚN HƠN producer delivery.timeout.ms (30s) để future luôn
 *                    tự kết thúc: nếu relay bỏ chờ sớm, record vẫn có thể được gửi sau lưng → gửi trùng.
 * @param retention   giữ dòng PUBLISHED bao lâu trước khi cleanup xóa
 */
@ConfigurationProperties("shop.outbox")
public record OutboxProperties(
    @DefaultValue("100") int batchSize,
    @DefaultValue("40s") Duration sendTimeout,
    @DefaultValue("3d") Duration retention) {
}
