package com.shop.product.web.rest;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request body HTTP + validation. Giá do server giữ; client không bao giờ gửi giá khi đặt hàng (docs/07 §4). */
final class ProductRequests {

    private ProductRequests() {
    }

    record CreateProductRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Z0-9][A-Z0-9-]*", message = "uppercase letters, digits and '-'") String sku,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal price,
        Long categoryId) {
    }

    /** active null = giữ đang bán. version: gửi lại version đã đọc để tránh ghi đè thay đổi của người khác. */
    record UpdateProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal price,
        Long categoryId,
        Boolean active,
        Long version) {
    }

    record CreateCategoryRequest(@NotBlank @Size(max = 100) String name) {
    }
}
