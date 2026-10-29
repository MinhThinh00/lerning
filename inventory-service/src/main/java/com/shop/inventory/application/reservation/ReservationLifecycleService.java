package com.shop.inventory.application.reservation;

import com.shop.common.messaging.inbox.InboxGuard;
import com.shop.inventory.application.messaging.InventoryEvents.LineItem;
import com.shop.inventory.domain.model.Reservation;
import com.shop.inventory.domain.model.Reservation.Status;
import com.shop.inventory.domain.repository.InventoryRepository;
import com.shop.inventory.domain.repository.ReservationRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chốt / nhả hàng đã giữ theo kết quả của đơn (docs/04 §6). Mỗi event xử lý trong 1 transaction có Inbox:
 * dòng processed_event, đổi trạng thái reservation và cập nhật tồn kho cùng commit hoặc cùng rollback.
 *
 * Chỉ dựa trên reservation của ĐÚNG orderId: không bao giờ nhả "đại" theo số reserved còn lại
 * (bug v1: có thể nhả hàng đang giữ cho đơn khác).
 */
@Service
public class ReservationLifecycleService {

    static final String PAID_CONSUMER = "inventory:order.paid";
    static final String CANCELLED_CONSUMER = "inventory:order.cancelled";

    private static final Logger log = LoggerFactory.getLogger(ReservationLifecycleService.class);

    private final ReservationRepository reservations;
    private final InventoryRepository inventory;
    private final InboxGuard inbox;
    private final StockCounterSync counterSync;

    public ReservationLifecycleService(ReservationRepository reservations, InventoryRepository inventory, InboxGuard inbox,
                                       StockCounterSync counterSync) {
        this.reservations = reservations;
        this.inventory = inventory;
        this.inbox = inbox;
        this.counterSync = counterSync;
    }

    /** order.paid: RESERVED → COMMITTED; reserved -= q, sold += q. */
    @Transactional
    public void onOrderPaid(String eventId, String orderId) {
        if (!inbox.tryMarkProcessed(eventId, PAID_CONSUMER)) {
            return;
        }
        List<Reservation> rows = reservations.findByOrderId(orderId);
        if (rows.stream().noneMatch(r -> r.getStatus() == Status.RESERVED)) {
            // Trong luồng đúng không xảy ra: chỉ đơn đã giữ hàng mới tới được bước thanh toán
            log.error("order.paid for {} but no RESERVED reservation (found {})", orderId, statuses(rows));
            return;
        }
        for (Reservation r : rows) {
            if (r.getStatus() == Status.RESERVED) {
                r.commit();
                requireOneRow(inventory.commitReserved(r.getProductId(), r.getQuantity()), r);
            }
        }
    }

    /**
     * order.cancelled:
     * - RESERVED → RELEASED, trả hàng về available;
     * - FAILED (chưa từng trừ kho) → không làm gì;
     * - chưa có reservation (cancelled đến TRƯỚC created) → ghi tombstone RELEASED để order.created đến sau bỏ qua.
     */
    @Transactional
    public void onOrderCancelled(String eventId, String orderId, List<LineItem> items) {
        if (!inbox.tryMarkProcessed(eventId, CANCELLED_CONSUMER)) {
            return;
        }
        List<Reservation> rows = reservations.findByOrderId(orderId);
        if (rows.isEmpty()) {
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("order.cancelled without items for unknown order " + orderId);
            }
            items.forEach(it -> reservations.save(Reservation.tombstone(orderId, it.productId(), it.quantity())));
            // Nếu order.created đang giữ hàng song song: 1 trong 2 INSERT vỡ unique → bên vỡ rollback;
            // nếu là bên này thì error handler retry, lần sau thấy RESERVED và nhả bình thường.
            reservations.flush();
            log.info("order.cancelled arrived before order.created for {}: tombstones written", orderId);
            return;
        }
        for (Reservation r : rows) {
            switch (r.getStatus()) {
                case RESERVED -> {
                    r.release();
                    requireOneRow(inventory.releaseReserved(r.getProductId(), r.getQuantity()), r);
                    counterSync.adjustAfterCommit(r.getProductId(), r.getQuantity());   // hàng về available → counter Redis cũng vậy
                }
                case COMMITTED -> log.warn("order.cancelled for paid order {} (product {}): refund flow out of scope",
                    orderId, r.getProductId());
                case FAILED, RELEASED -> { /* chưa từng giữ hàng / đã nhả: không làm gì */ }
            }
        }
    }

    /** 0 dòng = reserved trong DB nhỏ hơn reservation → bất biến đã vỡ ở đâu đó. Rollback, để DLT báo động. */
    private static void requireOneRow(int updated, Reservation r) {
        if (updated != 1) {
            throw new IllegalStateException("Inventory invariant broken for product " + r.getProductId()
                + ": cannot apply reservation " + r.getId() + " (qty " + r.getQuantity() + ")");
        }
    }

    private static List<Status> statuses(List<Reservation> rows) {
        return rows.stream().map(Reservation::getStatus).toList();
    }
}
