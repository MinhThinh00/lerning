package com.shop.product.application.service;

import com.shop.common.web.PageResponse;
import com.shop.common.web.error.BusinessException;
import com.shop.common.web.error.NotFoundException;
import com.shop.product.application.access.Caller;
import com.shop.product.application.access.ProductAccessPolicy;
import com.shop.product.application.dto.ProductDtos.CreateProductCommand;
import com.shop.product.application.dto.ProductDtos.ProductDetail;
import com.shop.product.application.dto.ProductDtos.UpdateProductCommand;
import com.shop.product.domain.model.Product;
import com.shop.product.domain.model.Product.Details;
import com.shop.product.domain.repository.CategoryRepository;
import com.shop.product.domain.repository.ProductRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý sản phẩm cho seller/admin. Phân quyền 2 lớp:
 *  1. @PreAuthorize ở controller: có product:write không.
 *  2. ProductAccessPolicy ở đây: có được đụng SẢN PHẨM NÀY không (seller chỉ của mình, admin tất cả).
 *
 * Tạo sản phẩm KHÔNG tạo tồn kho: admin nhập kho ở inventory-service, hai service độc lập (docs/07 §8).
 */
@Service
public class ProductManagementService {

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductManagementService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductDetail> list(Caller caller, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Optional<String> scope = ProductAccessPolicy.sellerScope(caller);
        return PageResponse.from(scope.isPresent()
            ? products.findBySellerId(scope.get(), pageable)
            : products.findAll(pageable), ProductDetail::from);
    }

    @Transactional(readOnly = true)
    public ProductDetail get(Caller caller, Long id) {
        return ProductDetail.from(loadModifiable(caller, id));
    }

    @Transactional
    public ProductDetail create(Caller caller, CreateProductCommand cmd) {
        checkCategory(cmd.categoryId());
        if (products.existsBySku(cmd.sku())) {
            throw skuTaken(cmd.sku());
        }
        Product product = Product.create(caller.id(), cmd.sku(),
            new Details(cmd.name(), cmd.description(), cmd.price(), cmd.categoryId()));
        try {
            return ProductDetail.from(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException ex) {
            // 2 request cùng sku lọt qua existsBySku cùng lúc → unique constraint của DB chặn.
            // Chỉ dịch thành SKU_TAKEN khi đúng constraint đó: lỗi khác (vd dữ liệu quá dài) phải nổi lên,
            // không được giả dạng "trùng SKU".
            if (violates(ex, "uk_product_sku")) {
                throw skuTaken(cmd.sku());
            }
            throw ex;
        }
    }

    @Transactional
    public ProductDetail update(Caller caller, Long id, UpdateProductCommand cmd) {
        Product product = loadModifiable(caller, id);
        if (cmd.version() != null && cmd.version() != product.getVersion()) {
            throw conflict();
        }
        checkCategory(cmd.categoryId());
        product.update(new Details(cmd.name(), cmd.description(), cmd.price(), cmd.categoryId()), cmd.active());
        try {
            return ProductDetail.from(products.saveAndFlush(product));
        } catch (OptimisticLockingFailureException concurrent) {
            // 2 request cùng đọc version N rồi cùng ghi → @Version chặn người ghi sau
            throw conflict();
        }
    }

    private Product loadModifiable(Caller caller, Long id) {
        Product product = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        ProductAccessPolicy.checkCanModify(caller, product);
        return product;
    }

    private void checkCategory(Long categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY", "Category " + categoryId + " does not exist");
        }
    }

    /** MySQL báo "Duplicate entry '...' for key 'product.uk_product_sku'" ở đâu đó trong chuỗi cause. */
    private static boolean violates(Throwable ex, String constraintName) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private static BusinessException skuTaken(String sku) {
        return new BusinessException(HttpStatus.CONFLICT, "SKU_TAKEN", "SKU " + sku + " already exists");
    }

    /** Bản đang sửa đã cũ: người khác vừa cập nhật. Client đọc lại rồi sửa tiếp. */
    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Product was modified by someone else");
    }
}
