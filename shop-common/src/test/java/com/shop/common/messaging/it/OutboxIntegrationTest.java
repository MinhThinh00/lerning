package com.shop.common.messaging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.outbox.OutboxCleanupJob;
import com.shop.common.messaging.outbox.OutboxWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

/** DoD Phase 3: rollback không publish, publish đúng định dạng, Kafka chết rồi sống lại, cleanup. */
class OutboxIntegrationTest extends MessagingIntegrationTest {

    record TestPayload(String key, int seq) {
    }

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    OutboxCleanupJob cleanupJob;

    @Test
    void writeOutsideTransactionFailsFast() {
        assertThatThrownBy(() -> outbox.write("it.any", "Test", "a1", "a1", "TestEvent", new TestPayload("a1", 0)))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void publishesEnvelopeWithKeyAndHeaders() {
        String topic = newTopic("it.outbox.format");
        String eventId = tx.execute(s -> outbox.write(topic, "Order", "order-1", "order-1", "OrderCreated",
            new TestPayload("order-1", 7)));

        List<ConsumerRecord<String, String>> records =
            KafkaTestSupport.readUntil(bootstrapServers(), topic, Duration.ofSeconds(30), r -> !r.isEmpty());

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> rec = records.getFirst();
        assertThat(rec.key()).isEqualTo("order-1");
        assertThat(KafkaTestSupport.header(rec, "eventId")).isEqualTo(eventId);
        assertThat(KafkaTestSupport.header(rec, "eventType")).isEqualTo("OrderCreated");

        EventEnvelope env = codec.decode(rec.value());
        assertThat(env.eventId()).isEqualTo(eventId);
        assertThat(env.eventVersion()).isEqualTo(1);
        assertThat(env.source()).isEqualTo("shop-common-it");
        assertThat(env.aggregateType()).isEqualTo("Order");
        assertThat(env.aggregateId()).isEqualTo("order-1");
        assertThat(env.occurredAt()).isNotNull();
        assertThat(env.payloadAs(codec.mapper(), TestPayload.class)).isEqualTo(new TestPayload("order-1", 7));
        assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    }

    /** DoD: ghi outbox trong tx bị rollback → không có event nào được publish. */
    @Test
    void rolledBackWriteIsNeverPublished() {
        String topic = newTopic("it.outbox.rollback");
        List<String> rolledBack = new ArrayList<>();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            rolledBack.add(outbox.write(topic, "Test", "k", "k", "TestEvent", new TestPayload("k", 1)));
            throw new IllegalStateException("business failure after writing outbox");
        })).isInstanceOf(IllegalStateException.class);

        // Marker ghi SAU, commit bình thường. Relay publish theo thứ tự id → thấy marker nghĩa là
        // mọi thứ trước nó (nếu có) đã đi qua.
        String marker = tx.execute(s -> outbox.write(topic, "Test", "k", "k", "Marker", new TestPayload("k", 2)));

        List<ConsumerRecord<String, String>> records = KafkaTestSupport.readUntil(bootstrapServers(), topic,
            Duration.ofSeconds(30), r -> r.stream().anyMatch(rec -> marker.equals(KafkaTestSupport.header(rec, "eventId"))));

        assertThat(records).extracting(rec -> KafkaTestSupport.header(rec, "eventId")).containsExactly(marker);
        assertThat(outboxStatus(rolledBack.getFirst())).isNull();
    }

    /** DoD: Kafka down 30s → event dồn lại, Kafka lên → publish đủ, đúng thứ tự. */
    @Test
    void eventsPileUpWhileKafkaIsDownAndArePublishedWhenItReturns() {
        String topic = newTopic("it.outbox.down");
        DockerClient docker = DockerClientFactory.instance().client();
        List<String> ids = new ArrayList<>();

        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            for (int i = 0; i < 10; i++) {
                int seq = i;
                ids.add(tx.execute(s -> outbox.write(topic, "Test", "k", "k", "TestEvent", new TestPayload("k", seq))));
            }
            // Suốt 30s broker "chết": không event nào được đánh dấu PUBLISHED, không event nào bị mất
            await().during(Duration.ofSeconds(30)).atMost(Duration.ofSeconds(35))
                .until(() -> ids.stream().allMatch(id -> "PENDING".equals(outboxStatus(id))));
            assertThat(attempts(ids.getFirst())).as("relay kept retrying the head event").isPositive();
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(90))
            .until(() -> ids.stream().allMatch(id -> "PUBLISHED".equals(outboxStatus(id))));

        List<ConsumerRecord<String, String>> records =
            KafkaTestSupport.readUntil(bootstrapServers(), topic, Duration.ofSeconds(30), r -> r.size() >= ids.size());
        List<Integer> seqs = records.stream()
            .map(rec -> codec.decode(rec.value()).payloadAs(codec.mapper(), TestPayload.class).seq())
            .toList();
        assertThat(seqs).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void cleanupDeletesOnlyOldPublishedEvents() {
        String oldPublished = insertRaw("PUBLISHED", "UTC_TIMESTAMP(6) - INTERVAL 4 DAY");
        String recentPublished = insertRaw("PUBLISHED", "UTC_TIMESTAMP(6) - INTERVAL 1 DAY");

        cleanupJob.cleanup();

        assertThat(outboxStatus(oldPublished)).isNull();
        assertThat(outboxStatus(recentPublished)).isEqualTo("PUBLISHED");
    }

    private static String newTopic(String prefix) {
        String topic = prefix + "." + UUID.randomUUID();
        KafkaTestSupport.createTopics(bootstrapServers(), 3, topic);
        return topic;
    }

    private int attempts(String eventId) {
        return jdbc.sql("SELECT attempts FROM outbox_event WHERE event_id = ?").param(eventId).query(Integer.class).single();
    }

    /** Chèn thẳng dòng đã PUBLISHED (không qua writer, để relay không động vào). */
    private String insertRaw(String status, String publishedAtSql) {
        String eventId = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, topic, message_key,
                                          payload, status, created_at, published_at)
                VALUES (?, 'Test', 'k', 'TestEvent', 'it.cleanup', 'k', '{}', ?, %s, %s)
                """.formatted(publishedAtSql, publishedAtSql))
            .params(eventId, status)
            .update();
        return eventId;
    }
}
