package com.shop.product.domain.model;

import com.shop.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "category")
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    protected Category() {
        // JPA
    }

    public static Category create(String name) {
        Category c = new Category();
        c.name = Objects.requireNonNull(name, "name");
        return c;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
