package com.shop.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Dòng hàng, giá là SNAPSHOT lấy từ product-service lúc đặt: sửa giá sau này không ảnh hưởng đơn cũ. */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;

    @Column(name = "sku", length = 50, updatable = false)
    private String sku;

    @Column(name = "product_name", length = 200, updatable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    protected OrderItem() {
        // JPA
    }

    OrderItem(Order order, PricedLine line) {
        this.order = order;
        this.productId = line.productId();
        this.sku = line.sku();
        this.productName = line.productName();
        this.unitPrice = line.unitPrice();
        this.quantity = line.quantity();
        this.lineTotal = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
    }

    public long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    /** Dòng hàng đã có giá do server tra (không bao giờ nhận giá từ client). */
    public record PricedLine(long productId, String sku, String productName, BigDecimal unitPrice, int quantity) {
    }
}
