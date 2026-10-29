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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Lần thu tiền của 1 đơn (unique order_id: order.confirmed giao lại cũng không tạo lần thu thứ hai).
 *
 * Vòng đời: PENDING (đã gửi sang cổng, chờ kết quả) → SUCCEEDED | FAILED. Trạng thái cuối KHÔNG đổi được nữa.
 * Việc chuyển trạng thái KHÔNG đi qua entity mà qua {@code PaymentRepository.completeIfPending}
 * ({@code UPDATE ... WHERE status = 'PENDING'}): callback trùng / đồng thời / reconcile chạy song song thì chỉ
 * đúng 1 bên đổi được dòng (docs/10 T7). Đọc entity rồi kiểm tra {@code status == PENDING} trong Java là bug
 * của SX: 2 callback cùng đọc PENDING, cùng xử lý thành công.
 */
@Entity
@Table(name = "payment")
public class Payment implements Persistable<String> {

    public enum Status { PENDING, SUCCEEDED, FAILED }

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
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "provider_txn_id", length = 64)
    private String providerTxnId;

    @Column(name = "reason", length = 100)
    private String reason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected Payment() {
        // JPA
    }

    public static Payment pending(String orderId, BigDecimal amount, String currency) {
        Payment p = new Payment();
        p.id = UUID.randomUUID().toString();
        p.orderId = Objects.requireNonNull(orderId, "orderId");
        p.amount = Objects.requireNonNull(amount, "amount");
        p.currency = Objects.requireNonNull(currency, "currency");
        p.status = Status.PENDING;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
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

    public String getProviderTxnId() {
        return providerTxnId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
