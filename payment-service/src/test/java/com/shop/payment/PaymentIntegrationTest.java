package com.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.KafkaTestSupport;
import com.shop.common.messaging.it.SharedContainers;
import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.application.port.PaymentGateway.ChargeResult;
import java.time.Duration;
import java.time.Instant;
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

/** order.confirmed → thu tiền (cổng giả lập bằng mock) → payment.success / payment.failed; không thu 2 lần. */
@SpringBootTest
class PaymentIntegrationTest {

    static final MySQLContainer MYSQL = SharedContainers.mysql("db_payment");
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, MYSQL);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PaymentGateway gateway;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    JdbcClient jdbc;

    @Test
    void successfulChargePublishesPaymentSuccessAndRedeliveryDoesNotChargeTwice() throws Exception {
        String orderId = UUID.randomUUID().toString();
        given(gateway.charge(eq(orderId), any(), any())).willReturn(new ChargeResult(true, null));
        String confirmed = orderConfirmed(orderId, "22990000.00");

        kafka.send(Topics.ORDER_CONFIRMED, orderId, confirmed).get();
        EventEnvelope success = codec.decode(awaitEvent(Topics.PAYMENT_SUCCESS, orderId));
        assertThat(success.payload().get("amount").asString()).isEqualTo("22990000.00");
        assertThat(success.payload().get("paymentId").asString()).isNotBlank();
        assertThat(paymentStatus(orderId)).isEqualTo("SUCCEEDED");

        kafka.send(Topics.ORDER_CONFIRMED, orderId, confirmed).get();                      // Kafka giao lại
        kafka.send(Topics.ORDER_CONFIRMED, orderId, orderConfirmed(orderId, "22990000.00")).get();   // order gửi lại
        Thread.sleep(3_000);
        verify(gateway, times(1)).charge(eq(orderId), any(), any());
        assertThat(KafkaTestSupport.readFor(bootstrap(), Topics.PAYMENT_SUCCESS, Duration.ofSeconds(2)))
            .filteredOn(r -> orderId.equals(r.key())).hasSize(1);
    }

    @Test
    void declinedChargePublishesPaymentFailed() throws Exception {
        String orderId = UUID.randomUUID().toString();
        given(gateway.charge(eq(orderId), any(), any())).willReturn(new ChargeResult(false, "DECLINED"));

        kafka.send(Topics.ORDER_CONFIRMED, orderId, orderConfirmed(orderId, "100000.00")).get();

        EventEnvelope failed = codec.decode(awaitEvent(Topics.PAYMENT_FAILED, orderId));
        assertThat(failed.payload().get("reason").asString()).isEqualTo("DECLINED");
        assertThat(paymentStatus(orderId)).isEqualTo("FAILED");
    }

    private String orderConfirmed(String orderId, String amount) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), "OrderConfirmed", 1, Instant.now(),
            "order-service", "Order", orderId, codec.mapper().valueToTree(
                Map.of("orderId", orderId, "userId", "user-1", "totalAmount", amount, "currency", "VND"))));
    }

    private String paymentStatus(String orderId) {
        return jdbc.sql("SELECT status FROM payment WHERE order_id = ?").param(orderId).query(String.class).single();
    }

    private String awaitEvent(String topic, String orderId) {
        return KafkaTestSupport.readUntil(bootstrap(), topic, TIMEOUT, rs -> rs.stream().anyMatch(r -> orderId.equals(r.key())))
            .stream().filter(r -> orderId.equals(r.key())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + topic + " for " + orderId)).value();
    }

    private static String bootstrap() {
        return SharedContainers.kafka().getBootstrapServers();
    }
}
