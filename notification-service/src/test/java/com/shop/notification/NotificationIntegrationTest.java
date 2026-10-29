package com.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.SharedContainers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.mysql.MySQLContainer;

/** order.paid / order.cancelled → 1 thông báo; Kafka giao lại → không báo trùng (Inbox). */
@SpringBootTest
class NotificationIntegrationTest {

    static final MySQLContainer MYSQL = SharedContainers.mysql("db_notification");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, MYSQL);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    JdbcClient jdbc;

    @Test
    void orderPaidNotifiesOnceEvenIfRedelivered() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String paid = envelope(orderId, "OrderPaid",
            Map.of("orderId", orderId, "userId", "user-1", "items", List.of(), "totalAmount", "22990000.00", "currency", "VND"));

        kafka.send(Topics.ORDER_PAID, orderId, paid).get();
        kafka.send(Topics.ORDER_PAID, orderId, paid).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> types(orderId).equals(List.of("ORDER_PAID")));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> types(orderId).size() == 1);
        assertThat(jdbc.sql("SELECT message FROM notification_log WHERE order_id = ?").param(orderId)
            .query(String.class).single()).contains("22990000.00 VND");
    }

    @Test
    void orderCancelledNotifiesWithReason() throws Exception {
        String orderId = UUID.randomUUID().toString();
        kafka.send(Topics.ORDER_CANCELLED, orderId, envelope(orderId, "OrderCancelled",
            Map.of("orderId", orderId, "userId", "user-1", "reason", "OUT_OF_STOCK", "items", List.of()))).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> types(orderId).equals(List.of("ORDER_CANCELLED")));
        assertThat(jdbc.sql("SELECT message FROM notification_log WHERE order_id = ?").param(orderId)
            .query(String.class).single()).contains("OUT_OF_STOCK");
    }

    private List<String> types(String orderId) {
        return jdbc.sql("SELECT type FROM notification_log WHERE order_id = ? ORDER BY id").param(orderId)
            .query(String.class).list();
    }

    private String envelope(String orderId, String type, Map<String, Object> payload) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), type, 1, Instant.now(), "order-service",
            "Order", orderId, codec.mapper().valueToTree(payload)));
    }
}
