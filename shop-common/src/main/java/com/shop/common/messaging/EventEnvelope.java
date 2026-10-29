package com.shop.common.messaging;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Vỏ chung của mọi event trên Kafka (docs/05-MESSAGING.md §3). Value của record = JSON của envelope.
 *
 * Payload để dạng JsonNode, KHÔNG generic {@code <T>}: producer và consumer không phải chia sẻ
 * class Java (v1 dùng DomainEvent<T> → bị erase thành LinkedHashMap ở consumer).
 * Consumer tự chọn class của mình qua {@link #payloadAs}.
 *
 * @param eventId       UUID, dùng để chống trùng ở consumer (Inbox)
 * @param eventVersion  tăng khi đổi schema payload; consumer phải đọc được cả bản cũ trong giai đoạn chuyển
 * @param source        spring.application.name của service phát
 * @param aggregateId   vd orderId
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String source,
    String aggregateType,
    String aggregateId,
    JsonNode payload) {

    public <T> T payloadAs(ObjectMapper mapper, Class<T> type) {
        return mapper.convertValue(payload, type);
    }
}
