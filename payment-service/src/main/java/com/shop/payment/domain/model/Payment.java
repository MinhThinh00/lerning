package com.shop.payment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Kết quả thu tiền của 1 đơn. Unique order_id: 1 đơn chỉ thu 1 lần dù order.confirmed bị giao lại. */
@Entity
@Table(name = "payment")
public class Payment implements Persistable<String> {

    public enum Status { SUCCEEDED, FAILED }

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36, updatable = false)
    private String orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16, updatable = false)
    private Status status;

    @Column(name = "reason", length = 100, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Payment() {
        // JPA
    }

    public static Payment succeeded(String orderId, BigDecimal amount, String currency) {
        return of(orderId, amount, currency, Status.SUCCEEDED, null);
    }

    public static Payment failed(String orderId, BigDecimal amount, String currency, String reason) {
        return of(orderId, amount, currency, Status.FAILED, reason);
    }

    private static Payment of(String orderId, BigDecimal amount, String currency, Status status, String reason) {
        Payment p = new Payment();
        p.id = UUID.randomUUID().toString();
        p.orderId = Objects.requireNonNull(orderId, "orderId");
        p.amount = Objects.requireNonNull(amount, "amount");
        p.currency = Objects.requireNonNull(currency, "currency");
        p.status = status;
        p.reason = reason;
        p.createdAt = Instant.now();
        return p;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
