package com.shop.common.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Đọc outbox_event PENDING theo thứ tự id và publish lên Kafka (docs/05-MESSAGING.md §4.3).
 *
 * - ShedLock: N replica thì mỗi lượt chỉ 1 relay chạy → không publish song song, giữ thứ tự.
 *   lockAtMostFor phải lớn hơn thời gian 1 lượt chạy dài nhất (batch × sendTimeout khi lỗi = 1 × 40s, vì gặp lỗi là dừng).
 *   Nếu lượt chạy vượt lockAtMostFor, lock hết hạn và replica khác chạy chồng lên → gửi trùng/sai thứ tự.
 * - Không @Transactional: mỗi markPublished là 1 UPDATE autocommit. Crash giữa send và markPublished
 *   → event gửi lại lượt sau (at-least-once), Inbox phía consumer lo phần trùng.
 * - Gặp lỗi là dừng cả batch: event sau không được vượt event trước.
 */
public class OutboxRelay {

    public static final String LOCK_NAME = "outbox.relay";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, OutboxProperties props) {
        this.repository = repository;
        this.kafka = kafka;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${shop.outbox.poll-interval-ms:500}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
    public void relay() {
        relayBatch();
    }

    /** Một lượt relay, KHÔNG có lock (test gọi trực tiếp). @return số event đã publish */
    public int relayBatch() {
        List<OutboxEvent> batch = repository.findPending(props.batchSize());
        int published = 0;
        for (OutboxEvent e : batch) {
            try {
                kafka.send(toRecord(e)).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                repository.markPublished(e.id());
                published++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return published;
            } catch (Exception ex) {
                String error = truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.warn("Outbox publish failed, stop batch: id={} eventId={} topic={} attempts={} error={}",
                    e.id(), e.eventId(), e.topic(), e.attempts() + 1, error);
                repository.markAttemptFailed(e.id(), error);
                break;
            }
        }
        return published;
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent e) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(e.topic(), e.messageKey(), e.payload());
        rec.headers().add("eventId", e.eventId().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("eventType", e.eventType().getBytes(StandardCharsets.UTF_8));
        if (e.traceParent() != null) {
            rec.headers().add("traceparent", e.traceParent().getBytes(StandardCharsets.UTF_8));
        }
        return rec;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }
}
