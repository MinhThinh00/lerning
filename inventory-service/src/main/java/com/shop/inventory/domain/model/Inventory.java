package com.shop.inventory.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Tồn kho của 1 sản phẩm. Bất biến (docs/04 §2):
 * <pre>
 *   available + reserved + sold = tổng nhập kho
 *   reserved = SUM(quantity) của reservation RESERVED
 * </pre>
 * Luồng nóng (reserve/commit/release) KHÔNG đọc entity rồi ghi lại mà dùng UPDATE nguyên tử có điều kiện
 * (InventoryRepository), để nhiều replica cùng ghi 1 dòng không làm mất cập nhật của nhau.
 * Các UPDATE đó đều tăng version, nên strategy dùng @Version (L3, Phase 8) chạy xen kẽ vẫn đúng.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(name = "sku", length = 50)
    private String sku;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "sold", nullable = false)
    private int sold;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
        // JPA
    }

    /** Nhập kho lần đầu cho sản phẩm chưa có dòng tồn kho. */
    public static Inventory open(long productId, int initialAvailable) {
        if (initialAvailable < 0) {
            throw new IllegalArgumentException("initialAvailable must be >= 0");
        }
        Inventory inv = new Inventory();
        inv.productId = productId;
        inv.available = initialAvailable;
        return inv;
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

    public long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public int getSold() {
        return sold;
    }

    public long getVersion() {
        return version;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
