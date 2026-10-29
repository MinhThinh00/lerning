package com.shop.common.messaging.kafka;

import com.shop.common.messaging.DomainRuleViolationException;
import com.shop.common.messaging.InvalidMessageException;
import com.shop.common.messaging.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * Error handler mặc định cho mọi @KafkaListener (docs/05-MESSAGING.md §7).
 * Spring Boot tự gắn bean CommonErrorHandler vào listener container factory.
 *
 * Listener KHÔNG được catch(Exception) rồi log: offset vẫn commit → message mất, DLT rỗng, không ai biết.
 * Cứ để exception thoát ra, handler này quyết định:
 * - lỗi tạm thời (DB/Redis timeout...): retry 3 lần, backoff 1s → 2s → 4s, hết lượt thì sang {@code <topic>.DLT};
 * - lỗi vĩnh viễn (message hỏng, vi phạm nghiệp vụ): sang DLT ngay, không retry.
 */
public final class KafkaErrorHandling {

    private KafkaErrorHandling() {
    }

    public static DefaultErrorHandler defaultErrorHandler(KafkaOperations<?, ?> template) {
        // partition -1: để producer tự chọn partition của DLT.
        // v1 giữ nguyên số partition gốc → lỗi khi DLT có ít partition hơn topic gốc.
        var recoverer = new DeadLetterPublishingRecoverer(template,
            (rec, ex) -> new TopicPartition(Topics.dlt(rec.topic()), -1));

        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        // Classifier duyệt cả chuỗi cause, nên exception bị bọc trong ListenerExecutionFailedException vẫn nhận ra.
        handler.addNotRetryableExceptions(
            InvalidMessageException.class,
            JacksonException.class,
            IllegalArgumentException.class,
            DomainRuleViolationException.class);
        return handler;
    }
}
