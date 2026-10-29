package com.shop.inventory.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Hàng đã giữ cho 1 (đơn, sản phẩm). Unique (order_id, product_id) là chốt idempotency cuối cùng:
 * 2 consumer cùng xử lý 1 order.created thì transaction thứ hai vỡ ở INSERT.
 *
 * <pre>
 *   RESERVED ──order.paid──▶ COMMITTED
 *      └──────order.cancelled──▶ RELEASED
 *   FAILED     (giữ hàng thất bại, chưa từng trừ kho)
 *   RELEASED   (tombstone: order.cancelled đến TRƯỚC order.created)
 * </pre>
 */
@Entity
@Table(name = "inventory_reservation")
public class Reservation {

    public enum Status { RESERVED, COMMITTED, RELEASED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 36, updatable = false)
    private String orderId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "strategy", length = 4)
    private String strategy;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
        // JPA
    }

    public static Reservation reserved(String orderId, long productId, int quantity, String strategy) {
        return of(orderId, productId, quantity, Status.RESERVED, strategy, null);
    }

    public static Reservation failed(String orderId, long productId, int quantity, String strategy, String reason) {
        return of(orderId, productId, quantity, Status.FAILED, strategy, reason);
    }

    /** order.cancelled đến trước order.created: đánh dấu để order.created đến sau bỏ qua. */
    public static Reservation tombstone(String orderId, long productId, int quantity) {
        return of(orderId, productId, quantity, Status.RELEASED, null, "CANCELLED_BEFORE_CREATED");
    }

    private static Reservation of(String orderId, long productId, int quantity, Status status,
                                  String strategy, String reason) {
        Reservation r = new Reservation();
        r.orderId = Objects.requireNonNull(orderId, "orderId");
        r.productId = productId;
        r.quantity = quantity;
        r.status = status;
        r.strategy = strategy;
        r.reason = reason;
        return r;
    }

    public void commit() {
        transition(Status.COMMITTED);
    }

    public void release() {
        transition(Status.RELEASED);
    }

    private void transition(Status target) {
        if (status != Status.RESERVED) {
            throw new IllegalStateException("Reservation " + id + " is " + status + ", cannot become " + target);
        }
        status = target;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getReason() {
        return reason;
    }
}
