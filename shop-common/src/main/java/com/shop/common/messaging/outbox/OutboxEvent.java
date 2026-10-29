package com.shop.common.messaging.outbox;

/**
 * Một dòng PENDING trong outbox_event, đủ để relay dựng ProducerRecord.
 *
 * @param id      AUTO_INCREMENT, chính là thứ tự publish
 * @param payload envelope JSON đầy đủ, gửi nguyên văn làm value
 */
public record OutboxEvent(
    long id,
    String eventId,
    String eventType,
    String topic,
    String messageKey,
    String payload,
    String traceParent,
    int attempts) {
}
