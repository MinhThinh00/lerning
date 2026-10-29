package com.shop.common.messaging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.Topics;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * DoD Phase 3: message JSON hỏng → vào {@code <topic>.DLT} ngay, không retry.
 * Thêm 2 ca đối chứng (docs/10 T5):
 * - lỗi tạm thời → retry 3 lần (1s, 2s, 4s) rồi mới sang DLT;
 * - listener catch(Exception) rồi log (anti-pattern của selex) → message biến mất, DLT rỗng.
 */
@SpringBootTest(classes = {MessagingTestApplication.class, KafkaErrorHandlingTest.Listeners.class})
class KafkaErrorHandlingTest extends MessagingIntegrationTest {

    static final String STRICT_TOPIC = "it.errors.strict";
    static final String SWALLOW_TOPIC = "it.errors.swallow";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    Listeners listeners;

    @Test
    void malformedMessageGoesToDltImmediatelyWithoutRetry() throws Exception {
        String key = "bad-" + UUID.randomUUID();
        kafka.send(STRICT_TOPIC, key, "{not json").get();

        ConsumerRecord<String, String> dead = awaitInDlt(STRICT_TOPIC, key, Duration.ofSeconds(30));
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(KafkaTestSupport.header(dead, "kafka_dlt-exception-message")).contains("Malformed envelope");

        // Backoff đầu tiên là 1s: chờ quá mốc đó mà vẫn chỉ 1 lần → đúng là không retry
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> listeners.attempts(key) == 1);
    }

    @Test
    void transientFailureIsRetriedThreeTimesThenGoesToDlt() throws Exception {
        String key = "transient-" + UUID.randomUUID();
        kafka.send(STRICT_TOPIC, key, "TRANSIENT").get();

        awaitInDlt(STRICT_TOPIC, key, Duration.ofSeconds(40));
        assertThat(listeners.attempts(key)).isEqualTo(4);   // 1 lần đầu + 3 retry
    }

    /** Anti-pattern: nuốt exception → container coi là thành công, commit offset → message mất. */
    @Test
    void swallowingListenerLosesTheMessageSilently() throws Exception {
        String key = "swallow-" + UUID.randomUUID();
        kafka.send(SWALLOW_TOPIC, key, "TRANSIENT").get();

        await().atMost(Duration.ofSeconds(30)).until(() -> listeners.attempts(key) == 1);
        List<ConsumerRecord<String, String>> dlt =
            KafkaTestSupport.readFor(bootstrapServers(), Topics.dlt(SWALLOW_TOPIC), Duration.ofSeconds(8));

        assertThat(listeners.attempts(key)).as("no retry").isEqualTo(1);
        assertThat(dlt).as("nothing in DLT either").noneMatch(r -> key.equals(r.key()));
    }

    private static ConsumerRecord<String, String> awaitInDlt(String topic, String key, Duration timeout) {
        List<ConsumerRecord<String, String>> dlt = KafkaTestSupport.readUntil(bootstrapServers(), Topics.dlt(topic),
            timeout, records -> records.stream().anyMatch(r -> key.equals(r.key())));
        return dlt.stream().filter(r -> key.equals(r.key())).findFirst()
            .orElseThrow(() -> new AssertionError("no DLT record for key " + key));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Listeners {

        private static final Logger log = LoggerFactory.getLogger(Listeners.class);

        private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        @Autowired
        EnvelopeCodec codec;

        @Bean
        NewTopic strictTopic() {
            return new NewTopic(STRICT_TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic strictDlt() {
            return new NewTopic(Topics.dlt(STRICT_TOPIC), 1, (short) 1);
        }

        @Bean
        NewTopic swallowTopic() {
            return new NewTopic(SWALLOW_TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic swallowDlt() {
            return new NewTopic(Topics.dlt(SWALLOW_TOPIC), 1, (short) 1);
        }

        /** Cách đúng: để exception thoát ra, DefaultErrorHandler của shop-common lo retry/DLT. */
        @KafkaListener(topics = STRICT_TOPIC, groupId = "it-errors-strict")
        void strict(ConsumerRecord<String, String> rec) {
            count(rec.key());
            if ("TRANSIENT".equals(rec.value())) {
                throw new IllegalStateException("transient failure, e.g. DB timeout");
            }
            codec.decode(rec.value());   // JSON hỏng → InvalidMessageException (not-retryable)
        }

        /** Cách sai (selex KafkaService): catch-all rồi log. */
        @KafkaListener(topics = SWALLOW_TOPIC, groupId = "it-errors-swallow")
        void swallowing(ConsumerRecord<String, String> rec) {
            count(rec.key());
            try {
                throw new IllegalStateException("transient failure, e.g. DB timeout");
            } catch (Exception ex) {
                log.error("Failed to handle message, ignoring: {}", ex.getMessage());
            }
        }

        int attempts(String key) {
            return attempts.computeIfAbsent(key, k -> new AtomicInteger()).get();
        }

        private void count(String key) {
            attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }
    }
}
