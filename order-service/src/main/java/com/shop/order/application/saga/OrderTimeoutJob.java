package com.shop.order.application.saga;

import com.shop.order.application.service.OrderEventPublisher;
import com.shop.order.config.OrderProperties;
import com.shop.order.domain.model.OrderStatus;
import com.shop.order.domain.model.Transition;
import com.shop.order.domain.repository.OrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hủy đơn kẹt quá lâu (docs/07 §5): PENDING (inventory không trả lời) và CONFIRMED (payment không trả lời).
 * Không có bước này, đơn kẹt mãi và hàng bị giữ mãi khi 1 service chết.
 *
 * - ShedLock: N replica thì mỗi lượt chỉ 1 job chạy → không hủy 1 đơn nhiều lần, không phát order.cancelled trùng.
 * - Xử lý theo trang 100 đơn cũ nhất; cursor là chính dữ liệu (đơn đã hủy tự rơi khỏi điều kiện), không lưu biến tạm.
 * - Mỗi đơn 1 transaction riêng: 1 đơn lỗi không chặn cả lô. Consumer vừa sửa đơn đó → @Version chặn → bỏ qua,
 *   lượt sau xét lại.
 */
@Component
public class OrderTimeoutJob {

    public static final String LOCK_NAME = "order.timeout";

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);
    private static final int PAGE = 100;

    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final TransactionTemplate tx;
    private final OrderProperties props;

    public OrderTimeoutJob(OrderRepository orders, OrderEventPublisher events, TransactionTemplate tx, OrderProperties props) {
        this.orders = orders;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${order.timeout.check-interval:60s}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void cancelExpired() {
        runOnce();
    }

    /**
     * Một lượt, KHÔNG qua ShedLock (test gọi trực tiếp). Lưu ý: gọi cancelExpired() 2 lần trong vòng
     * lockAtLeastFor (5s) thì lần sau bị ShedLock bỏ qua im lặng — đúng thiết kế, nhưng dễ làm test "fail khó hiểu".
     */
    public void runOnce() {
        cancelOlderThan(OrderStatus.PENDING, props.pendingTimeout());
        cancelOlderThan(OrderStatus.CONFIRMED, props.paymentTimeout());
    }

    /** @return số đơn đã hủy */
    int cancelOlderThan(OrderStatus status, Duration timeout) {
        int cancelled = 0;
        List<String> ids;
        do {
            ids = orders.findStuckIds(status, Instant.now().minus(timeout), PageRequest.of(0, PAGE));
            int before = cancelled;
            for (String id : ids) {
                if (cancelOne(id, status)) {
                    cancelled++;
                }
            }
            if (cancelled == before) {
                break;   // cả trang không hủy được đơn nào (xung đột) → dừng, tránh lặp vô hạn trên cùng 1 trang
            }
        } while (ids.size() == PAGE);
        if (cancelled > 0) {
            log.info("Timeout job cancelled {} {} orders older than {}", cancelled, status, timeout);
        }
        return cancelled;
    }

    private boolean cancelOne(String id, OrderStatus expected) {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> orders.findById(id)
                .filter(o -> o.getStatus() == expected)   // có thể đã đổi trạng thái sau lúc query id
                .map(o -> {
                    if (o.cancel("TIMEOUT") != Transition.APPLIED) {
                        return false;
                    }
                    events.cancelled(o);
                    return true;
                })
                .orElse(false)));
        } catch (OptimisticLockingFailureException concurrent) {
            log.info("Order {} changed concurrently while timing out, retry next run", id);
            return false;
        }
    }
}
