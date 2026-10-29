package com.shop.product.infrastructure.persistence;

import com.shop.product.domain.model.Category;
import com.shop.product.domain.repository.CategoryRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface CategoryJpaRepository extends JpaRepository<Category, Long>, CategoryRepository {
}
