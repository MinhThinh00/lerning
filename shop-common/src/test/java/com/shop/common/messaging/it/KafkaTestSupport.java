package com.shop.common.messaging.it;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Đọc topic từ đầu bằng consumer group ngẫu nhiên, để test kiểm tra Kafka thật sự nhận được gì. */
public final class KafkaTestSupport {

    private KafkaTestSupport() {
    }

    public static void createTopics(String bootstrap, int partitions, String... topics) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            for (String topic : topics) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
                } catch (ExecutionException ex) {
                    if (!(ex.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** Đọc tới khi {@code done} thỏa hoặc hết {@code timeout}; trả về mọi record đã đọc. */
    public static List<ConsumerRecord<String, String>> readUntil(
            String bootstrap, String topic, Duration timeout, Predicate<List<ConsumerRecord<String, String>>> done) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(bootstrap))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && !done.test(records)) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    /** Đọc trong đúng {@code duration} (dùng để khẳng định "không có gì tới"). */
    public static List<ConsumerRecord<String, String>> readFor(String bootstrap, String topic, Duration duration) {
        return readUntil(bootstrap, topic, duration, r -> false);
    }

    public static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Properties consumerProps(String bootstrap) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }
}
