package com.shop.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class EnvelopeCodecTest {

    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());

    record OrderPaid(String orderId, int quantity) {
    }

    @Test
    void roundTripsEnvelopeAndPayload() {
        EventEnvelope env = new EventEnvelope("e-1", "OrderPaid", 1, Instant.parse("2026-10-12T08:15:30.123Z"),
            "order-service", "Order", "o-1", JsonNodeFactory.instance.objectNode().put("orderId", "o-1").put("quantity", 2));

        String json = codec.encode(env);
        EventEnvelope decoded = codec.decode(json);

        assertThat(json).contains("\"occurredAt\":\"2026-10-12T08:15:30.123Z\"");
        assertThat(decoded).isEqualTo(env);
        assertThat(decoded.payloadAs(codec.mapper(), OrderPaid.class)).isEqualTo(new OrderPaid("o-1", 2));
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        String json = """
            {"eventId":"e-1","eventType":"OrderPaid","eventVersion":2,"occurredAt":"2026-10-12T08:15:30Z",
             "source":"order-service","aggregateType":"Order","aggregateId":"o-1","payload":{},"newField":true}
            """;
        assertThat(codec.decode(json).eventVersion()).isEqualTo(2);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{not json")).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> codec.decode("")).isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void rejectsEnvelopeMissingRequiredFields() {
        assertThatThrownBy(() -> codec.decode("{\"eventType\":\"X\",\"eventVersion\":1,\"payload\":{}}"))
            .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> codec.decode("{\"eventId\":\"e\",\"eventType\":\"X\",\"payload\":{}}"))
            .as("eventVersion missing → 0").isInstanceOf(InvalidMessageException.class);
    }
}
