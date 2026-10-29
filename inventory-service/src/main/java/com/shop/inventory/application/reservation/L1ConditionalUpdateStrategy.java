package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1: {@code UPDATE ... SET available = available - q WHERE product_id = ? AND available >= q} (docs/04 §4).
 *
 * Kiểm tra và trừ nằm trong 1 câu lệnh: MySQL giữ row lock suốt câu UPDATE, request thứ hai phải chờ rồi
 * đánh giá lại điều kiện trên giá trị MỚI. Không có khoảng hở "đọc rồi mới ghi" như L0 (xem test) →
 * không oversell, kể cả khi N replica cùng chạy (mọi replica dùng chung DB).
 */
@Component
public class L1ConditionalUpdateStrategy implements StockReservationStrategy {

    private final InventoryRepository inventory;
    private final ReservationRecorder recorder;
    private final TransactionTemplate tx;

    public L1ConditionalUpdateStrategy(InventoryRepository inventory, ReservationRecorder recorder, TransactionTemplate tx) {
        this.inventory = inventory;
        this.recorder = recorder;
        this.tx = tx;
    }

    @Override
    public Strategy id() {
        return Strategy.L1;
    }

    @Override
    public ReserveOutcome reserve(String orderId, List<Item> sortedItems) {
        return tx.execute(status -> {
            for (Item it : sortedItems) {
                if (inventory.decreaseIfAvailable(it.productId(), it.quantity()) == 0) {
                    status.setRollbackOnly();   // hoàn tác các item đã trừ trước đó trong đơn
                    return new Rejected(reasonFor(it.productId()), it.productId());
                }
            }
            recorder.reserved(orderId, sortedItems, Strategy.L1);
            return new Reserved();
        });
    }

    /** UPDATE trả 0 có thể vì hết hàng hoặc vì không có dòng tồn kho (ngừng bán / chưa nhập kho). */
    private ReserveOutcome.Reason reasonFor(long productId) {
        return inventory.findByProductId(productId).filter(inv -> inv.isActive()).isPresent()
            ? ReserveOutcome.Reason.OUT_OF_STOCK
            : ReserveOutcome.Reason.NOT_FOUND;
    }
}
