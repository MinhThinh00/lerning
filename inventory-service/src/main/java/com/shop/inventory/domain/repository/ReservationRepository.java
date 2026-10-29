package com.shop.inventory.domain.repository;

import com.shop.inventory.domain.model.Reservation;
import java.util.List;

/** Port: implement ở infrastructure.persistence. */
public interface ReservationRepository {

    List<Reservation> findByOrderId(String orderId);

    Reservation save(Reservation reservation);

    /** Ghi xuống DB ngay để unique (order_id, product_id) báo trùng tại chỗ, không đợi tới commit. */
    void flush();
}
