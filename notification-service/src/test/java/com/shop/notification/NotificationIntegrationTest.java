package com.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.SharedContainers;
import com.shop.notification.application.NotificationRetryJob;
import com.shop.notification.application.port.UserDirectory;
import com.shop.notification.application.port.UserDirectory.Contact;
import com.shop.notification.application.port.UserDirectory.UserDirectoryUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * order.paid / order.cancelled → email THẬT qua SMTP tới Mailpit (container), địa chỉ lấy từ UserDirectory
 * (mock: phần gọi M2M sang user-service kiểm bằng chạy thật). Kafka giao lại → không gửi trùng.
 * user-service chết → thông báo nằm PENDING, retry job gửi khi user-service sống lại.
 */
@SpringBootTest
class NotificationIntegrationTest {

    static final MySQLContainer MYSQL = SharedContainers.mysql("db_notification");

    @SuppressWarnings("resource")
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.21.8")
        .withExposedPorts(1025, 8025)
        .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, MYSQL);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("notification.retry-after", () -> "0s");
        registry.add("notification.retry-interval", () -> "1h");   // test tự gọi runOnce()
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    UserDirectory users;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    NotificationRetryJob retryJob;

    @Test
    void orderPaid_sendsOneRealEmail_evenIfRedelivered() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String email = "buyer-" + orderId + "@shop.local";
        given(users.findContact(userId)).willReturn(Optional.of(new Contact(email, "Lan", "vi")));
        String paid = envelope(orderId, "OrderPaid",
            Map.of("orderId", orderId, "userId", userId, "items", List.of(), "totalAmount", "22990000.00", "currency", "VND"));

        kafka.send(Topics.ORDER_PAID, orderId, paid).get();
        kafka.send(Topics.ORDER_PAID, orderId, paid).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(status(orderId)));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> mailsTo(email).size() == 1);
        JsonNode mail = mailsTo(email).get(0);
        assertThat(mail.get("Subject").asString()).contains(orderId).contains("đã thanh toán");
        assertThat(mailText(mail.get("ID").asString())).contains("Chào Lan").contains("22990000.00 VND");
        assertThat(rows(orderId)).isEqualTo(1);
    }

    @Test
    void userServiceDown_staysPending_thenRetryJobSendsWhenItRecovers() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String email = "late-" + orderId + "@shop.local";
        given(users.findContact(userId)).willThrow(new UserDirectoryUnavailableException("connection refused", null));

        kafka.send(Topics.ORDER_CANCELLED, orderId, envelope(orderId, "OrderCancelled",
            Map.of("orderId", orderId, "userId", userId, "reason", "OUT_OF_STOCK", "items", List.of()))).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> "PENDING".equals(status(orderId)) && attempts(orderId) == 1);
        assertThat(jdbc.sql("SELECT last_error FROM notification_log WHERE order_id = ?").param(orderId)
            .query(String.class).single()).startsWith("USER_SERVICE");
        assertThat(mailsTo(email)).isEmpty();

        willReturn(Optional.of(new Contact(email, "Minh", "vi"))).given(users).findContact(userId);   // given(mock.x()) sẽ gọi stub đang ném lỗi
        retryJob.runOnce();

        assertThat(status(orderId)).isEqualTo("SENT");
        assertThat(attempts(orderId)).isEqualTo(2);
        await().atMost(Duration.ofSeconds(5)).until(() -> mailsTo(email).size() == 1);
        assertThat(mailsTo(email).get(0).get("Subject").asString()).contains("đã bị hủy");
    }

    @Test
    void unknownUser_failsWithoutRetrying() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        given(users.findContact(userId)).willReturn(Optional.empty());

        kafka.send(Topics.ORDER_PAID, orderId, envelope(orderId, "OrderPaid",
            Map.of("orderId", orderId, "userId", userId, "items", List.of(), "totalAmount", "1.00", "currency", "VND"))).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(status(orderId)));
        retryJob.runOnce();
        assertThat(attempts(orderId)).isEqualTo(1);
    }

    private String status(String orderId) {
        return jdbc.sql("SELECT status FROM notification_log WHERE order_id = ?").param(orderId)
            .query(String.class).optional().orElse(null);
    }

    private int attempts(String orderId) {
        return jdbc.sql("SELECT attempts FROM notification_log WHERE order_id = ?").param(orderId)
            .query(Integer.class).single();
    }

    private int rows(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_log WHERE order_id = ?").param(orderId)
            .query(Integer.class).single();
    }

    private List<JsonNode> mailsTo(String email) {
        JsonNode body = mailpit().get().uri("/api/v1/messages?limit=500").retrieve().body(JsonNode.class);
        return body.get("messages").valueStream()
            .filter(m -> m.get("To").valueStream().anyMatch(to -> email.equals(to.get("Address").asString())))
            .toList();
    }

    private String mailText(String id) {
        return mailpit().get().uri("/api/v1/message/{id}", id).retrieve().body(JsonNode.class).get("Text").asString();
    }

    private static RestClient mailpit() {
        return RestClient.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025));
    }

    private String envelope(String orderId, String type, Map<String, Object> payload) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), type, 1, Instant.now(), "order-service",
            "Order", orderId, codec.mapper().valueToTree(payload)));
    }
}
