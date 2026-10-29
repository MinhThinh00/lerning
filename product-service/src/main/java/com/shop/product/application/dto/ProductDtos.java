package com.shop.product.application.dto;

import com.shop.product.domain.model.Category;
import com.shop.product.domain.model.Product;
import java.math.BigDecimal;
import java.time.Instant;

/** Input/output của use case (không phải DTO HTTP; request DTO có validation nằm ở tầng web). */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** View public: đủ cho khách xem và cho order-service lấy giá. Không lộ sellerId. */
    public record ProductView(Long id, String sku, String name, String description, BigDecimal price,
                              String currency, Long categoryId) {

        public static ProductView from(Product p) {
            return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                p.getCurrency(), p.getCategoryId());
        }
    }

    /** View cho trang quản lý của seller/admin. */
    public record ProductDetail(Long id, String sku, String name, String description, BigDecimal price,
                                String currency, Long categoryId, String sellerId, boolean active, long version,
                                Instant createdAt, Instant updatedAt) {

        public static ProductDetail from(Product p) {
            return new ProductDetail(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                p.getCurrency(), p.getCategoryId(), p.getSellerId(), p.isActive(), p.getVersion(),
                p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record CategoryView(Long id, String name) {

        public static CategoryView from(Category c) {
            return new CategoryView(c.getId(), c.getName());
        }
    }

    public record CreateProductCommand(String sku, String name, String description, BigDecimal price, Long categoryId) {
    }

    /** version: client gửi lại version đã đọc → phát hiện ghi đè lên thay đổi của người khác. */
    public record UpdateProductCommand(String name, String description, BigDecimal price, Long categoryId,
                                       boolean active, Long version) {
    }
}
