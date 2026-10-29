package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.application.reservation.StockCounter.DeductResult;
import com.shop.inventory.application.reservation.StockCounter.Deduction;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L5: counter Redis (Lua, nguyên tử) làm CỔNG + L1 làm bước quyết định (docs/04 §4).
 * <pre>
 *  Redis đủ hàng?  không → OUT_OF_STOCK ngay, KHÔNG chạm DB   (lợi ích chính: flash sale hết hàng không đè DB)
 *                  có    → Redis đã trừ → L1 trên DB
 *                              DB đồng ý   → xong
 *                              DB từ chối / lỗi → trả lại Redis (restore)
 *  Key chưa nạp    → nạp từ DB (SET NX) rồi thử lại; vẫn không có (sản phẩm không tồn tại) → để L1 trả lời
 *  Redis chết      → đi thẳng L1: chậm hơn nhưng vẫn đúng
 * </pre>
 * Đúng tuyệt đối vẫn do L1 đảm bảo: counter cao hơn DB thì vài request lọt cổng rồi bị L1 từ chối; thấp hơn DB
 * thì vài request bị từ chối oan → reconcile kéo counter về theo DB.
 */
@Component
public class L5RedisGateStrategy implements StockReservationStrategy {

    private static final Logger log = LoggerFactory.getLogger(L5RedisGateStrategy.class);

    private final StockCounter counter;
    private final StockCounterSync sync;
    private final L1ConditionalUpdateStrategy l1;

    public L5RedisGateStrategy(StockCounter counter, StockCounterSync sync, L1ConditionalUpdateStrategy l1) {
        this.counter = counter;
        this.sync = sync;
        this.l1 = l1;
    }

    @Override
    public Strategy id() {
        return Strategy.L5;
    }

    @Override
    public ReserveOutcome reserve(String orderId, List<Item> sortedItems) {
        Deduction gate;
        try {
            gate = counter.tryDeduct(sortedItems);
            if (gate.result() == DeductResult.NOT_LOADED) {
                sync.load(StockChecks.ids(sortedItems));
                gate = counter.tryDeduct(sortedItems);
            }
        } catch (RuntimeException redisDown) {
            log.warn("Order {}: Redis unavailable, L5 falls back to L1: {}", orderId, redisDown.getMessage());
            return l1.reserveAs(orderId, sortedItems, Strategy.L5);
        }
        switch (gate.result()) {
            case INSUFFICIENT:
                return new Rejected(Reason.OUT_OF_STOCK, gate.shortProductId());
            case NOT_LOADED:
                return l1.reserveAs(orderId, sortedItems, Strategy.L5);   // không có dòng tồn kho active → L1 trả NOT_FOUND
            default:
                break;
        }
        try {
            ReserveOutcome outcome = l1.reserveAs(orderId, sortedItems, Strategy.L5);
            if (!(outcome instanceof Reserved)) {
                counter.restore(sortedItems);
            }
            return outcome;
        } catch (RuntimeException e) {
            counter.restore(sortedItems);   // gồm cả đơn trùng (unique key) → ReservationService trả AlreadyHandled
            throw e;
        }
    }
}
