package com.shop.inventory.application.reservation;

/** 1 dòng hàng cần giữ. productId dạng long như trong event (docs/05 §3). */
public record Item(long productId, int quantity) {

    public Item {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
