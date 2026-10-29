package com.shop.inventory.application.messaging;

import java.util.List;

/**
 * Payload event mà inventory-service phát / nhận (docs/05 §3). Mỗi service tự khai báo class của mình,
 * không chia sẻ class Java với producer: chỉ cần khớp tên field JSON. Field thừa bị bỏ qua (Jackson 3 mặc định).
 */
public final class InventoryEvents {

    private InventoryEvents() {
    }

    public record LineItem(long productId, int quantity) {
    }

    // ── Phát (qua outbox) ──

    public record InventoryReserved(String orderId, List<LineItem> items, String strategy) {
    }

    public record InventoryFailed(String orderId, String reason, long productId) {
    }

    // ── Nhận (từ order-service) ──

    public record OrderCreated(String orderId, String userId, List<LineItem> items) {
    }

    public record OrderPaid(String orderId, List<LineItem> items) {
    }

    /** items để release, và để tạo tombstone khi order.cancelled đến trước order.created. */
    public record OrderCancelled(String orderId, String reason, List<LineItem> items) {
    }
}
