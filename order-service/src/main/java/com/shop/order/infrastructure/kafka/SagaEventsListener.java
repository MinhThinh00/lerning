package com.shop.order.infrastructure.kafka;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.InvalidMessageException;
import com.shop.common.messaging.Topics;
import com.shop.order.application.messaging.OrderEvents.InventoryFailed;
import com.shop.order.application.messaging.OrderEvents.InventoryReserved;
import com.shop.order.application.messaging.OrderEvents.PaymentFailed;
import com.shop.order.application.messaging.OrderEvents.PaymentSucceeded;
import com.shop.order.application.saga.OrderSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nhận kết quả từ inventory và payment. Không catch exception: DefaultErrorHandler của shop-common
 * retry lỗi tạm thời (vd xung đột @Version) và đẩy message hỏng sang DLT.
 */
@Component
class SagaEventsListener {

    private final EnvelopeCodec codec;
    private final OrderSagaService saga;

    SagaEventsListener(EnvelopeCodec codec, OrderSagaService saga) {
        this.codec = codec;
        this.saga = saga;
    }

    @KafkaListener(topics = Topics.INVENTORY_RESERVED)
    void onInventoryReserved(String value) {
        EventEnvelope env = decode(value, "InventoryReserved");
        InventoryReserved evt = env.payloadAs(codec.mapper(), InventoryReserved.class);
        saga.onInventoryReserved(env.eventId(), requireOrderId(evt.orderId()));
    }

    @KafkaListener(topics = Topics.INVENTORY_FAILED)
    void onInventoryFailed(String value) {
        EventEnvelope env = decode(value, "InventoryFailed");
        InventoryFailed evt = env.payloadAs(codec.mapper(), InventoryFailed.class);
        saga.onInventoryFailed(env.eventId(), requireOrderId(evt.orderId()), evt.reason() == null ? "INVENTORY_FAILED" : evt.reason());
    }

    @KafkaListener(topics = Topics.PAYMENT_SUCCESS)
    void onPaymentSucceeded(String value) {
        EventEnvelope env = decode(value, "PaymentSucceeded");
        PaymentSucceeded evt = env.payloadAs(codec.mapper(), PaymentSucceeded.class);
        saga.onPaymentSucceeded(env.eventId(), requireOrderId(evt.orderId()));
    }

    @KafkaListener(topics = Topics.PAYMENT_FAILED)
    void onPaymentFailed(String value) {
        EventEnvelope env = decode(value, "PaymentFailed");
        PaymentFailed evt = env.payloadAs(codec.mapper(), PaymentFailed.class);
        saga.onPaymentFailed(env.eventId(), requireOrderId(evt.orderId()));
    }

    private EventEnvelope decode(String value, String expectedType) {
        EventEnvelope env = codec.decode(value);
        if (!expectedType.equals(env.eventType())) {
            throw new InvalidMessageException("Expected " + expectedType + " but got " + env.eventType());
        }
        return env;
    }

    private static String requireOrderId(String orderId) {
        if (orderId == null || orderId.isBlank() || orderId.length() > 36) {
            throw new InvalidMessageException("Invalid orderId: " + orderId);
        }
        return orderId;
    }
}
