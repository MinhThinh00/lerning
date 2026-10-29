package com.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.KafkaTestSupport;
import com.shop.common.messaging.it.SharedContainers;
import com.shop.order.application.port.ProductCatalog;
import com.shop.order.application.port.ProductCatalog.CatalogProduct;
import com.shop.order.application.port.ProductCatalog.CatalogUnavailableException;
import com.shop.order.application.saga.OrderTimeoutJob;
import com.shop.order.domain.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.mysql.MySQLContainer;

/**
 * order-service với MySQL + Kafka thật; product-service, inventory, payment được giả lập:
 * product bằng mock ProductCatalog, inventory/payment bằng cách gửi event của họ lên Kafka.
 * Bao: đặt đơn (giá do server tra, Idempotency-Key), 4 nhánh saga, event muộn/trùng (docs/07 §3), timeout, IDOR.
 */
@SpringBootTest(properties = {
    "order.pending-timeout=2s",
    "order.payment-timeout=2s",
    "order.timeout.check-interval=1h"   // job chạy tay trong test, không để scheduler tự hủy đơn của test khác
})
@AutoConfigureMockMvc
class OrderSagaIntegrationTest {

    static final MySQLContainer MYSQL = SharedContainers.mysql("db_order");
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, MYSQL);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    ProductCatalog catalog;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    OrderTimeoutJob timeoutJob;

    @BeforeEach
    void catalog() {
        given(catalog.findByIds(anyCollection())).willReturn(List.of(
            new CatalogProduct(1, "PHONE-001", "iPhone 16", new BigDecimal("22990000.00"), "VND"),
            new CatalogProduct(7, "ACC-001", "AirPods Pro 2", new BigDecimal("5990000.00"), "VND")));
    }

    // ── Đặt đơn ──

    @Test
    void placeOrderPricesOnServerAndPublishesOrderCreated() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":2},{\"productId\":7,\"quantity\":1}]");

        mvc.perform(get("/api/orders/" + orderId).with(user("user-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalAmount").value(51970000.00))   // 2 × 22.990.000 + 5.990.000
            .andExpect(jsonPath("$.items.length()").value(2));

        EventEnvelope created = codec.decode(awaitEvent(Topics.ORDER_CREATED, orderId).value());
        assertThat(created.eventType()).isEqualTo("OrderCreated");
        assertThat(created.payload().get("userId").asString()).isEqualTo("user-1");
        assertThat(created.payload().get("totalAmount").asString()).isEqualTo("51970000.00");   // tiền dạng string
        assertThat(created.payload().get("items")).hasSize(2);
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = place(user("user-1"), key, "[{\"productId\":1,\"quantity\":1}]");
        String second = place(user("user-1"), key, "[{\"productId\":1,\"quantity\":1}]");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE idempotency_key = ?").param(key)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void unknownProductIs400AndCatalogDownIs503() throws Exception {
        mvc.perform(placeRequest(null, "[{\"productId\":999,\"quantity\":1}]").with(user("user-1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));

        given(catalog.findByIds(anyCollection())).willThrow(new CatalogUnavailableException("down", null));
        mvc.perform(placeRequest(null, "[{\"productId\":1,\"quantity\":1}]").with(user("user-1")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void customerWithoutOrderWriteCannotPlaceOrders() throws Exception {
        mvc.perform(placeRequest(null, "[{\"productId\":1,\"quantity\":1}]")
                .with(jwt().jwt(j -> j.subject("seller-1")).authorities(new SimpleGrantedAuthority("product:write"))))
            .andExpect(status().isForbidden());
    }

    // ── Saga ──

    /** Happy path: reserved → CONFIRMED (+order.confirmed) → payment.success → PAID (+order.paid). */
    @Test
    void happyPathEndsInPaid() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");

        sendEvent(Topics.INVENTORY_RESERVED, orderId, "InventoryReserved", Map.of("orderId", orderId, "strategy", "L1"));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        assertThat(codec.decode(awaitEvent(Topics.ORDER_CONFIRMED, orderId).value()).payload().get("totalAmount").asString())
            .isEqualTo("22990000.00");

        String paymentSuccess = envelope(orderId, "PaymentSucceeded", Map.of("orderId", orderId, "paymentId", "p-1",
            "amount", "22990000.00", "currency", "VND"));
        kafka.send(Topics.PAYMENT_SUCCESS, orderId, paymentSuccess).get();
        awaitStatus(orderId, OrderStatus.PAID);
        assertThat(codec.decode(awaitEvent(Topics.ORDER_PAID, orderId).value()).payload().get("items")).hasSize(1);

        // payment.success bị giao lại → không phát order.paid lần 2
        kafka.send(Topics.PAYMENT_SUCCESS, orderId, paymentSuccess).get();
        assertThat(KafkaTestSupport.readFor(bootstrap(), Topics.ORDER_PAID, Duration.ofSeconds(3)))
            .filteredOn(r -> orderId.equals(r.key())).hasSize(1);
    }

    @Test
    void outOfStockCancelsOrderWithReason() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");

        sendEvent(Topics.INVENTORY_FAILED, orderId, "InventoryFailed",
            Map.of("orderId", orderId, "reason", "OUT_OF_STOCK", "productId", 1));
        awaitStatus(orderId, OrderStatus.CANCELLED);

        assertThat(cancelReason(orderId)).isEqualTo("OUT_OF_STOCK");
        EventEnvelope cancelled = codec.decode(awaitEvent(Topics.ORDER_CANCELLED, orderId).value());
        assertThat(cancelled.payload().get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(cancelled.payload().get("items")).hasSize(1);   // inventory cần items để nhả / tombstone
    }

    @Test
    void paymentFailureCancelsConfirmedOrder() throws Exception {
        String orderId = confirmedOrder();

        sendEvent(Topics.PAYMENT_FAILED, orderId, "PaymentFailed", Map.of("orderId", orderId, "reason", "DECLINED"));
        awaitStatus(orderId, OrderStatus.CANCELLED);

        assertThat(cancelReason(orderId)).isEqualTo("PAYMENT_FAILED");
        awaitEvent(Topics.ORDER_CANCELLED, orderId);
    }

    /** Event muộn (docs/07 §3): đơn đã hủy vì timeout, rồi tiền mới về → giữ CANCELLED, refund_required = true. */
    @Test
    void latePaymentAfterTimeoutMarksRefundRequired() throws Exception {
        String orderId = confirmedOrder();
        Thread.sleep(2_500);                       // quá payment-timeout (2s)
        timeoutJob.runOnce();
        assertThat(currentStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(cancelReason(orderId)).isEqualTo("TIMEOUT");
        awaitEvent(Topics.ORDER_CANCELLED, orderId);   // inventory sẽ nhả hàng

        sendEvent(Topics.PAYMENT_SUCCESS, orderId, "PaymentSucceeded",
            Map.of("orderId", orderId, "paymentId", "p-late", "amount", "22990000.00", "currency", "VND"));
        await().atMost(TIMEOUT).until(() -> Boolean.TRUE.equals(jdbc.sql("SELECT refund_required FROM orders WHERE id = ?")
            .param(orderId).query(Boolean.class).single()));

        assertThat(currentStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(KafkaTestSupport.readFor(bootstrap(), Topics.ORDER_PAID, Duration.ofSeconds(2)))
            .noneMatch(r -> orderId.equals(r.key()));
    }

    /** inventory.reserved tới sau khi đơn đã bị hủy → bỏ qua, không throw, không DLT. */
    @Test
    void lateInventoryReservedForCancelledOrderIsIgnored() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");
        mvc.perform(post("/api/orders/" + orderId + "/cancel").with(user("user-1"))).andExpect(status().isOk());

        String eventId = sendEvent(Topics.INVENTORY_RESERVED, orderId, "InventoryReserved", Map.of("orderId", orderId));
        await().atMost(TIMEOUT).until(() -> jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ?")
            .param(eventId).query(Integer.class).single() == 1);

        assertThat(currentStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(cancelReason(orderId)).isEqualTo("USER_CANCELLED");
        assertThat(KafkaTestSupport.readFor(bootstrap(), Topics.dlt(Topics.INVENTORY_RESERVED), Duration.ofSeconds(2)))
            .noneMatch(r -> orderId.equals(r.key()));
    }

    @Test
    void pendingOrderTimesOutWhenInventoryNeverAnswers() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");
        Thread.sleep(2_500);
        timeoutJob.runOnce();

        assertThat(currentStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(cancelReason(orderId)).isEqualTo("TIMEOUT");
    }

    // ── Data scoping (docs/10 T9) ──

    @Test
    void usersSeeOnlyTheirOwnOrders() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");

        mvc.perform(get("/api/orders/" + orderId).with(user("user-2"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").with(user("user-2"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + orderId).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/orders").with(user("user-2")))
            .andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(orderId))));
    }

    @Test
    void paidOrderCannotBeCancelledByUser() throws Exception {
        String orderId = confirmedOrder();
        sendEvent(Topics.PAYMENT_SUCCESS, orderId, "PaymentSucceeded",
            Map.of("orderId", orderId, "paymentId", "p-2", "amount", "22990000.00", "currency", "VND"));
        awaitStatus(orderId, OrderStatus.PAID);

        mvc.perform(post("/api/orders/" + orderId + "/cancel").with(user("user-1")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
    }

    // ── helpers ──

    private String confirmedOrder() throws Exception {
        String orderId = place(user("user-1"), null, "[{\"productId\":1,\"quantity\":1}]");
        sendEvent(Topics.INVENTORY_RESERVED, orderId, "InventoryReserved", Map.of("orderId", orderId));
        awaitStatus(orderId, OrderStatus.CONFIRMED);
        return orderId;
    }

    private String place(RequestPostProcessor who, String idempotencyKey, String itemsJson) throws Exception {
        String body = mvc.perform(placeRequest(idempotencyKey, itemsJson).with(who))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder placeRequest(String key, String itemsJson) {
        var req = post("/api/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":" + itemsJson + ",\"shippingAddress\":\"Hà Nội\"}");
        return key == null ? req : req.header("Idempotency-Key", key);
    }

    private String sendEvent(String topic, String orderId, String type, Map<String, Object> payload) throws Exception {
        String json = envelope(orderId, type, payload);
        kafka.send(topic, orderId, json).get();
        return codec.decode(json).eventId();
    }

    private String envelope(String orderId, String type, Map<String, Object> payload) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), type, 1, Instant.now(), "test",
            "Test", orderId, codec.mapper().valueToTree(payload)));
    }

    private void awaitStatus(String orderId, OrderStatus expected) {
        await().atMost(TIMEOUT).until(() -> expected.name().equals(currentStatus(orderId)));
    }

    private String currentStatus(String orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    private String cancelReason(String orderId) {
        return jdbc.sql("SELECT cancel_reason FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    private ConsumerRecord<String, String> awaitEvent(String topic, String orderId) {
        return KafkaTestSupport.readUntil(bootstrap(), topic, TIMEOUT, rs -> rs.stream().anyMatch(r -> orderId.equals(r.key())))
            .stream().filter(r -> orderId.equals(r.key())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + topic + " for " + orderId));
    }

    private static String bootstrap() {
        return SharedContainers.kafka().getBootstrapServers();
    }

    private static RequestPostProcessor user(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("ROLE_USER"),
            new SimpleGrantedAuthority("order:read"), new SimpleGrantedAuthority("order:write"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("order:read"), new SimpleGrantedAuthority("order:write"));
    }
}
