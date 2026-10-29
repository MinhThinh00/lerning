package com.shop.common.messaging.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.outbox.OutboxWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DoD Phase 3: relay chạy ở 2 instance song song → mỗi event publish đúng 1 lần, đúng thứ tự.
 * Instance thứ hai là một ApplicationContext riêng (scheduler + relay riêng) trỏ cùng MySQL + Kafka,
 * y như 2 replica của một service.
 */
class OutboxRelayTwoInstancesTest extends MessagingIntegrationTest {

    record TestPayload(String key, int seq) {
    }

    private static final int KEYS = 5;
    private static final int EVENTS_PER_KEY = 60;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EnvelopeCodec codec;

    @Test
    void eachEventIsPublishedExactlyOnceAndInOrderPerKey() {
        String topic = "it.outbox.replicas." + UUID.randomUUID();
        KafkaTestSupport.createTopics(bootstrapServers(), 3, topic);

        try (ConfigurableApplicationContext secondInstance = new SpringApplicationBuilder(MessagingTestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(connectionProperties())
                .properties(Map.of("spring.liquibase.enabled", "false"))
                .run()) {

            // Ghi xen kẽ key trong lúc cả 2 relay đang poll
            List<String> ids = new ArrayList<>();
            for (int seq = 0; seq < EVENTS_PER_KEY; seq++) {
                for (int k = 0; k < KEYS; k++) {
                    String key = "order-" + k;
                    int s = seq;
                    ids.add(tx.execute(st -> outbox.write(topic, "Test", key, key, "TestEvent", new TestPayload(key, s))));
                }
            }

            await().atMost(Duration.ofSeconds(60))
                .until(() -> ids.stream().allMatch(id -> "PUBLISHED".equals(outboxStatus(id))));
        }

        // Mọi send đã được broker ack trước khi mark PUBLISHED → đọc hết topic là thấy cả bản trùng (nếu có)
        List<ConsumerRecord<String, String>> records =
            KafkaTestSupport.readFor(bootstrapServers(), topic, Duration.ofSeconds(10));

        List<String> eventIds = records.stream().map(r -> KafkaTestSupport.header(r, "eventId")).toList();
        assertThat(eventIds).hasSize(KEYS * EVENTS_PER_KEY).doesNotHaveDuplicates();

        // Thứ tự đọc trong 1 partition = thứ tự ghi; mọi event của 1 key nằm cùng partition
        Map<String, List<Integer>> seqByKey = records.stream().collect(Collectors.groupingBy(
            ConsumerRecord::key,
            Collectors.mapping(r -> codec.decode(r.value()).payloadAs(codec.mapper(), TestPayload.class).seq(),
                Collectors.toList())));
        assertThat(seqByKey).hasSize(KEYS);
        seqByKey.forEach((key, seqs) -> assertThat(seqs).as("order of %s", key).isSorted().hasSize(EVENTS_PER_KEY));
    }
}
