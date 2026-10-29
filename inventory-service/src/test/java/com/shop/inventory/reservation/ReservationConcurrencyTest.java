package com.shop.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.ReservationService;
import com.shop.inventory.application.reservation.ReserveOutcome;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.Strategy;
import com.shop.inventory.it.InventoryIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DoD Phase 5 + Phase 8 (docs/10 T2): 200 thread cùng giữ 1 sản phẩm tồn 100, mỗi thread 1 cái, cùng xuất phát
 * (CountDownLatch) → KHÔNG strategy nào bán vượt, bất biến docs/04 §2 thỏa.
 *
 * L1, L2, L5 không có kiểu từ chối "vì tranh chấp" (trừ L2 chờ lock > 3s, không xảy ra ở mức tải này) → đúng 100
 * thành công. L3 (hết lượt retry → CONFLICT) và L4 (chờ lock quá lâu → LOCK_TIMEOUT) được phép từ chối oan khi
 * tranh chấp cao, đó chính là điểm yếu cần thấy khi so sánh, nhưng mỗi lần từ chối oan nhiều nhất làm mất 1 lượt
 * bán: thành công + từ chối oan ≥ tồn kho.
 *
 * Đối chứng: cùng kịch bản với L0 (đọc rồi ghi) → oversell. Nếu test này không bắt được L0
 * thì "L1 xanh" cũng chẳng chứng minh được gì.
 */
class ReservationConcurrencyTest extends InventoryIntegrationTest {

    private static final int STOCK = 100;
    private static final int THREADS = 200;

    @Autowired
    ReservationService reservations;

    @Autowired
    TransactionTemplate tx;

    @RepeatedTest(10)
    void l1_exactlyStockManySucceed_andInvariantHolds() throws Exception {
        assertNoOversell(Strategy.L1);
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void everyStrategy_neverOversells(Strategy strategy) throws Exception {
        assertNoOversell(strategy);
    }

    private void assertNoOversell(Strategy strategy) throws Exception {
        long productId = newProduct(STOCK);

        Map<String, Integer> outcomes = race(orderId ->
            tag(reservations.reserve(orderId, List.of(new Item(productId, 1)), strategy)));

        System.out.printf("[race] %s -> %s%n", strategy, outcomes);
        int success = outcomes.getOrDefault("success", 0);
        int contention = outcomes.getOrDefault("CONFLICT", 0) + outcomes.getOrDefault("LOCK_TIMEOUT", 0);
        assertThat(success).as("%s: never sells more than stock (%s)", strategy, outcomes).isLessThanOrEqualTo(STOCK);
        assertThat(outcomes.keySet()).as("only success / OUT_OF_STOCK / contention").allMatch(
            k -> List.of("success", "OUT_OF_STOCK", "CONFLICT", "LOCK_TIMEOUT").contains(k));
        if (strategy == Strategy.L3 || strategy == Strategy.L4) {
            assertThat(success + contention).as("%s: lost sales only through contention (%s)", strategy, outcomes)
                .isGreaterThanOrEqualTo(STOCK);
        } else {
            assertThat(success).as("%s (%s)", strategy, outcomes).isEqualTo(STOCK);
            assertThat(column(productId, "available")).isZero();
        }
        assertInvariant(productId, STOCK);
    }

    @Test
    void l0_readThenWrite_oversells_soTheTestDoesCatchRaces() throws Exception {
        long productId = newProduct(STOCK);
        L0ReadThenWriteStrategy l0 = new L0ReadThenWriteStrategy(jdbc, tx);

        int success = race(orderId -> l0.reserve(orderId, productId, 1) ? "success" : "rejected")
            .getOrDefault("success", 0);

        int reservedRows = jdbc.sql("SELECT COUNT(*) FROM inventory_reservation WHERE product_id = ? AND status = 'RESERVED'")
            .param(productId).query(Integer.class).single();
        int reservedColumn = column(productId, "reserved");
        // Bán vượt: số đơn "giữ được hàng" > tồn kho, trong khi cột reserved bị ghi đè nên nhỏ hơn số đơn thật
        assertThat(success).as("orders told they got stock").isGreaterThan(STOCK);
        assertThat(reservedRows).isEqualTo(success);
        assertThat(reservedColumn).as("lost updates").isLessThan(reservedRows);
    }

    private static String tag(ReserveOutcome outcome) {
        return outcome instanceof Rejected r ? r.reason().name() : outcome.tag();
    }

    /** Chạy THREADS lần reserve song song, mọi thread chờ ở vạch xuất phát rồi cùng chạy. @return số lần theo kết quả */
    private static Map<String, Integer> race(Function<String, String> reserveOne) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    outcomes.merge(reserveOne.apply(UUID.randomUUID().toString()), 1, Integer::sum);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        assertThat(errors.get()).as("unexpected exceptions").isZero();
        return outcomes;
    }
}
