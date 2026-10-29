package com.shop.common.messaging.it;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Lớp cha của integration test messaging: MySQL + Kafka thật (cần Docker).
 *
 * Container là singleton cho cả JVM (start 1 lần trong static block, Ryuk dọn khi JVM thoát), dùng chung
 * mọi lớp test. Spring cache context theo cấu hình, nên các context của lớp test trước vẫn sống và relay
 * của chúng vẫn chạy trên cùng DB: chính là tình huống "nhiều replica", ShedLock phải lo được.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
public abstract class MessagingIntegrationTest {

    // Cùng image với deploy/ (mysql) và image đã có sẵn trên máy dev (kafka)
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36").withDatabaseName("db_it");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        Startables.deepStart(MYSQL, KAFKA).join();
    }

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        connectionProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    /** Dùng cho cả context thứ hai dựng tay (test 2 instance). */
    protected static Map<String, Object> connectionProperties() {
        return Map.of(
            "spring.datasource.url", MYSQL.getJdbcUrl(),
            "spring.datasource.username", MYSQL.getUsername(),
            "spring.datasource.password", MYSQL.getPassword(),
            "spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    }

    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    protected String outboxStatus(String eventId) {
        return jdbc.sql("SELECT status FROM outbox_event WHERE event_id = ?").param(eventId)
            .query(String.class).optional().orElse(null);
    }
}
