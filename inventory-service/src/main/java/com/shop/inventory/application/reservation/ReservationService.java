package com.shop.inventory.application.reservation;

import com.shop.common.web.error.BusinessException;
import com.shop.inventory.application.reservation.ReserveOutcome.AlreadyHandled;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.domain.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Use case giữ hàng cho 1 đơn (docs/04 §3), chung cho mọi strategy:
 * <pre>
 *  1. Đơn đã có reservation (redelivery / đã bị hủy trước) → AlreadyHandled, không làm gì, không phát event
 *  2. Sắp items theo productId                               → mọi request lock theo cùng thứ tự, chống deadlock
 *  3. strategy.reserve: thành công → trừ kho + reservation RESERVED + outbox inventory.reserved (1 transaction)
 *                       thất bại   → transaction MỚI: reservation FAILED + outbox inventory.failed
 *  4. Giữ được bằng L1–L4 → trừ counter Redis (L5 đã tự trừ ở cổng) để counter bám theo DB
 *  5. Metric inventory.reserve{strategy, outcome}
 * </pre>
 * Hai consumer cùng vượt qua bước 1 (redelivery song song) → unique (order_id, product_id) làm transaction
 * thứ hai vỡ → bắt lại, trả AlreadyHandled.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final Map<Strategy, StockReservationStrategy> strategies = new EnumMap<>(Strategy.class);
    private final ReservationRepository reservations;
    private final ReservationRecorder recorder;
    private final TransactionTemplate tx;
    private final StockCounter counter;
    private final MeterRegistry meters;

    public ReservationService(List<StockReservationStrategy> strategies, ReservationRepository reservations,
                              ReservationRecorder recorder, TransactionTemplate tx, StockCounter counter,
                              MeterRegistry meters) {
        strategies.forEach(s -> this.strategies.put(s.id(), s));
        this.reservations = reservations;
        this.recorder = recorder;
        this.tx = tx;
        this.counter = counter;
        this.meters = meters;
    }

    public ReserveOutcome reserve(String orderId, List<Item> items, Strategy strategyId) {
        StockReservationStrategy strategy = requireAvailable(strategyId);
        List<Item> sorted = validateAndSort(items);
        Timer.Sample sample = Timer.start(meters);
        ReserveOutcome outcome = null;
        try {
            outcome = doReserve(orderId, sorted, strategy);
            return outcome;
        } finally {
            sample.stop(Timer.builder("inventory.reserve")
                .tag("strategy", strategyId.name())
                .tag("outcome", outcome == null ? "error" : outcome.tag())   // không tag product_id: cardinality vô hạn
                .publishPercentileHistogram()
                .register(meters));
        }
    }

    public StockReservationStrategy requireAvailable(Strategy strategyId) {
        StockReservationStrategy strategy = strategies.get(strategyId);
        if (strategy == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STRATEGY_NOT_AVAILABLE",
                "Strategy " + strategyId + " is not implemented yet (available: " + strategies.keySet() + ")");
        }
        return strategy;
    }

    private ReserveOutcome doReserve(String orderId, List<Item> sorted, StockReservationStrategy strategy) {
        if (!reservations.findByOrderId(orderId).isEmpty()) {
            log.info("Order {} already has reservations, skip", orderId);
            return new AlreadyHandled();
        }
        try {
            ReserveOutcome outcome = strategy.reserve(orderId, sorted);
            if (outcome instanceof Reserved && strategy.id() != Strategy.L5) {
                sorted.forEach(it -> counter.adjust(it.productId(), -it.quantity()));   // đã commit; best-effort
            }
            if (outcome instanceof Rejected rejected) {
                tx.executeWithoutResult(s -> recorder.failed(orderId, sorted, rejected, strategy.id()));
                // debug: lúc benchmark có hàng nghìn lần từ chối mỗi giây; số liệu đã có ở metric inventory.reserve
                log.debug("Order {} rejected by {}: {} (product {})", orderId, strategy.id(), rejected.reason(), rejected.productId());
            }
            return outcome;
        } catch (DataIntegrityViolationException duplicate) {
            // Consumer khác (hoặc tombstone của order.cancelled) vừa ghi reservation cho đơn này
            log.info("Order {} reserved concurrently by another consumer, skip", orderId);
            return new AlreadyHandled();
        }
    }

    private static List<Item> validateAndSort(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (new HashSet<>(items.stream().map(Item::productId).toList()).size() != items.size()) {
            throw new IllegalArgumentException("duplicate productId in items");
        }
        return items.stream().sorted(Comparator.comparingLong(Item::productId)).toList();
    }
}
