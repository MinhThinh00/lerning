package com.shop.product.application.service;

import com.shop.common.web.PageResponse;
import com.shop.common.web.error.NotFoundException;
import com.shop.product.application.dto.ProductDtos.ProductView;
import com.shop.product.domain.repository.ProductRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API đọc public (không cần token). Chỉ trả sản phẩm đang bán (active).
 * order-service gọi getByIds để lấy giá lúc đặt hàng.
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository products;

    public ProductQueryService(ProductRepository products) {
        this.products = products;
    }

    public ProductView get(Long id) {
        return products.findByIdAndActiveTrue(id).map(ProductView::from)
            .orElseThrow(() -> new NotFoundException("Product", id));
    }

    /**
     * Id không tồn tại / ngừng bán thì không có trong kết quả (không lỗi):
     * bên gọi tự so danh sách để biết thiếu id nào.
     */
    public List<ProductView> getByIds(Collection<Long> ids) {
        return products.findByIdInAndActiveTrue(ids).stream().map(ProductView::from).toList();
    }

    public PageResponse<ProductView> list(Long categoryId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id"));
        return PageResponse.from(categoryId == null
            ? products.findByActiveTrue(pageable)
            : products.findByCategoryIdAndActiveTrue(categoryId, pageable), ProductView::from);
    }
}
