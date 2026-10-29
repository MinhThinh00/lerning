package com.shop.product.web.rest;

import com.shop.common.web.PageResponse;
import com.shop.product.application.dto.ProductDtos.CreateProductCommand;
import com.shop.product.application.dto.ProductDtos.ProductDetail;
import com.shop.product.application.dto.ProductDtos.ProductView;
import com.shop.product.application.dto.ProductDtos.UpdateProductCommand;
import com.shop.product.application.service.ProductManagementService;
import com.shop.product.application.service.ProductQueryService;
import com.shop.product.web.rest.ProductRequests.CreateProductRequest;
import com.shop.product.web.rest.ProductRequests.UpdateProductRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * | Endpoint                              | Quyền                                   |
 * |---------------------------------------|-----------------------------------------|
 * | GET  /api/products?categoryId=&page=  | public                                  |
 * | GET  /api/products?ids=1,2,3          | public (order-service lấy giá)          |
 * | GET  /api/products/{id}               | public                                  |
 * | POST /api/products                    | product:write (seller = người gọi)      |
 * | PUT  /api/products/{id}               | product:write + chủ sản phẩm hoặc admin |
 */
@RestController
@RequestMapping("/api/products")
class ProductController {

    private final ProductQueryService queries;
    private final ProductManagementService management;

    ProductController(ProductQueryService queries, ProductManagementService management) {
        this.queries = queries;
        this.management = management;
    }

    @GetMapping
    PageResponse<ProductView> list(@RequestParam(required = false) Long categoryId,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.list(categoryId, page, size);
    }

    @GetMapping(params = "ids")
    List<ProductView> getByIds(@RequestParam @Size(min = 1, max = 100) List<Long> ids) {
        return queries.getByIds(ids);
    }

    @GetMapping("/{id}")
    ProductView get(@PathVariable Long id) {
        return queries.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:write')")
    ResponseEntity<ProductDetail> create(JwtAuthenticationToken auth, @Valid @RequestBody CreateProductRequest req) {
        ProductDetail created = management.create(JwtPrincipals.caller(auth),
            new CreateProductCommand(req.sku(), req.name(), req.description(), req.price(), req.categoryId()));
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:write')")
    ProductDetail update(JwtAuthenticationToken auth, @PathVariable Long id, @Valid @RequestBody UpdateProductRequest req) {
        return management.update(JwtPrincipals.caller(auth), id, new UpdateProductCommand(
            req.name(), req.description(), req.price(), req.categoryId(), req.active() == null || req.active(), req.version()));
    }
}
