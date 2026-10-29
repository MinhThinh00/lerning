package com.shop.order.application.service;

import com.shop.common.messaging.Topics;
import com.shop.common.messaging.outbox.OutboxWriter;
import com.shop.order.application.messaging.OrderEvents.OrderCancelled;
import com.shop.order.application.messaging.OrderEvents.OrderConfirmed;
import com.shop.order.application.messaging.OrderEvents.OrderCreated;
import com.shop.order.application.messaging.OrderEvents.OrderPaid;
import com.shop.order.domain.model.Order;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Mỗi lần chuyển trạng thái thành công → 1 event qua outbox, trong CÙNG transaction với đơn (docs/07 §2).
 * Key = orderId: mọi event của 1 đơn vào cùng partition.
 *
 * Metric (docs/07 §7): order.created/confirmed/paid/cancelled{reason}, order.saga.duration{outcome}.
 * Ghi metric trước commit nên nếu transaction rollback sẽ đếm dư 1 lần; chấp nhận được cho mục đích quan sát.
 */
@Component
public class OrderEventPublisher {

    private static final String AGGREGATE = "Order";

    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public OrderEventPublisher(OutboxWriter outbox, MeterRegistry meters) {
        this.outbox = outbox;
        this.meters = meters;
    }

    public void created(Order o) {
        write(Topics.ORDER_CREATED, o, "OrderCreated", OrderCreated.from(o));
        meters.counter("order.created").increment();
    }

    public void confirmed(Order o) {
        write(Topics.ORDER_CONFIRMED, o, "OrderConfirmed", OrderConfirmed.from(o));
        meters.counter("order.confirmed").increment();
    }

    public void paid(Order o) {
        write(Topics.ORDER_PAID, o, "OrderPaid", OrderPaid.from(o));
        meters.counter("order.paid").increment();
        sagaFinished(o, "paid");
    }

    public void cancelled(Order o) {
        write(Topics.ORDER_CANCELLED, o, "OrderCancelled", OrderCancelled.from(o));
        meters.counter("order.cancelled", "reason", o.getCancelReason()).increment();
        sagaFinished(o, "cancelled");
    }

    public void refundRequired(Order o) {
        meters.counter("order.refund.required").increment();
    }

    private void write(String topic, Order o, String eventType, Object payload) {
        outbox.write(topic, AGGREGATE, o.getId(), o.getId(), eventType, payload);
    }

    private void sagaFinished(Order o, String outcome) {
        Timer.builder("order.saga.duration").tag("outcome", outcome).register(meters)
            .record(Duration.between(o.getCreatedAt(), Instant.now()));
    }
}
