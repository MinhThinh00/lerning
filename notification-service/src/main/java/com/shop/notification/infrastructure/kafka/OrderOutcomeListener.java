package com.shop.notification.infrastructure.kafka;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.InvalidMessageException;
import com.shop.common.messaging.Topics;
import com.shop.notification.application.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** order.paid / order.cancelled → thông báo cho khách. Không catch exception: để DefaultErrorHandler retry / DLT. */
@Component
class OrderOutcomeListener {

    record OrderPaid(String orderId, String userId, String totalAmount, String currency) {
    }

    record OrderCancelled(String orderId, String userId, String reason) {
    }

    private final EnvelopeCodec codec;
    private final NotificationService notifications;

    OrderOutcomeListener(EnvelopeCodec codec, NotificationService notifications) {
        this.codec = codec;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.ORDER_PAID)
    void onOrderPaid(String value) {
        EventEnvelope env = decode(value, "OrderPaid");
        OrderPaid evt = env.payloadAs(codec.mapper(), OrderPaid.class);
        requireIds(evt.orderId(), evt.userId());
        notifications.orderPaid(env.eventId(), evt.orderId(), evt.userId(), evt.totalAmount(), evt.currency());
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    void onOrderCancelled(String value) {
        EventEnvelope env = decode(value, "OrderCancelled");
        OrderCancelled evt = env.payloadAs(codec.mapper(), OrderCancelled.class);
        requireIds(evt.orderId(), evt.userId());
        notifications.orderCancelled(env.eventId(), evt.orderId(), evt.userId(), evt.reason());
    }

    private EventEnvelope decode(String value, String expectedType) {
        EventEnvelope env = codec.decode(value);
        if (!expectedType.equals(env.eventType())) {
            throw new InvalidMessageException("Expected " + expectedType + " but got " + env.eventType());
        }
        return env;
    }

    private static void requireIds(String orderId, String userId) {
        if (orderId == null || userId == null) {
            throw new InvalidMessageException("Event missing orderId/userId");
        }
    }
}
