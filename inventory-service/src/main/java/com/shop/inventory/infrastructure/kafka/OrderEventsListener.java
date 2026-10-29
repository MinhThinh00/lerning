package com.shop.inventory.infrastructure.kafka;

import com.shop.common.messaging.EnvelopeCodec;
import com.shop.common.messaging.EventEnvelope;
import com.shop.common.messaging.InvalidMessageException;
import com.shop.common.messaging.Topics;
import com.shop.inventory.application.messaging.InventoryEvents.LineItem;
import com.shop.inventory.application.messaging.InventoryEvents.OrderCancelled;
import com.shop.inventory.application.messaging.InventoryEvents.OrderCreated;
import com.shop.inventory.application.messaging.InventoryEvents.OrderPaid;
import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.ReservationLifecycleService;
import com.shop.inventory.application.reservation.ReservationService;
import com.shop.inventory.config.InventoryProperties;
import java.util.List;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Bước của inventory trong saga choreography (docs/00 §5):
 * <pre>
 *   order.created   → giữ hàng (strategy mặc định) → outbox inventory.reserved / inventory.failed
 *   order.paid      → chốt hàng đã giữ
 *   order.cancelled → nhả hàng (hoặc tombstone nếu đến trước order.created)
 * </pre>
 * Không catch exception ở đây: để DefaultErrorHandler của shop-common retry / đẩy DLT.
 * Hết hàng KHÔNG phải exception: đó là kết quả nghiệp vụ, phát inventory.failed rồi commit offset bình thường.
 */
@Component
class OrderEventsListener {

    private final EnvelopeCodec codec;
    private final ReservationService reservations;
    private final ReservationLifecycleService lifecycle;
    private final InventoryProperties props;

    OrderEventsListener(EnvelopeCodec codec, ReservationService reservations, ReservationLifecycleService lifecycle,
                        InventoryProperties props) {
        this.codec = codec;
        this.reservations = reservations;
        this.lifecycle = lifecycle;
        this.props = props;
        // Strategy cấu hình nhưng chưa cài (vd L5 trước Phase 8) → app không khởi động, thay vì DLT mọi đơn
        reservations.requireAvailable(props.defaultStrategy());
    }

    /**
     * Không cần Inbox: reservation theo orderId đã là cơ chế chống trùng (bước 1 + unique key của ReservationService),
     * và đường thất bại ghi ở transaction riêng nên không gộp chung được với dòng inbox.
     */
    @KafkaListener(topics = Topics.ORDER_CREATED)
    void onOrderCreated(String value) {
        EventEnvelope env = decode(value, "OrderCreated");
        OrderCreated evt = env.payloadAs(codec.mapper(), OrderCreated.class);
        reservations.reserve(requireOrderId(evt.orderId()), toItems(evt.items()), props.defaultStrategy());
    }

    @KafkaListener(topics = Topics.ORDER_PAID)
    void onOrderPaid(String value) {
        EventEnvelope env = decode(value, "OrderPaid");
        OrderPaid evt = env.payloadAs(codec.mapper(), OrderPaid.class);
        lifecycle.onOrderPaid(env.eventId(), requireOrderId(evt.orderId()));
    }

    @KafkaListener(topics = Topics.ORDER_CANCELLED)
    void onOrderCancelled(String value) {
        EventEnvelope env = decode(value, "OrderCancelled");
        OrderCancelled evt = env.payloadAs(codec.mapper(), OrderCancelled.class);
        lifecycle.onOrderCancelled(env.eventId(), requireOrderId(evt.orderId()), evt.items());
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

    private static List<Item> toItems(List<LineItem> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidMessageException("OrderCreated without items");
        }
        return items.stream().map(it -> new Item(it.productId(), it.quantity())).toList();
    }
}
