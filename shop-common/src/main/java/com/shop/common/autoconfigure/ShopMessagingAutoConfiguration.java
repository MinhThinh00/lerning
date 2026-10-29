package com.shop.common.autoconfigure;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.inbox.InboxCleanupJob;
import com.shop.common.messaging.inbox.InboxGuard;
import com.shop.common.messaging.inbox.InboxProperties;
import com.shop.common.messaging.kafka.KafkaErrorHandling;
import com.shop.common.messaging.outbox.OutboxCleanupJob;
import com.shop.common.messaging.outbox.OutboxMetrics;
import com.shop.common.messaging.outbox.OutboxProperties;
import com.shop.common.messaging.outbox.OutboxRelay;
import com.shop.common.messaging.outbox.OutboxRepository;
import com.shop.common.messaging.outbox.OutboxWriter;
import io.micrometer.core.instrument.binder.MeterBinder;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Messaging: envelope, outbox, inbox, error handler + DLT, ShedLock (docs/05-MESSAGING.md).
 * Chỉ bật khi service có cả Kafka lẫn DataSource. user-service (không Kafka) không bị ảnh hưởng.
 *
 * Service dùng phần này phải include changelog chung vào master changelog của mình:
 * <pre>
 * - include:
 *     file: db/changelog/shop-common/001-messaging.yaml
 * </pre>
 * Không tự thêm @EnableScheduling / @EnableSchedulerLock (đã bật ở đây).
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@ConditionalOnClass({KafkaTemplate.class, JdbcClient.class})
@ConditionalOnBean({KafkaTemplate.class, DataSource.class})
@EnableConfigurationProperties({OutboxProperties.class, InboxProperties.class})
public class ShopMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EnvelopeCodec envelopeCodec(ObjectProvider<ObjectMapper> mapper) {
        return new EnvelopeCodec(mapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }

    /** JdbcClient.create(dataSource) đi qua DataSourceUtils → dùng chung Connection/transaction đang mở (kể cả JPA). */
    @Bean
    OutboxRepository outboxRepository(DataSource dataSource) {
        return new OutboxRepository(JdbcClient.create(dataSource));
    }

    @Bean
    OutboxWriter outboxWriter(OutboxRepository repository, EnvelopeCodec codec, Environment env) {
        return new OutboxWriter(repository, codec, env.getProperty("spring.application.name", "unknown-service"));
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                            OutboxProperties props) {
        return new OutboxRelay(repository, kafkaTemplate, props);
    }

    @Bean
    OutboxCleanupJob outboxCleanupJob(OutboxRepository repository, OutboxProperties props) {
        return new OutboxCleanupJob(repository, props);
    }

    @Bean
    InboxGuard inboxGuard(DataSource dataSource) {
        return new InboxGuard(JdbcClient.create(dataSource));
    }

    @Bean
    InboxCleanupJob inboxCleanupJob(InboxGuard inbox, InboxProperties props) {
        return new InboxCleanupJob(inbox, props);
    }

    /** Boot tự gắn CommonErrorHandler vào ConcurrentKafkaListenerContainerFactory mặc định. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        return KafkaErrorHandling.defaultErrorHandler(kafkaTemplate);
    }

    /**
     * ShedLock trên bảng shedlock của DB service. usingDbTime(): mọi replica so thời gian theo đồng hồ MySQL,
     * nên lệch đồng hồ giữa các node không làm lock hết hạn sớm/muộn.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
    static class SchedulingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterBinder.class)
    static class MetricsConfiguration {

        @Bean
        OutboxMetrics outboxMetrics(OutboxRepository repository) {
            return new OutboxMetrics(repository);
        }
    }
}
