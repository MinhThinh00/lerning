package com.shop.common.messaging.inbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention giữ processed_event bao lâu. Phải dài hơn mọi khả năng Kafka giao lại
 *                  (retention của topic, replay thủ công từ DLT...), nếu không event cũ sẽ bị xử lý lại.
 */
@ConfigurationProperties("shop.inbox")
public record InboxProperties(@DefaultValue("14d") Duration retention) {
}
