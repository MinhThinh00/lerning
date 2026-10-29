package com.shop.product.web.rest;

import com.shop.common.web.PageResponse;
import com.shop.product.application.dto.ProductDtos.ProductDetail;
import com.shop.product.application.service.ProductManagementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trang quản lý sản phẩm: thấy cả sản phẩm ngừng bán, có sellerId/version.
 * Seller chỉ thấy sản phẩm của mình, admin thấy tất cả (ProductAccessPolicy).
 */
@RestController
@RequestMapping("/api/seller/products")
@PreAuthorize("hasAuthority('product:write')")
class SellerProductController {

    private final ProductManagementService management;

    SellerProductController(ProductManagementService management) {
        this.management = management;
    }

    @GetMapping
    PageResponse<ProductDetail> list(JwtAuthenticationToken auth,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return management.list(JwtPrincipals.caller(auth), page, size);
    }

    @GetMapping("/{id}")
    ProductDetail get(JwtAuthenticationToken auth, @PathVariable Long id) {
        return management.get(JwtPrincipals.caller(auth), id);
    }
}
