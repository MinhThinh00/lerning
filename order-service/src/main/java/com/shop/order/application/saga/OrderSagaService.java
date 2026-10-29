package com.shop.order.application.saga;

import com.shop.common.messaging.DomainRuleViolationException;
import com.shop.common.messaging.inbox.InboxGuard;
import com.shop.order.application.service.OrderEventPublisher;
import com.shop.order.domain.model.Order;
import com.shop.order.domain.model.OrderStatus;
import com.shop.order.domain.model.Transition;
import com.shop.order.domain.repository.OrderRepository;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phản ứng của order-service với event từ inventory và payment (saga choreography, docs/00 §5).
 * order-service giữ state machine nên là nơi DUY NHẤT quyết định số phận đơn; inventory/payment chỉ báo kết quả.
 *
 * Mỗi event: 1 transaction gồm dòng inbox + chuyển trạng thái + outbox event kế tiếp.
 * Event muộn / trùng: Transition.IGNORED → commit bình thường, không throw (docs/07 §3).
 * Xung đột @Version với job timeout → exception thoát ra → error handler retry trên dữ liệu mới.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orders;
    private final InboxGuard inbox;
    private final OrderEventPublisher events;

    public OrderSagaService(OrderRepository orders, InboxGuard inbox, OrderEventPublisher events) {
        this.orders = orders;
        this.inbox = inbox;
        this.events = events;
    }

    /** inventory.reserved → CONFIRMED → order.confirmed (payment bắt đầu thu tiền). */
    @Transactional
    public void onInventoryReserved(String eventId, String orderId) {
        handle(eventId, "order:inventory.reserved", orderId, Order::confirm, events::confirmed);
    }

    /** inventory.failed → CANCELLED(reason) → order.cancelled. */
    @Transactional
    public void onInventoryFailed(String eventId, String orderId, String reason) {
        handle(eventId, "order:inventory.failed", orderId, o -> o.cancel(reason), events::cancelled);
    }

    /** payment.success → PAID → order.paid (inventory chốt hàng, notification báo khách). */
    @Transactional
    public void onPaymentSucceeded(String eventId, String orderId) {
        handle(eventId, "order:payment.success", orderId, Order::markPaid, events::paid);
    }

    /** payment.failed → CANCELLED(PAYMENT_FAILED) → order.cancelled (inventory nhả hàng). */
    @Transactional
    public void onPaymentFailed(String eventId, String orderId) {
        handle(eventId, "order:payment.failed", orderId, o -> o.cancel("PAYMENT_FAILED"), events::cancelled);
    }

    private void handle(String eventId, String consumer, String orderId, Function<Order, Transition> transition,
                        Consumer<Order> onApplied) {
        if (!inbox.tryMarkProcessed(eventId, consumer)) {
            return;
        }
        // Đơn luôn được ghi TRƯỚC khi order.created được phát, nên không tìm thấy = dữ liệu sai → DLT ngay
        Order order = orders.findById(orderId)
            .orElseThrow(() -> new DomainRuleViolationException("Unknown order " + orderId + " in " + consumer));
        OrderStatus before = order.getStatus();
        Transition result = transition.apply(order);
        switch (result) {
            case APPLIED -> onApplied.accept(order);
            case REFUND_REQUIRED -> {
                log.error("Payment succeeded for CANCELLED order {} (reason {}): refund required",
                    orderId, order.getCancelReason());
                events.refundRequired(order);
            }
            case IGNORED -> log.info("{} ignored for order {} in status {}", consumer, orderId, before);
        }
    }
}
