package com.shop.product.application.service;

import com.shop.common.web.error.BusinessException;
import com.shop.product.application.dto.ProductDtos.CategoryView;
import com.shop.product.domain.model.Category;
import com.shop.product.domain.repository.CategoryRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryView::from).toList();
    }

    @Transactional
    public CategoryView create(String name) {
        if (categories.existsByName(name)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_EXISTS", "Category " + name + " already exists");
        }
        return CategoryView.from(categories.save(Category.create(name)));
    }
}
