package com.shop.inventory.application.reservation;

import com.shop.common.messaging.Topics;
import com.shop.common.messaging.outbox.OutboxWriter;
import com.shop.inventory.application.messaging.InventoryEvents.InventoryFailed;
import com.shop.inventory.application.messaging.InventoryEvents.InventoryReserved;
import com.shop.inventory.application.messaging.InventoryEvents.LineItem;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.domain.model.Reservation;
import com.shop.inventory.domain.repository.ReservationRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Ghi reservation + event outbox, dùng chung cho mọi strategy. Luôn chạy TRONG transaction của người gọi:
 * reservation, thay đổi tồn kho và event cùng commit hoặc cùng rollback (OutboxWriter tự kiểm tra có transaction).
 */
@Component
public class ReservationRecorder {

    static final String AGGREGATE = "InventoryReservation";

    private final ReservationRepository reservations;
    private final OutboxWriter outbox;

    public ReservationRecorder(ReservationRepository reservations, OutboxWriter outbox) {
        this.reservations = reservations;
        this.outbox = outbox;
    }

    public void reserved(String orderId, List<Item> items, Strategy strategy) {
        items.forEach(it -> reservations.save(Reservation.reserved(orderId, it.productId(), it.quantity(), strategy.name())));
        reservations.flush();   // trùng (order_id, product_id) → vỡ ngay tại đây
        outbox.write(Topics.INVENTORY_RESERVED, AGGREGATE, orderId, orderId, "InventoryReserved",
            new InventoryReserved(orderId, lineItems(items), strategy.name()));
    }

    public void failed(String orderId, List<Item> items, Rejected rejected, Strategy strategy) {
        String reason = rejected.reason().name();
        items.forEach(it -> reservations.save(
            Reservation.failed(orderId, it.productId(), it.quantity(), strategy.name(), reason)));
        reservations.flush();
        outbox.write(Topics.INVENTORY_FAILED, AGGREGATE, orderId, orderId, "InventoryFailed",
            new InventoryFailed(orderId, reason, rejected.productId()));
    }

    private static List<LineItem> lineItems(List<Item> items) {
        return items.stream().map(it -> new LineItem(it.productId(), it.quantity())).toList();
    }
}
