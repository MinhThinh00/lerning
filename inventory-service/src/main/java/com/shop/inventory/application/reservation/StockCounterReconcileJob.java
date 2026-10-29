package com.shop.inventory.application.reservation;

import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * So counter Redis với available của DB (docs/04 §5). Lệch ở 2 lần kiểm tra LIÊN TIẾP mới sửa (SET theo DB):
 * lệch 1 lần có thể chỉ là reserve L5 đang dở (Redis đã trừ, DB chưa commit), sửa ngay sẽ làm hỏng counter đúng.
 * Dấu "nghi lệch" lưu ở Redis (có TTL) để replica nào chạy lượt sau cũng thấy.
 *
 * Gauge {@code inventory.stock.drift}: số sản phẩm lệch ở lượt gần nhất.
 */
@Component
public class StockCounterReconcileJob {

    public static final String LOCK_NAME = "inventory.reconcile";

    private static final Logger log = LoggerFactory.getLogger(StockCounterReconcileJob.class);

    private final InventoryRepository inventory;
    private final StockCounter counter;
    private final AtomicInteger drifted = new AtomicInteger();

    public StockCounterReconcileJob(InventoryRepository inventory, StockCounter counter, MeterRegistry meters) {
        this.inventory = inventory;
        this.counter = counter;
        Gauge.builder("inventory.stock.drift", drifted, AtomicInteger::get)
            .description("Products whose Redis counter differed from DB available at the last reconcile")
            .register(meters);
    }

    @Scheduled(fixedDelayString = "${inventory.reconcile.interval:5m}", initialDelayString = "${inventory.reconcile.interval:5m}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Stock counter reconcile skipped: {}", e.getMessage());
        }
    }

    /** Một lượt, KHÔNG qua ShedLock (test gọi trực tiếp). @return số sản phẩm đã sửa */
    public int runOnce() {
        int drift = 0;
        int fixed = 0;
        for (Inventory inv : inventory.findAllByActiveTrue()) {
            long productId = inv.getProductId();
            OptionalLong redis = counter.current(productId);
            if (redis.isEmpty()) {
                counter.loadIfAbsent(productId, inv.getAvailable());
                continue;
            }
            if (redis.getAsLong() == inv.getAvailable()) {
                counter.clearSuspect(productId);
                continue;
            }
            drift++;
            if (!counter.markSuspect(productId)) {   // đã nghi từ lượt trước → lệch thật
                counter.overwrite(productId, inv.getAvailable());
                counter.clearSuspect(productId);
                fixed++;
                log.warn("Stock counter of product {} drifted (redis {}, db {}): reset to db", productId,
                    redis.getAsLong(), inv.getAvailable());
            }
        }
        drifted.set(drift);
        return fixed;
    }
}
