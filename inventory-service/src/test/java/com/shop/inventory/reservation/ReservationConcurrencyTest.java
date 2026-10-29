package com.shop.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.ReservationService;
import com.shop.inventory.application.reservation.ReserveOutcome;
import com.shop.inventory.application.reservation.Strategy;
import com.shop.inventory.it.InventoryIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DoD Phase 5 (docs/10 T2): 200 thread cùng giữ 1 sản phẩm tồn 100, mỗi thread 1 cái, cùng xuất phát
 * (CountDownLatch) → đúng 100 thành công, bất biến docs/04 §2 thỏa.
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
        long productId = newProduct(STOCK);

        int success = race(orderId -> reservations.reserve(orderId, List.of(new Item(productId, 1)), Strategy.L1)
            instanceof ReserveOutcome.Reserved);

        assertThat(success).isEqualTo(STOCK);
        assertThat(column(productId, "available")).isZero();
        assertInvariant(productId, STOCK);
    }

    @Test
    void l0_readThenWrite_oversells_soTheTestDoesCatchRaces() throws Exception {
        long productId = newProduct(STOCK);
        L0ReadThenWriteStrategy l0 = new L0ReadThenWriteStrategy(jdbc, tx);

        int success = race(orderId -> l0.reserve(orderId, productId, 1));

        int reservedRows = jdbc.sql("SELECT COUNT(*) FROM inventory_reservation WHERE product_id = ? AND status = 'RESERVED'")
            .param(productId).query(Integer.class).single();
        int reservedColumn = column(productId, "reserved");
        // Bán vượt: số đơn "giữ được hàng" > tồn kho, trong khi cột reserved bị ghi đè nên nhỏ hơn số đơn thật
        assertThat(success).as("orders told they got stock").isGreaterThan(STOCK);
        assertThat(reservedRows).isEqualTo(success);
        assertThat(reservedColumn).as("lost updates").isLessThan(reservedRows);
    }

    /** Chạy THREADS lần reserve song song, mọi thread chờ ở vạch xuất phát rồi cùng chạy. @return số lần thành công */
    private static int race(Predicate<String> reserveOne) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (reserveOne.test(UUID.randomUUID().toString())) {
                        success.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        assertThat(errors.get()).as("unexpected exceptions").isZero();
        return success.get();
    }
}
