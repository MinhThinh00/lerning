package com.shop.common.messaging.it;

import com.shop.common.messaging.Topics;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Container dùng chung cho integration test của các service (qua shop-common-tests.jar).
 * Singleton theo JVM: start 1 lần, Ryuk dọn khi JVM thoát. Topic tạo sẵn giống job kafka-init
 * (deploy/infra/kafka/topics.sh), vì code không tự tạo topic.
 *
 * <pre>
 * static final MySQLContainer MYSQL = SharedContainers.mysql("db_order");
 * &#64;DynamicPropertySource
 * static void props(DynamicPropertyRegistry r) { SharedContainers.register(r, MYSQL); }
 * </pre>
 */
public final class SharedContainers {

    public static final List<String> BUSINESS_TOPICS = List.of(
        Topics.ORDER_CREATED, Topics.ORDER_CONFIRMED, Topics.ORDER_PAID, Topics.ORDER_CANCELLED,
        Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED, Topics.PAYMENT_SUCCESS, Topics.PAYMENT_FAILED);

    private static KafkaContainer kafka;

    private SharedContainers() {
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer("apache/kafka:3.9.1");
            kafka.start();
            BUSINESS_TOPICS.forEach(t -> KafkaTestSupport.createTopics(kafka.getBootstrapServers(), 3, t, Topics.dlt(t)));
        }
        return kafka;
    }

    /** MySQL riêng cho 1 service (mỗi service 1 DB như thật). */
    public static MySQLContainer mysql(String database) {
        MySQLContainer mysql = new MySQLContainer("mysql:8.0.36").withDatabaseName(database);
        mysql.start();
        return mysql;
    }

    /** Datasource + Kafka + các tinh chỉnh cho test (listener thấy topic nhanh, relay poll dày). */
    public static void register(DynamicPropertyRegistry registry, MySQLContainer mysql) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> kafka().getBootstrapServers());
        registry.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> "1000");
        registry.add("shop.outbox.poll-interval-ms", () -> "100");
    }
}
