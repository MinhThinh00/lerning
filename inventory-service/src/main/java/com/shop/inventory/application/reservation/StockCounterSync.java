package com.shop.inventory.application.reservation;

import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Giữ counter Redis bám theo DB (docs/04 §5):
 * <ul>
 *   <li>Khởi động: nạp mọi sản phẩm active bằng SET NX.</li>
 *   <li>Key thiếu lúc reserve (L5): nạp lại sản phẩm đó.</li>
 *   <li>DB đổi available ngoài đường L5 (nhả hàng, nhập kho, giữ hàng bằng L1–L4): cộng/trừ counter SAU COMMIT.
 *       Trước commit thì transaction có thể còn rollback, counter sẽ lệch theo chiều sai.</li>
 * </ul>
 * Tất cả best-effort: Redis lỗi không làm hỏng nghiệp vụ, StockCounterReconcileJob sửa sau.
 */
@Component
public class StockCounterSync {

    private static final Logger log = LoggerFactory.getLogger(StockCounterSync.class);

    private final StockCounter counter;
    private final InventoryRepository inventory;

    public StockCounterSync(StockCounter counter, InventoryRepository inventory) {
        this.counter = counter;
        this.inventory = inventory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        try {
            var all = inventory.findAllByActiveTrue();
            all.forEach(inv -> counter.loadIfAbsent(inv.getProductId(), inv.getAvailable()));
            log.info("Stock counters warmed up for {} products", all.size());
        } catch (RuntimeException e) {
            log.warn("Redis unavailable at startup, L5 goes straight to DB until counters load: {}", e.getMessage());
        }
    }

    /** Nạp counter cho các sản phẩm active trong danh sách (key chưa có). */
    public void load(Collection<Long> productIds) {
        inventory.findAllByProductIdIn(productIds).stream()
            .filter(Inventory::isActive)
            .forEach(inv -> counter.loadIfAbsent(inv.getProductId(), inv.getAvailable()));
    }

    /** available của DB vừa đổi delta trong transaction hiện tại → đổi counter khi (và chỉ khi) commit. */
    public void adjustAfterCommit(long productId, int delta) {
        afterCommit(() -> counter.adjust(productId, delta));
    }

    /** Dòng tồn kho vừa được mở → nạp counter sau commit. */
    public void loadAfterCommit(long productId, int available) {
        afterCommit(() -> {
            try {
                counter.loadIfAbsent(productId, available);
            } catch (RuntimeException e) {
                log.warn("Redis counter load failed for product {}: {}", productId, e.getMessage());
            }
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
