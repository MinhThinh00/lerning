package com.shop.common.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Chuyển envelope ↔ JSON string. Kafka chỉ chở String (StringSerializer), không dùng
 * JsonSerializer + header __TypeId__ (docs/05-MESSAGING.md §1.3).
 */
public class EnvelopeCodec {

    private final ObjectMapper mapper;

    public EnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(EventEnvelope envelope) {
        return mapper.writeValueAsString(envelope);
    }

    /**
     * Parse và kiểm tra các trường bắt buộc.
     * Mọi lỗi đều quy về {@link InvalidMessageException} để error handler nhận ra là lỗi vĩnh viễn.
     */
    public EventEnvelope decode(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("Empty message");
        }
        EventEnvelope env;
        try {
            env = mapper.readValue(json, EventEnvelope.class);
        } catch (JacksonException ex) {
            throw new InvalidMessageException("Malformed envelope: " + ex.getOriginalMessage(), ex);
        }
        if (env.eventId() == null || env.eventType() == null || env.eventVersion() < 1 || env.payload() == null) {
            throw new InvalidMessageException("Envelope missing eventId/eventType/eventVersion/payload");
        }
        return env;
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
