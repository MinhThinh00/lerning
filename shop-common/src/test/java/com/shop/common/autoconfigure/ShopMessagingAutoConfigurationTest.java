package com.shop.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.common.messaging.outbox.OutboxRelay;
import com.shop.common.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Messaging chỉ bật khi có cả KafkaTemplate lẫn DataSource → service không dùng Kafka (user-service) không bị ảnh hưởng. */
class ShopMessagingAutoConfigurationTest {

    @Test
    void staysOffWithoutKafkaAndDataSource() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ShopMessagingAutoConfiguration.class))
            .run(ctx -> assertThat(ctx).hasNotFailed()
                .doesNotHaveBean(OutboxWriter.class)
                .doesNotHaveBean(OutboxRelay.class));
    }
}
