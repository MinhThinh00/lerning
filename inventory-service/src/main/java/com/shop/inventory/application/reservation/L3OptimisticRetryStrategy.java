package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.config.InventoryProperties;
import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L3: đọc không lock → kiểm tra → ghi {@code UPDATE ... WHERE id = ? AND version = ?} (@Version) → version đã đổi
 * thì làm lại cả transaction (docs/04 §4).
 *
 * Vòng retry nằm NGOÀI transaction: lỗi version xảy ra lúc flush/commit, lúc đó transaction đã hỏng, phải mở
 * transaction mới đọc lại. Không dùng @Retryable + @Transactional trên cùng method (thứ tự 2 proxy không đảm bảo).
 * Backoff có jitter để các request đụng nhau không cùng thử lại đúng 1 thời điểm.
 *
 * Điểm yếu: sản phẩm hot → nhiều request đụng version, retry dồn dập, hết lượt thử thì CONFLICT dù còn hàng.
 */
@Component
public class L3OptimisticRetryStrategy implements StockReservationStrategy {

    private final InventoryRepository inventory;
    private final ReservationRecorder recorder;
    private final TransactionTemplate tx;
    private final InventoryProperties props;

    public L3OptimisticRetryStrategy(InventoryRepository inventory, ReservationRecorder recorder, TransactionTemplate tx,
                                     InventoryProperties props) {
        this.inventory = inventory;
        this.recorder = recorder;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public Strategy id() {
        return Strategy.L3;
    }

    @Override
    public ReserveOutcome reserve(String orderId, List<Item> sortedItems) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    Map<Long, Inventory> stock = StockChecks.byProductId(inventory.findAllByProductIdIn(StockChecks.ids(sortedItems)));
                    Rejected shortage = StockChecks.firstShortage(sortedItems, stock);
                    if (shortage != null) {
                        status.setRollbackOnly();
                        return shortage;
                    }
                    sortedItems.forEach(it -> stock.get(it.productId()).reserve(it.quantity()));
                    recorder.reserved(orderId, sortedItems, Strategy.L3);   // flush → UPDATE ... AND version = ?
                    return new Reserved();
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= props.l3MaxAttempts()) {
                    return new Rejected(Reason.CONFLICT, sortedItems.get(0).productId());
                }
                backoff(attempt);
            }
        }
    }

    /** 5ms · 2^attempt, trần 100ms, cộng jitter ngẫu nhiên. */
    private static void backoff(int attempt) {
        long base = Math.min(100, 5L << Math.min(attempt, 5));
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(base + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying reservation", ie);
        }
    }
}
