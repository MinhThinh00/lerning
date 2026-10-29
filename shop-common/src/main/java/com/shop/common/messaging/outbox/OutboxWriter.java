package com.shop.common.messaging.outbox;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ghi event vào outbox_event, TRONG transaction nghiệp vụ đang chạy (docs/05-MESSAGING.md §4.2).
 * Rollback nghiệp vụ → dòng outbox rollback theo → không có event "ma".
 *
 * <pre>
 * &#64;Transactional
 * public OrderResponse createOrder(...) {
 *     orders.save(order);
 *     outbox.write(Topics.ORDER_CREATED, "Order", order.getId(), order.getId(), "OrderCreated", OrderCreated.from(order));
 * }
 * </pre>
 */
public class OutboxWriter {

    private final OutboxRepository repository;
    private final EnvelopeCodec codec;
    private final String source;

    public OutboxWriter(OutboxRepository repository, EnvelopeCodec codec, String source) {
        this.repository = repository;
        this.codec = codec;
        this.source = source;
    }

    /** Ghi event version 1. @return eventId vừa sinh */
    public String write(String topic, String aggregateType, String aggregateId, String messageKey,
                        String eventType, Object payload) {
        return write(topic, aggregateType, aggregateId, messageKey, eventType, 1, payload);
    }

    /**
     * Tương đương {@code @Transactional(propagation = MANDATORY)}, nhưng kiểm tra tường minh thay vì dựa vào proxy:
     * gọi ngoài transaction là lỗi lập trình (event sẽ được commit tách rời khỏi dữ liệu nghiệp vụ) → ném ngay.
     */
    public String write(String topic, String aggregateType, String aggregateId, String messageKey,
                        String eventType, int eventVersion, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                "OutboxWriter.write must run inside the business transaction (@Transactional)");
        }
        String eventId = UUID.randomUUID().toString();
        EventEnvelope envelope = new EventEnvelope(
            eventId, eventType, eventVersion, Instant.now(), source, aggregateType, aggregateId,
            codec.mapper().valueToTree(payload));

        // trace_parent: Phase 7 (observability) sẽ lấy traceparent của span hiện tại để relay nối trace.
        repository.insert(eventId, aggregateType, aggregateId, eventType, topic, messageKey,
            codec.encode(envelope), null);
        return eventId;
    }
}
