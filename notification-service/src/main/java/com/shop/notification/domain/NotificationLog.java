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

/**
 * Một thông báo cần gửi, kiêm "hộp thư đi" (mail outbox):
 * <pre>
 *   PENDING ──claim──▶ SENDING ──gửi được──▶ SENT
 *      ▲                  │ lỗi tạm thời (user-service / SMTP chết)
 *      └──────────────────┘                 lỗi vĩnh viễn hoặc hết lượt thử ──▶ FAILED
 * </pre>
 * Chuyển trạng thái bằng UPDATE có điều kiện ở repository (không đọc entity rồi ghi lại), để consumer và
 * retry job chạy song song cũng chỉ 1 bên gửi.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    public enum Type { ORDER_PAID, ORDER_CANCELLED }

    public enum Status { PENDING, SENDING, SENT, FAILED }

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

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "recipient", length = 254)
    private String recipient;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationLog() {
        // JPA
    }

    /** Thông báo mới, chờ gửi. */
    public NotificationLog(String eventId, String orderId, String userId, Type type, String message) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
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

    public Status getStatus() {
        return status;
    }

    public String getRecipient() {
        return recipient;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
