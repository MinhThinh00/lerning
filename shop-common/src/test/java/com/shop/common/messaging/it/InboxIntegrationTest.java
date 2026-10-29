package com.shop.common.messaging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.inbox.InboxGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * DoD Phase 3: gửi cùng một message 2 lần → handler nghiệp vụ chỉ chạy 1 lần.
 * Thêm: nghiệp vụ lỗi sau khi đã đánh dấu inbox → dòng inbox rollback theo → lần retry vẫn xử lý được.
 */
@SpringBootTest(classes = {MessagingTestApplication.class, InboxIntegrationTest.Listener.class})
class InboxIntegrationTest extends MessagingIntegrationTest {

    static final String TOPIC = "it.inbox";
    static final String CONSUMER = "it:it.inbox";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EnvelopeCodec codec;

    @Autowired
    Listener listener;

    @Test
    void duplicateDeliveryIsHandledOnce() throws Exception {
        String json = envelope("normal");
        String eventId = codec.decode(json).eventId();

        // Giả lập Kafka giao lại: cùng một message (cùng eventId) tới 2 lần
        kafka.send(TOPIC, "order-1", json).get();
        kafka.send(TOPIC, "order-1", json).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> listener.deliveries(eventId) == 2);
        assertThat(listener.handled(eventId)).isEqualTo(1);
        assertThat(processedRows(eventId)).isEqualTo(1);
    }

    @Test
    void inboxMarkRollsBackWithFailedBusinessLogic() throws Exception {
        String json = envelope("fail-once");
        String eventId = codec.decode(json).eventId();

        kafka.send(TOPIC, "order-2", json).get();

        // Lần 1: đánh dấu inbox rồi nghiệp vụ ném lỗi → rollback cả hai → error handler retry sau 1s
        // Lần 2: inbox chưa có dòng nào → xử lý bình thường
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.handled(eventId) == 1);
        assertThat(listener.deliveries(eventId)).isEqualTo(2);
        assertThat(processedRows(eventId)).isEqualTo(1);
    }

    private String envelope(String mode) {
        return codec.encode(new EventEnvelope(UUID.randomUUID().toString(), "TestEvent", 1, Instant.now(),
            "it", "Test", "order", JsonNodeFactory.instance.objectNode().put("mode", mode)));
    }

    private int processedRows(String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
            .params(eventId, CONSUMER).query(Integer.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Listener {

        private final ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> handled = new ConcurrentHashMap<>();
        private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();

        @Autowired
        EnvelopeCodec codec;

        @Autowired
        InboxGuard inbox;

        @Autowired
        TransactionTemplate tx;

        @Bean
        NewTopic itInboxTopic() {
            return new NewTopic(TOPIC, 1, (short) 1);
        }

        /** Đúng mẫu docs/05 §5: inbox + nghiệp vụ trong cùng 1 transaction, exception được phép thoát ra. */
        @KafkaListener(topics = TOPIC, groupId = "it-inbox")
        void onMessage(String value) {
            EventEnvelope env = codec.decode(value);
            counter(deliveries, env.eventId()).incrementAndGet();
            tx.executeWithoutResult(status -> {
                if (!inbox.tryMarkProcessed(env.eventId(), CONSUMER)) {
                    return;   // đã xử lý rồi → bỏ qua, coi là thành công
                }
                if ("fail-once".equals(env.payload().get("mode").asString()) && failedOnce.add(env.eventId())) {
                    throw new IllegalStateException("transient failure, e.g. DB timeout");
                }
                counter(handled, env.eventId()).incrementAndGet();
            });
        }

        int deliveries(String eventId) {
            return counter(deliveries, eventId).get();
        }

        int handled(String eventId) {
            return counter(handled, eventId).get();
        }

        private static AtomicInteger counter(ConcurrentHashMap<String, AtomicInteger> map, String key) {
            return map.computeIfAbsent(key, k -> new AtomicInteger());
        }
    }
}
