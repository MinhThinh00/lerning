package com.shop.product.domain.repository;

import com.shop.product.domain.model.Product;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Port: domain chỉ biết interface này, không biết JPA. Implement ở infrastructure.persistence. */
public interface ProductRepository {

    Optional<Product> findById(Long id);

    Optional<Product> findByIdAndActiveTrue(Long id);

    List<Product> findByIdInAndActiveTrue(Collection<Long> ids);

    Page<Product> findByActiveTrue(Pageable pageable);

    Page<Product> findByCategoryIdAndActiveTrue(Long categoryId, Pageable pageable);

    /** Danh sách cho trang quản lý: mọi trạng thái, của 1 seller. */
    Page<Product> findBySellerId(String sellerId, Pageable pageable);

    Page<Product> findAll(Pageable pageable);

    boolean existsBySku(String sku);

    Product save(Product product);

    /** Ghi xuống DB ngay: bắt vi phạm unique (sku) và xung đột @Version tại chỗ, trong service. */
    Product saveAndFlush(Product product);
}
