package com.shop.order.domain.model;

/**
 * <pre>
 *   PENDING ──inventory.reserved──▶ CONFIRMED ──payment.success──▶ PAID
 *      │                               │
 *      └─inventory.failed / user cancel / timeout─┴─payment.failed / user cancel / timeout──▶ CANCELLED
 * </pre>
 * Phạm vi dừng ở PAID (docs/07 §2). PAID và CANCELLED là trạng thái cuối.
 */
public enum OrderStatus {
    PENDING, CONFIRMED, PAID, CANCELLED;

    public boolean isTerminal() {
        return this == PAID || this == CANCELLED;
    }
}
