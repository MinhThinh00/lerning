package com.shop.product.web.rest;

import com.shop.product.application.dto.ProductDtos.CategoryView;
import com.shop.product.application.service.CategoryService;
import com.shop.product.web.rest.ProductRequests.CreateCategoryRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** GET public; tạo danh mục chỉ admin (realm chưa có permission riêng cho category). */
@RestController
@RequestMapping("/api/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryView> list() {
        return categories.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    CategoryView create(@Valid @RequestBody CreateCategoryRequest req) {
        return categories.create(req.name());
    }
}
