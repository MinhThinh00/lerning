package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.config.InventoryProperties;
import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L4: Redisson lock quanh read-modify-write (docs/04 §4). Các điểm đã sửa so với v1:
 * <ul>
 *   <li><b>MultiLock theo thứ tự productId</b> cho đơn nhiều sản phẩm (items đã sort) → không deadlock.</li>
 *   <li><b>leaseTime = -1 → watchdog</b> tự gia hạn lock khi transaction chạy lâu. Lease cố định 5s của v1:
 *       transaction quá 5s là mất lock giữa chừng mà không ai biết.</li>
 *   <li><b>Unlock SAU commit</b>: tx.execute chỉ trả về khi đã commit. Unlock trước commit thì replica khác lấy
 *       được lock và đọc phải dữ liệu chưa commit → oversell.</li>
 *   <li><b>@Version làm fencing token</b>: Redis Sentinel replicate bất đồng bộ, failover có thể làm mất lock
 *       (hoặc GC pause dài hơn lease). 2 bên cùng tưởng mình giữ lock → bên ghi sau đụng version → CONFLICT,
 *       không ghi đè.</li>
 * </ul>
 * Lock chỉ đúng khi MỌI writer đều đi qua nó; ở đây L1/L2/L5 không lấy lock, nên chính @Version mới là thứ giữ
 * đúng khi chạy xen kẽ. Distributed lock thật sự cần khi vùng cần bảo vệ không nằm gọn trong 1 transaction DB.
 */
@Component
public class L4DistributedLockStrategy implements StockReservationStrategy {

    static final String LOCK_PREFIX = "inv:lock:";

    private static final Logger log = LoggerFactory.getLogger(L4DistributedLockStrategy.class);

    private final RedissonClient redisson;
    private final InventoryRepository inventory;
    private final ReservationRecorder recorder;
    private final TransactionTemplate tx;
    private final InventoryProperties props;

    /** Điểm chen cho test mất lock (đã đọc, chưa ghi). Production luôn là no-op. */
    private volatile Runnable afterRead = () -> { };

    public L4DistributedLockStrategy(RedissonClient redisson, InventoryRepository inventory, ReservationRecorder recorder,
                                     TransactionTemplate tx, InventoryProperties props) {
        this.redisson = redisson;
        this.inventory = inventory;
        this.recorder = recorder;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public Strategy id() {
        return Strategy.L4;
    }

    @Override
    public ReserveOutcome reserve(String orderId, List<Item> sortedItems) {
        RLock lock = redisson.getMultiLock(sortedItems.stream()
            .map(it -> redisson.getLock(LOCK_PREFIX + it.productId()))
            .toArray(RLock[]::new));
        boolean acquired;
        try {
            acquired = lock.tryLock(props.l4Wait().toMillis(), -1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Rejected(Reason.LOCK_TIMEOUT, sortedItems.get(0).productId());
        }
        if (!acquired) {
            return new Rejected(Reason.LOCK_TIMEOUT, sortedItems.get(0).productId());
        }
        try {
            return tx.execute(status -> {
                Map<Long, Inventory> stock = StockChecks.byProductId(inventory.findAllByProductIdIn(StockChecks.ids(sortedItems)));
                afterRead.run();
                Rejected shortage = StockChecks.firstShortage(sortedItems, stock);
                if (shortage != null) {
                    status.setRollbackOnly();
                    return shortage;
                }
                sortedItems.forEach(it -> stock.get(it.productId()).reserve(it.quantity()));
                recorder.reserved(orderId, sortedItems, Strategy.L4);
                return new Reserved();
            });
        } catch (OptimisticLockingFailureException e) {
            // Lock đã mất (failover / pause) và writer khác đã ghi → fencing bằng @Version chặn lại
            log.warn("Order {}: stale write blocked by @Version while holding L4 lock (lock lost?)", orderId);
            return new Rejected(Reason.CONFLICT, sortedItems.get(0).productId());
        } finally {
            unlock(lock, orderId);
        }
    }

    private static void unlock(RLock lock, String orderId) {
        try {
            lock.unlock();
        } catch (RuntimeException e) {
            // IllegalMonitorStateException: lock không còn là của mình (đã mất); RedisException: Redis chết.
            // Watchdog ngừng gia hạn khi thread kết thúc nên lock (nếu còn) sẽ tự hết hạn.
            log.warn("Order {}: could not release L4 lock: {}", orderId, e.toString());
        }
    }

    /** Chỉ dành cho test: chạy {@code hook} sau khi đọc tồn kho, trước khi ghi. */
    public void onAfterReadForTesting(Runnable hook) {
        this.afterRead = hook == null ? () -> { } : hook;
    }
}
