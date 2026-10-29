package com.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.KafkaTestSupport;
import com.shop.common.messaging.it.SharedContainers;
import com.shop.payment.domain.model.Payment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

/**
 * docs/10 T7 với fake gateway THẬT (qua HTTP): order.confirmed → PENDING → cổng giả callback 1..3 lần,
 * có lúc đồng thời → đúng 1 payment.success. Cộng các ca callback: 10 callback đồng thời, sai chữ ký, lệch tiền.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@DirtiesContext   // 2 class cùng group consumer order.confirmed: context cũ còn sống sẽ giành message
class PaymentIntegrationTest extends PaymentTestSupport {

    static final int PORT = freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registerCommon(registry, PORT);
        registry.add("payment.fake-gateway.success-rate", () -> "1.0");
        registry.add("payment.fake-gateway.no-callback-rate", () -> "0");
        registry.add("payment.fake-gateway.min-delay", () -> "50ms");
        registry.add("payment.fake-gateway.max-delay", () -> "200ms");
        registry.add("payment.fake-gateway.max-callbacks", () -> "3");
    }

    @Override
    int port() {
        return PORT;
    }

    @Test
    void sagaThroughFakeGateway_publishesExactlyOneSuccess_evenWithDuplicateCallbacksAndRedelivery() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String confirmed = orderConfirmed(orderId, "22990000.00");

        kafka.send(Topics.ORDER_CONFIRMED, orderId, confirmed).get();
        kafka.send(Topics.ORDER_CONFIRMED, orderId, confirmed).get();   // Kafka giao lại → gửi lại cổng, cổng idempotent

        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(statusByOrder(orderId)));
        Thread.sleep(2_000);   // đợi hết các callback trùng của cổng giả
        assertThat(outboxEvents(orderId)).isEqualTo(1);

        EventEnvelope success = codec.decode(KafkaTestSupport.readUntil(bootstrap(), Topics.PAYMENT_SUCCESS,
                Duration.ofSeconds(30), rs -> rs.stream().anyMatch(r -> orderId.equals(r.key())))
            .stream().filter(r -> orderId.equals(r.key())).findFirst().orElseThrow().value());
        assertThat(success.payload().get("amount").asString()).isEqualTo("22990000.00");
        assertThat(jdbc.sql("SELECT provider_txn_id FROM payment WHERE order_id = ?").param(orderId)
            .query(String.class).single()).startsWith("FGW-");
    }

    @Test
    void tenConcurrentCallbacks_exactlyOneEvent() throws Exception {
        Payment p = pendingPayment("100000.00");
        byte[] body = callbackBody(p, "SUCCEEDED", "100000.00");
        String sig = sign(body);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(pool.submit(() -> {
                start.await();
                return postCallback(body, sig);
            }));
        }
        start.countDown();
        for (Future<Integer> f : codes) {
            assertThat(f.get()).as("duplicates also get 200 so the gateway stops retrying").isEqualTo(200);
        }
        pool.shutdown();

        assertThat(status(p.getId())).isEqualTo("SUCCEEDED");
        assertThat(outboxEvents(p.getOrderId())).isEqualTo(1);
    }

    @Test
    void invalidSignature_401_andNothingChanges() {
        Payment p = pendingPayment("100000.00");
        byte[] body = callbackBody(p, "SUCCEEDED", "100000.00");

        assertThat(postCallback(body, "00".repeat(32))).isEqualTo(401);
        assertThat(postCallback(body, null)).isEqualTo(401);
        byte[] tampered = callbackBody(p, "SUCCEEDED", "1.00");
        assertThat(postCallback(tampered, sign(body))).as("signature of another body").isEqualTo(401);

        assertThat(status(p.getId())).isEqualTo("PENDING");
        assertThat(outboxEvents(p.getOrderId())).isZero();
    }

    @Test
    void failedCallback_publishesPaymentFailedWithReason() {
        Payment p = pendingPayment("100000.00");
        byte[] body = callbackBody(p, "FAILED", "100000.00");

        assertThat(postCallback(body, sign(body))).isEqualTo(200);

        assertThat(status(p.getId())).isEqualTo("FAILED");
        assertThat(outboxTopic(p.getOrderId())).isEqualTo(Topics.PAYMENT_FAILED);
        assertThat(jdbc.sql("SELECT reason FROM payment WHERE id = ?").param(p.getId()).query(String.class).single())
            .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void amountMismatch_409_andNotCompleted() {
        Payment p = pendingPayment("100000.00");
        byte[] body = callbackBody(p, "SUCCEEDED", "1.00");

        assertThat(postCallback(body, sign(body))).isEqualTo(409);
        assertThat(status(p.getId())).isEqualTo("PENDING");
    }

    private static String bootstrap() {
        return SharedContainers.kafka().getBootstrapServers();
    }
}
