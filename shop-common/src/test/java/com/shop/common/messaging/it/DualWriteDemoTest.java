package com.shop.common.messaging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.outbox.OutboxWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test đối chứng (docs/10 T5): cùng một kịch bản lỗi, dual-write làm DB và Kafka lệch nhau,
 * outbox thì không. "Crash" được giả lập bằng exception đúng ở giữa hai bước.
 */
class DualWriteDemoTest extends MessagingIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate tx;

    private String topic;

    @BeforeEach
    void setUp() {
        jdbc.sql("CREATE TABLE IF NOT EXISTS demo_order (id VARCHAR(36) PRIMARY KEY)").update();
        topic = "it.dualwrite." + UUID.randomUUID();
        KafkaTestSupport.createTopics(bootstrapServers(), 1, topic);
    }

    /** Dual-write, ca 1: DB commit xong, chết trước khi send → DB có đơn, Kafka không có event (event mất). */
    @Test
    void dualWrite_crashAfterCommit_losesEvent() {
        String orderId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> {
            tx.executeWithoutResult(s -> insertOrder(orderId));
            crash();
            kafka.send(topic, orderId, "OrderCreated");
        }).isInstanceOf(IllegalStateException.class);

        assertThat(orderExists(orderId)).isTrue();
        assertThat(keysOnTopic(Duration.ofSeconds(5))).doesNotContain(orderId);
    }

    /** Dual-write, ca 2: send trong transaction rồi transaction rollback → Kafka có event cho đơn không tồn tại. */
    @Test
    void dualWrite_sendThenRollback_publishesPhantomEvent() {
        String orderId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertOrder(orderId);
            kafka.send(topic, orderId, "OrderCreated").join();
            crash();
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderExists(orderId)).isFalse();
        assertThat(keysOnTopic(Duration.ofSeconds(5))).contains(orderId);
    }

    /** Outbox, ca 1: commit xong rồi chết → event đã nằm trong outbox cùng đơn, relay vẫn publish. */
    @Test
    void outbox_crashAfterCommit_eventStillPublished() {
        String orderId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> {
            tx.executeWithoutResult(s -> {
                insertOrder(orderId);
                outbox.write(topic, "Order", orderId, orderId, "OrderCreated", Map.of("orderId", orderId));
            });
            crash();
        }).isInstanceOf(IllegalStateException.class);

        assertThat(orderExists(orderId)).isTrue();
        await().atMost(Duration.ofSeconds(30)).until(() -> keysOnTopic(Duration.ofSeconds(2)).contains(orderId));
    }

    /** Outbox, ca 2: rollback → cả đơn lẫn event cùng biến mất. */
    @Test
    void outbox_rollback_neitherOrderNorEvent() {
        String orderId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertOrder(orderId);
            outbox.write(topic, "Order", orderId, orderId, "OrderCreated", Map.of("orderId", orderId));
            crash();
        })).isInstanceOf(IllegalStateException.class);

        assertThat(orderExists(orderId)).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?").param(orderId)
            .query(Integer.class).single()).isZero();
    }

    private void insertOrder(String id) {
        jdbc.sql("INSERT INTO demo_order (id) VALUES (?)").param(id).update();
    }

    private boolean orderExists(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM demo_order WHERE id = ?").param(id).query(Integer.class).single() > 0;
    }

    private List<String> keysOnTopic(Duration duration) {
        return KafkaTestSupport.readFor(bootstrapServers(), topic, duration).stream().map(ConsumerRecord::key).toList();
    }

    private static void crash() {
        throw new IllegalStateException("simulated crash");
    }
}
