package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L2: {@code SELECT ... FOR UPDATE} rồi kiểm tra + trừ trong Java (docs/04 §4).
 *
 * Request thứ hai chờ ở câu SELECT tới khi request đầu commit, rồi đọc giá trị MỚI → không oversell.
 * Lock theo thứ tự productId (câu query ORDER BY) → 2 đơn nhiều sản phẩm không deadlock nhau.
 * Cái giá: giữ row lock + connection suốt transaction; tranh chấp cao thì request xếp hàng chờ, p99 tăng.
 * Chờ quá innodb_lock_wait_timeout (3s) → LOCK_TIMEOUT.
 */
@Component
public class L2PessimisticLockStrategy implements StockReservationStrategy {

    private final InventoryRepository inventory;
    private final ReservationRecorder recorder;
    private final TransactionTemplate tx;

    public L2PessimisticLockStrategy(InventoryRepository inventory, ReservationRecorder recorder, TransactionTemplate tx) {
        this.inventory = inventory;
        this.recorder = recorder;
        this.tx = tx;
    }

    @Override
    public Strategy id() {
        return Strategy.L2;
    }

    @Override
    public ReserveOutcome reserve(String orderId, List<Item> sortedItems) {
        try {
            return tx.execute(status -> {
                Map<Long, Inventory> locked = StockChecks.byProductId(inventory.lockAllActive(StockChecks.ids(sortedItems)));
                Rejected shortage = StockChecks.firstShortage(sortedItems, locked);
                if (shortage != null) {
                    status.setRollbackOnly();
                    return shortage;
                }
                sortedItems.forEach(it -> locked.get(it.productId()).reserve(it.quantity()));
                recorder.reserved(orderId, sortedItems, Strategy.L2);
                return new Reserved();
            });
        } catch (PessimisticLockingFailureException e) {   // lock wait timeout / deadlock
            return new Rejected(Reason.LOCK_TIMEOUT, sortedItems.get(0).productId());
        }
    }
}
