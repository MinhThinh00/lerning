package com.shop.product.infrastructure.persistence;

import com.shop.product.domain.model.Product;
import com.shop.product.domain.repository.ProductRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface ProductJpaRepository extends JpaRepository<Product, Long>, ProductRepository {
}
