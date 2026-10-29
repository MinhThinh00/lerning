package com.shop.product.domain.repository;

import com.shop.product.domain.model.Category;
import java.util.List;

/** Port: implement ở infrastructure.persistence. */
public interface CategoryRepository {

    List<Category> findAllByOrderByNameAsc();

    boolean existsById(Long id);

    boolean existsByName(String name);

    Category save(Category category);
}
