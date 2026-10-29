package com.shop.product.domain.model;

import com.shop.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sản phẩm. Nguồn sự thật về GIÁ: order-service lấy giá ở đây lúc đặt hàng rồi chụp snapshot vào đơn,
 * nên sửa giá về sau không làm thay đổi đơn cũ.
 *
 * Danh mục tham chiếu bằng categoryId (aggregate khác), không map @ManyToOne.
 * sellerId = Keycloak sub của người sở hữu, không đổi được sau khi tạo.
 */
@Entity
@Table(name = "product")
public class Product extends AuditableEntity {

    public static final String DEFAULT_CURRENCY = "VND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku", nullable = false, length = 50, updatable = false)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "seller_id", nullable = false, length = 36, updatable = false)
    private String sellerId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Product() {
        // JPA
    }

    public static Product create(String sellerId, String sku, Details details) {
        Product p = new Product();
        p.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        p.sku = Objects.requireNonNull(sku, "sku");
        p.apply(details);
        return p;
    }

    public void update(Details details, boolean active) {
        apply(details);
        this.active = active;
    }

    public boolean isOwnedBy(String userId) {
        return sellerId.equals(userId);
    }

    private void apply(Details d) {
        if (d.price() == null || d.price().signum() <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
        this.name = Objects.requireNonNull(d.name(), "name");
        this.description = d.description();
        this.price = d.price();
        this.categoryId = d.categoryId();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    /** Các trường sửa được. */
    public record Details(String name, String description, BigDecimal price, Long categoryId) {
    }
}
