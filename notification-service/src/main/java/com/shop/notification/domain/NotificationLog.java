package com.shop.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Thông báo đã "gửi". Bản mock chỉ ghi log + bảng này; gửi email thật: docs/07 §10, docs/10 T8. */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    public enum Type { ORDER_PAID, ORDER_CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, updatable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false, length = 36, updatable = false)
    private String orderId;

    @Column(name = "user_id", nullable = false, length = 36, updatable = false)
    private String userId;

    @Column(name = "type", nullable = false, length = 32, updatable = false)
    @Enumerated(EnumType.STRING)
    private Type type;

    @Column(name = "message", nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationLog() {
        // JPA
    }

    public NotificationLog(String eventId, String orderId, String userId, Type type, String message) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
