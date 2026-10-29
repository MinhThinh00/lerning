package com.shop.order.domain.model;

import com.shop.order.domain.model.OrderItem.PricedLine;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Aggregate đơn hàng, nhân vật chính của saga (docs/07).
 *
 * - id = UUID sinh trong app: biết trước khi insert (dùng làm key Kafka ngay), không đoán được.
 * - @Version: consumer và job timeout cùng sửa 1 đơn → người ghi sau bị chặn, retry lại trên dữ liệu mới.
 * - Method chuyển trạng thái trả Transition thay vì throw (event muộn/trùng là chuyện bình thường).
 */
@Entity
@Table(name = "orders")
public class Order implements Persistable<String> {

    public static final String CURRENCY = "VND";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = CURRENCY;

    @Column(name = "shipping_address", length = 500, updatable = false)
    private String shippingAddress;

    @Column(name = "cancel_reason", length = 100)
    private String cancelReason;

    @Column(name = "refund_required", nullable = false)
    private boolean refundRequired;

    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("productId")
    private List<OrderItem> items = new ArrayList<>();

    @Transient
    private boolean isNew = true;

    protected Order() {
        // JPA
    }

    public static Order create(String userId, List<PricedLine> lines, String shippingAddress, String idempotencyKey) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("order must have at least 1 item");
        }
        Order o = new Order();
        o.id = UUID.randomUUID().toString();
        o.userId = Objects.requireNonNull(userId, "userId");
        o.status = OrderStatus.PENDING;
        o.shippingAddress = shippingAddress;
        o.idempotencyKey = idempotencyKey;
        lines.forEach(l -> o.items.add(new OrderItem(o, l)));
        o.totalAmount = o.items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        o.createdAt = Instant.now();
        o.updatedAt = o.createdAt;
        return o;
    }

    /** inventory.reserved */
    public Transition confirm() {
        if (status != OrderStatus.PENDING) {
            return Transition.IGNORED;   // trùng (CONFIRMED/PAID) hoặc muộn (CANCELLED: inventory sẽ nhả theo order.cancelled)
        }
        status = OrderStatus.CONFIRMED;
        confirmedAt = Instant.now();
        return Transition.APPLIED;
    }

    /** payment.success */
    public Transition markPaid() {
        return switch (status) {
            case CONFIRMED -> {
                status = OrderStatus.PAID;
                paidAt = Instant.now();
                yield Transition.APPLIED;
            }
            case CANCELLED -> {
                // Đơn đã hủy (vd timeout) nhưng tiền vẫn bị trừ → giữ CANCELLED, đánh dấu hoàn tiền
                if (refundRequired) {
                    yield Transition.IGNORED;
                }
                refundRequired = true;
                yield Transition.REFUND_REQUIRED;
            }
            case PAID, PENDING -> Transition.IGNORED;   // PAID: trùng. PENDING: không thể xảy ra trong luồng đúng
        };
    }

    /** inventory.failed / payment.failed / user cancel / timeout */
    public Transition cancel(String reason) {
        if (status != OrderStatus.PENDING && status != OrderStatus.CONFIRMED) {
            return Transition.IGNORED;   // PAID: payment.failed muộn → bỏ qua. CANCELLED: trùng
        }
        status = OrderStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = Instant.now();
        return Transition.APPLIED;
    }

    public boolean isOwnedBy(String user) {
        return userId.equals(user);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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

    public String getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public boolean isRefundRequired() {
        return refundRequired;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
