package com.shop.payment.infrastructure.kafka;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.InvalidMessageException;
import com.shop.common.messaging.Topics;
import com.shop.payment.application.PaymentEvents.OrderConfirmed;
import com.shop.payment.application.PaymentService;
import java.math.BigDecimal;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** order.confirmed → thu tiền. Không catch exception: để DefaultErrorHandler retry / DLT. */
@Component
class OrderConfirmedListener {

    private final EnvelopeCodec codec;
    private final PaymentService payments;

    OrderConfirmedListener(EnvelopeCodec codec, PaymentService payments) {
        this.codec = codec;
        this.payments = payments;
    }

    @KafkaListener(topics = Topics.ORDER_CONFIRMED)
    void onOrderConfirmed(String value) {
        EventEnvelope env = codec.decode(value);
        if (!"OrderConfirmed".equals(env.eventType())) {
            throw new InvalidMessageException("Expected OrderConfirmed but got " + env.eventType());
        }
        OrderConfirmed evt = env.payloadAs(codec.mapper(), OrderConfirmed.class);
        if (evt.orderId() == null || evt.totalAmount() == null || evt.currency() == null) {
            throw new InvalidMessageException("OrderConfirmed missing orderId/totalAmount/currency");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(evt.totalAmount());
        } catch (NumberFormatException ex) {
            throw new InvalidMessageException("Invalid totalAmount " + evt.totalAmount(), ex);
        }
        payments.onOrderConfirmed(evt.orderId(), amount, evt.currency());
    }
}
