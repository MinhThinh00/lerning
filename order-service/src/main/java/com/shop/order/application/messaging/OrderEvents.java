package com.shop.order.application.messaging;

import com.shop.order.domain.model.Order;
import java.util.List;

/**
 * Payload event (docs/05 §3). Tiền serialize dạng STRING (BigDecimal.toPlainString) để không mất chính xác
 * ở consumer viết bằng ngôn ngữ/thư viện khác. Mỗi service tự khai báo class của mình, chỉ cần khớp tên field.
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    public record LineItem(long productId, int quantity) {
    }

    // ── Phát (qua outbox) ──

    public record OrderCreated(String orderId, String userId, List<LineItem> items, String totalAmount, String currency) {
        public static OrderCreated from(Order o) {
            return new OrderCreated(o.getId(), o.getUserId(), lineItems(o), o.getTotalAmount().toPlainString(), o.getCurrency());
        }
    }

    public record OrderConfirmed(String orderId, String userId, String totalAmount, String currency) {
        public static OrderConfirmed from(Order o) {
            return new OrderConfirmed(o.getId(), o.getUserId(), o.getTotalAmount().toPlainString(), o.getCurrency());
        }
    }

    public record OrderPaid(String orderId, String userId, List<LineItem> items, String totalAmount, String currency) {
        public static OrderPaid from(Order o) {
            return new OrderPaid(o.getId(), o.getUserId(), lineItems(o), o.getTotalAmount().toPlainString(), o.getCurrency());
        }
    }

    /** items để inventory nhả hàng (và tạo tombstone nếu cancelled đến trước created). */
    public record OrderCancelled(String orderId, String userId, String reason, List<LineItem> items) {
        public static OrderCancelled from(Order o) {
            return new OrderCancelled(o.getId(), o.getUserId(), o.getCancelReason(), lineItems(o));
        }
    }

    // ── Nhận ──

    public record InventoryReserved(String orderId) {
    }

    /** reason: OUT_OF_STOCK / LOCK_TIMEOUT / CONFLICT / NOT_FOUND */
    public record InventoryFailed(String orderId, String reason, Long productId) {
    }

    public record PaymentSucceeded(String orderId, String paymentId, String amount, String currency) {
    }

    public record PaymentFailed(String orderId, String reason) {
    }

    private static List<LineItem> lineItems(Order o) {
        return o.getItems().stream().map(i -> new LineItem(i.getProductId(), i.getQuantity())).toList();
    }
}
