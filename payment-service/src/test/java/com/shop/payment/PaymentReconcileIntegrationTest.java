package com.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.Topics;
import com.shop.payment.application.PaymentReconcileJob;
import com.shop.payment.domain.model.Payment;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

/** Cổng giả KHÔNG BAO GIỜ gọi callback (mất IPN) → chỉ reconcile chốt được trạng thái (docs/10 T7 bước 4). */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@DirtiesContext   // 2 class cùng group consumer order.confirmed: context cũ còn sống sẽ giành message
class PaymentReconcileIntegrationTest extends PaymentTestSupport {

    static final int PORT = freePort();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registerCommon(registry, PORT);
        registry.add("payment.fake-gateway.success-rate", () -> "1.0");
        registry.add("payment.fake-gateway.no-callback-rate", () -> "1.0");
        registry.add("payment.fake-gateway.min-delay", () -> "50ms");
        registry.add("payment.fake-gateway.max-delay", () -> "100ms");
        registry.add("payment.reconcile.pending-after", () -> "0s");
        registry.add("payment.reconcile.interval", () -> "1h");   // test tự gọi runOnce()
    }

    @Autowired
    PaymentReconcileJob reconcile;

    @Override
    int port() {
        return PORT;
    }

    @Test
    void lostCallback_reconcileAsksGatewayAndCompletes_exactlyOnce() throws Exception {
        String orderId = UUID.randomUUID().toString();
        kafka.send(Topics.ORDER_CONFIRMED, orderId, orderConfirmed(orderId, "500000.00")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> "PENDING".equals(statusByOrder(orderId)));
        String paymentId = jdbc.sql("SELECT id FROM payment WHERE order_id = ?").param(orderId).query(String.class).single();
        await().atMost(Duration.ofSeconds(10)).until(() -> "SUCCEEDED".equals(http().get()
            .uri("/fake-gateway/charges/{id}", paymentId).retrieve().body(Map.class).get("status")));
        Thread.sleep(500);
        assertThat(status(paymentId)).as("no callback arrived").isEqualTo("PENDING");

        reconcile.runOnce();
        reconcile.runOnce();   // lượt sau: không còn PENDING → không làm gì

        assertThat(status(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(outboxEvents(orderId)).isEqualTo(1);
        assertThat(outboxTopic(orderId)).isEqualTo(Topics.PAYMENT_SUCCESS);
    }

    @Test
    void paymentUnknownToGateway_failsAsNotFound() {
        Payment p = pendingPayment("100000.00");   // yêu cầu thu chưa từng tới cổng

        reconcile.runOnce();

        assertThat(status(p.getId())).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT reason FROM payment WHERE id = ?").param(p.getId()).query(String.class).single())
            .isEqualTo("NOT_FOUND_AT_GATEWAY");
        assertThat(outboxTopic(p.getOrderId())).isEqualTo(Topics.PAYMENT_FAILED);
    }
}
