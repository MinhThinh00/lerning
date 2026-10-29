package com.shop.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.inventory.application.messaging.InventoryEvents.LineItem;
import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.L4DistributedLockStrategy;
import com.shop.inventory.application.reservation.ReservationLifecycleService;
import com.shop.inventory.application.reservation.ReservationService;
import com.shop.inventory.application.reservation.ReserveOutcome;
import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.application.reservation.StockCounter;
import com.shop.inventory.application.reservation.StockCounterReconcileJob;
import com.shop.inventory.application.reservation.Strategy;
import com.shop.inventory.application.stock.StockService;
import com.shop.inventory.it.InventoryIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** DoD Phase 8 cho phần dựa vào Redis: L4 mất lock vẫn không ghi đè; L5 chịu được key mất / counter lệch. */
class RedisStrategiesTest extends InventoryIntegrationTest {

    @Autowired
    ReservationService reservations;

    @Autowired
    ReservationLifecycleService lifecycle;

    @Autowired
    StockService stockService;

    @Autowired
    L4DistributedLockStrategy l4;

    @Autowired
    StockCounter counter;

    @Autowired
    StockCounterReconcileJob reconcile;

    @Autowired
    StringRedisTemplate redis;

    @AfterEach
    void clearHook() {
        l4.onAfterReadForTesting(null);
    }

    /**
     * A giữ lock và đã đọc tồn kho → Redis mất dữ liệu (giả lập failover sang replica chưa kịp nhận key lock)
     * → B lấy được lock, giữ hàng, commit → A ghi tiếp bằng dữ liệu cũ → @Version chặn, A bị CONFLICT.
     * Không có @Version: A ghi đè, mất phần B vừa giữ (lost update) → reserved sai.
     */
    @Test
    void l4_lockLostMidTransaction_versionFencingBlocksStaleWrite() {
        long p = newProduct(10);
        AtomicReference<ReserveOutcome> b = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        l4.onAfterReadForTesting(() -> {
            if (!once.compareAndSet(false, true)) {
                return;   // hook cũng chạy trong lượt của B
            }
            redis.execute((RedisCallback<Object>) c -> {
                c.serverCommands().flushAll();
                return null;
            });
            Thread other = new Thread(() -> b.set(reserve(Strategy.L4, p, 3)));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ReserveOutcome a = reserve(Strategy.L4, p, 2);

        assertThat(b.get()).isInstanceOf(Reserved.class);
        assertThat(a).isEqualTo(new Rejected(Reason.CONFLICT, p));
        assertThat(stock(p)).containsEntry("available", 7).containsEntry("reserved", 3);
        assertInvariant(p, 10);
    }

    @Test
    void l5_counterKeyDeleted_reloadsFromDbAndStaysCorrect() {
        long p = newProduct(5);
        assertThat(reserve(Strategy.L5, p, 1)).isInstanceOf(Reserved.class);
        assertThat(counter.current(p)).hasValue(4);

        redis.delete("inv:stock:" + p);

        assertThat(reserve(Strategy.L5, p, 2)).isInstanceOf(Reserved.class);
        assertThat(counter.current(p)).hasValue(2);
        assertThat(column(p, "available")).isEqualTo(2);
        assertInvariant(p, 5);
    }

    @Test
    void l5_counterBelowDb_rejectsWithoutTouchingDb_untilReconcileFixesIt() {
        long p = newProduct(5);
        assertThat(reserve(Strategy.L5, p, 1)).isInstanceOf(Reserved.class);
        counter.overwrite(p, 0);   // lệch: Redis nói hết hàng, DB còn 4

        assertThat(reserve(Strategy.L5, p, 1)).isEqualTo(new Rejected(Reason.OUT_OF_STOCK, p));
        assertThat(column(p, "available")).isEqualTo(4);

        reconcile.runOnce();   // lần 1: chỉ đánh dấu nghi (có thể chỉ là reserve đang dở)
        assertThat(counter.current(p)).hasValue(0);
        reconcile.runOnce();   // lần 2 vẫn lệch → sửa theo DB
        assertThat(counter.current(p)).hasValue(4);

        assertThat(reserve(Strategy.L5, p, 1)).isInstanceOf(Reserved.class);
        assertThat(counter.current(p)).hasValue(3);
        assertInvariant(p, 5);
    }

    @Test
    void l5_counterAboveDb_dbStillDecides_andCounterIsRestored() {
        long p = newProduct(4);
        counter.overwrite(p, 100);   // lệch: Redis nói còn 100

        assertThat(reserve(Strategy.L5, p, 5)).isEqualTo(new Rejected(Reason.OUT_OF_STOCK, p));
        assertThat(counter.current(p)).as("restored after DB said no").hasValue(100);
        assertThat(column(p, "available")).isEqualTo(4);
    }

    @Test
    void counterFollowsDb_onNonRedisReserve_release_andAdjust() {
        long p = newProduct(10);
        String order = UUID.randomUUID().toString();
        counter.loadIfAbsent(p, 10);

        assertThat(reservations.reserve(order, List.of(new Item(p, 3)), Strategy.L2)).isInstanceOf(Reserved.class);
        assertThat(counter.current(p)).as("L2 reserve").hasValue(7);

        lifecycle.onOrderCancelled(UUID.randomUUID().toString(), order, List.of(new LineItem(p, 3)));
        assertThat(counter.current(p)).as("release after commit").hasValue(10);

        stockService.adjust("test", p, 5, "restock");
        assertThat(counter.current(p)).as("restock after commit").hasValue(15);
        assertThat(column(p, "available")).isEqualTo(15);
    }

    private ReserveOutcome reserve(Strategy strategy, long productId, int qty) {
        return reservations.reserve(UUID.randomUUID().toString(), List.of(new Item(productId, qty)), strategy);
    }
}
