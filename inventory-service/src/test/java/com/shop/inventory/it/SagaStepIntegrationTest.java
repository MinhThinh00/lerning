package com.shop.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.Topics;
import com.shop.common.messaging.it.KafkaTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Bước inventory trong saga, chạy thật qua Kafka: gửi order.* như order-service sẽ gửi, kiểm tra DB và event
 * inventory.* mà relay publish ra. Bao các DoD Phase 5:
 * - redeliver order.created → không trừ thêm;
 * - order.cancelled cho đơn FAILED → tồn kho không đổi;
 * - order.cancelled đến trước order.created → không giữ hàng.
 */
class SagaStepIntegrationTest extends InventoryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    MeterRegistry meters;

    @Test
    void orderCreatedReservesStockAndPublishesInventoryReserved() throws Exception {
        long p1 = newProduct(10);
        long p2 = newProduct(10);
        String orderId = UUID.randomUUID().toString();

        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p2, 3, p1, 2)));

        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("RESERVED", "RESERVED")));
        assertThat(stock(p1)).containsEntry("available", 8).containsEntry("reserved", 2);
        assertThat(stock(p2)).containsEntry("available", 7).containsEntry("reserved", 3);

        ConsumerRecord<String, String> evt = awaitEvent(Topics.INVENTORY_RESERVED, orderId);
        EventEnvelope env = codec.decode(evt.value());
        assertThat(env.eventType()).isEqualTo("InventoryReserved");
        assertThat(env.source()).isEqualTo("inventory-service");
        assertThat(env.payload().get("strategy").asString()).isEqualTo("L5");   // strategy mặc định của luồng Kafka
        assertThat(env.payload().get("items")).hasSize(2);
    }

    /** DoD: redeliver order.created (cùng message, hoặc message mới cùng orderId) → không trừ thêm, không phát thêm event. */
    @Test
    void redeliveredOrderCreatedDoesNotReserveTwice() throws Exception {
        long p = newProduct(10);
        String orderId = UUID.randomUUID().toString();
        String created = orderCreatedJson(orderId, Map.of(p, 4));

        kafka.send(Topics.ORDER_CREATED, orderId, created).get();
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("RESERVED")));

        double duplicatesBefore = duplicates();
        kafka.send(Topics.ORDER_CREATED, orderId, created).get();                                     // Kafka giao lại
        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p, 4)));     // order-service gửi lại
        await().atMost(TIMEOUT).until(() -> duplicates() >= duplicatesBefore + 2);

        assertThat(stock(p)).containsEntry("available", 6).containsEntry("reserved", 4);
        assertInvariant(p, 10);
        List<ConsumerRecord<String, String>> events =
            KafkaTestSupport.readFor(bootstrapServers(), Topics.INVENTORY_RESERVED, Duration.ofSeconds(3));
        assertThat(events).filteredOn(r -> orderId.equals(r.key())).hasSize(1);
    }

    /** DoD: đơn FAILED (hết hàng) rồi bị hủy → tồn kho không đổi. */
    @Test
    void outOfStockFailsAndLaterCancellationChangesNothing() throws Exception {
        long p = newProduct(2);
        String orderId = UUID.randomUUID().toString();

        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p, 5)));
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("FAILED")));

        EventEnvelope failed = codec.decode(awaitEvent(Topics.INVENTORY_FAILED, orderId).value());
        assertThat(failed.payload().get("reason").asString()).isEqualTo("OUT_OF_STOCK");
        assertThat(failed.payload().get("productId").asLong()).isEqualTo(p);

        String cancelEventId = send(Topics.ORDER_CANCELLED, orderId, "OrderCancelled", orderCancelled(orderId, Map.of(p, 5)));
        await().atMost(TIMEOUT).until(() -> processed(cancelEventId, "inventory:order.cancelled"));

        assertThat(stock(p)).containsEntry("available", 2).containsEntry("reserved", 0);
        assertThat(reservationStatuses(orderId)).containsExactly("FAILED");
        assertInvariant(p, 2);
    }

    /** All-or-nothing: item thứ hai hết hàng → item thứ nhất cũng không bị trừ. */
    @Test
    void multiItemOrderIsAllOrNothing() throws Exception {
        long plenty = newProduct(10);
        long scarce = newProduct(1);
        String orderId = UUID.randomUUID().toString();

        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(plenty, 2, scarce, 3)));
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("FAILED", "FAILED")));

        assertThat(stock(plenty)).containsEntry("available", 10).containsEntry("reserved", 0);
        assertThat(stock(scarce)).containsEntry("available", 1).containsEntry("reserved", 0);
    }

    @Test
    void unknownProductFailsWithNotFound() throws Exception {
        String orderId = UUID.randomUUID().toString();
        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(987_654_321L, 1)));

        EventEnvelope failed = codec.decode(awaitEvent(Topics.INVENTORY_FAILED, orderId).value());
        assertThat(failed.payload().get("reason").asString()).isEqualTo("NOT_FOUND");
    }

    /** DoD: order.cancelled đến TRƯỚC order.created → tombstone; order.created đến sau không giữ hàng, không phát event. */
    @Test
    void cancellationArrivingBeforeCreationPreventsReservation() throws Exception {
        long p = newProduct(10);
        String orderId = UUID.randomUUID().toString();

        String cancelEventId = send(Topics.ORDER_CANCELLED, orderId, "OrderCancelled", orderCancelled(orderId, Map.of(p, 3)));
        await().atMost(TIMEOUT).until(() -> processed(cancelEventId, "inventory:order.cancelled"));
        assertThat(reservationStatuses(orderId)).containsExactly("RELEASED");

        double duplicatesBefore = duplicates();
        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p, 3)));
        await().atMost(TIMEOUT).until(() -> duplicates() >= duplicatesBefore + 1);

        assertThat(stock(p)).containsEntry("available", 10).containsEntry("reserved", 0);
        assertThat(reservationStatuses(orderId)).containsExactly("RELEASED");
        assertThat(KafkaTestSupport.readFor(bootstrapServers(), Topics.INVENTORY_RESERVED, Duration.ofSeconds(2)))
            .noneMatch(r -> orderId.equals(r.key()));
    }

    @Test
    void orderPaidCommitsReservationOnce() throws Exception {
        long p = newProduct(10);
        String orderId = UUID.randomUUID().toString();
        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p, 4)));
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("RESERVED")));

        String paid = envelope(orderId, "OrderPaid", orderCancelled(orderId, Map.of(p, 4)));
        kafka.send(Topics.ORDER_PAID, orderId, paid).get();
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("COMMITTED")));
        assertThat(stock(p)).containsEntry("available", 6).containsEntry("reserved", 0).containsEntry("sold", 4);

        // Giao lại đúng message đó: Inbox chặn, không trừ reserved lần nữa
        kafka.send(Topics.ORDER_PAID, orderId, paid).get();
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
            .until(() -> column(p, "sold") == 4 && column(p, "available") == 6);
        assertInvariant(p, 10);
    }

    @Test
    void orderCancelledReleasesReservedStock() throws Exception {
        long p = newProduct(10);
        String orderId = UUID.randomUUID().toString();
        send(Topics.ORDER_CREATED, orderId, "OrderCreated", orderCreated(orderId, Map.of(p, 4)));
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("RESERVED")));

        send(Topics.ORDER_CANCELLED, orderId, "OrderCancelled", orderCancelled(orderId, Map.of(p, 4)));
        await().atMost(TIMEOUT).until(() -> reservationStatuses(orderId).equals(List.of("RELEASED")));

        assertThat(stock(p)).containsEntry("available", 10).containsEntry("reserved", 0);
        assertInvariant(p, 10);
    }

    // ── helpers ──

    /** Gửi 1 envelope mới (eventId mới). @return eventId */
    private String send(String topic, String orderId, String eventType, Map<String, Object> payload) throws Exception {
        String json = envelope(orderId, eventType, payload);
        kafka.send(topic, orderId, json).get();
        return codec.decode(json).eventId();
    }

    private String envelope(String orderId, String eventType, Map<String, Object> payload) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), eventType, 1, Instant.now(), "order-service",
            "Order", orderId, codec.mapper().valueToTree(payload)));
    }

    private String orderCreatedJson(String orderId, Map<Long, Integer> items) {
        return envelope(orderId, "OrderCreated", orderCreated(orderId, items));
    }

    private static Map<String, Object> orderCreated(String orderId, Map<Long, Integer> items) {
        return Map.of("orderId", orderId, "userId", "user-1", "items", lineItems(items),
            "totalAmount", "100000", "currency", "VND");
    }

    private static Map<String, Object> orderCancelled(String orderId, Map<Long, Integer> items) {
        return Map.of("orderId", orderId, "userId", "user-1", "reason", "TEST", "items", lineItems(items));
    }

    private static List<Map<String, Object>> lineItems(Map<Long, Integer> items) {
        return items.entrySet().stream()
            .map(e -> Map.<String, Object>of("productId", e.getKey(), "quantity", e.getValue())).toList();
    }

    private ConsumerRecord<String, String> awaitEvent(String topic, String orderId) {
        return KafkaTestSupport.readUntil(bootstrapServers(), topic, TIMEOUT,
                records -> records.stream().anyMatch(r -> orderId.equals(r.key())))
            .stream().filter(r -> orderId.equals(r.key())).findFirst()
            .orElseThrow(() -> new AssertionError("no " + topic + " event for " + orderId));
    }

    private boolean processed(String eventId, String consumer) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
            .params(eventId, consumer).query(Integer.class).single() == 1;
    }

    /** Số lần reserve trả AlreadyHandled: dùng làm mốc "listener đã xử lý xong message trùng". */
    private double duplicates() {
        Timer t = meters.find("inventory.reserve").tags("strategy", "L5", "outcome", "duplicate").timer();
        return t == null ? 0 : t.count();
    }
}
