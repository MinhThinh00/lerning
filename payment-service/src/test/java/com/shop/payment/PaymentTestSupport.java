package com.shop.payment;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.it.SharedContainers;
import com.shop.payment.application.callback.CallbackSignature;
import com.shop.payment.application.callback.GatewayCallback;
import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nền chung cho test payment: MySQL + Kafka (SharedContainers) và app chạy ở cổng THẬT, vì fake gateway gọi
 * callback qua HTTP về chính app. Cổng chọn trước (TestSocketUtils) để ghi được vào base-url / callback-url.
 */
abstract class PaymentTestSupport {

    static final MySQLContainer MYSQL = SharedContainers.mysql("db_payment");
    static final String SECRET = "test-hmac-secret";

    static void registerCommon(DynamicPropertyRegistry registry, int port) {
        SharedContainers.register(registry, MYSQL);
        registry.add("server.port", () -> port);
        registry.add("payment.gateway.base-url", () -> "http://localhost:" + port + "/fake-gateway");
        registry.add("payment.gateway.callback-url", () -> "http://localhost:" + port + "/api/payments/callback");
        registry.add("payment.gateway.hmac-secret", () -> SECRET);
    }

    static int freePort() {
        return TestSocketUtils.findAvailableTcpPort();
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    PaymentRepository payments;

    abstract int port();

    RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port())
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })   // test tự kiểm status
            .build();
    }

    String orderConfirmed(String orderId, String amount) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), "OrderConfirmed", 1, Instant.now(),
            "order-service", "Order", orderId, codec.mapper().valueToTree(
                Map.of("orderId", orderId, "userId", "user-1", "totalAmount", amount, "currency", "VND"))));
    }

    /** Payment PENDING ghi thẳng DB, cổng giả KHÔNG biết tới nó (để tự bắn callback / reconcile NOT_FOUND). */
    Payment pendingPayment(String amount) {
        return payments.saveAndFlush(Payment.pending(UUID.randomUUID().toString(), new BigDecimal(amount), "VND"));
    }

    byte[] callbackBody(Payment p, String status, String amount) {
        return json.writeValueAsBytes(new GatewayCallback(p.getId(), "TXN-" + UUID.randomUUID(), status,
            "FAILED".equals(status) ? "INSUFFICIENT_FUNDS" : null, amount, "VND"));
    }

    int postCallback(byte[] body, String signature) {
        return http().post().uri("/api/payments/callback").contentType(MediaType.APPLICATION_JSON)
            .header(CallbackSignature.HEADER, signature).body(body)
            .retrieve().toBodilessEntity().getStatusCode().value();
    }

    String sign(byte[] body) {
        return new CallbackSignature(SECRET).sign(body);
    }

    String status(String paymentId) {
        return jdbc.sql("SELECT status FROM payment WHERE id = ?").param(paymentId).query(String.class).single();
    }

    String statusByOrder(String orderId) {
        return jdbc.sql("SELECT status FROM payment WHERE order_id = ?").param(orderId).query(String.class).optional().orElse(null);
    }

    /** Số event payment.* trong outbox cho đơn: nguồn sự thật "đã phát mấy lần", không phụ thuộc relay. */
    int outboxEvents(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE message_key = ? AND topic LIKE 'payment.%'")
            .param(orderId).query(Integer.class).single();
    }

    String outboxTopic(String orderId) {
        return jdbc.sql("SELECT topic FROM outbox_event WHERE message_key = ? AND topic LIKE 'payment.%'")
            .param(orderId).query(String.class).single();
    }
}
