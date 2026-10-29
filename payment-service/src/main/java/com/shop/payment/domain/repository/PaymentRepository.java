package com.shop.payment.domain.repository;

import com.shop.payment.domain.model.Payment;
import java.util.Optional;

/** Port: implement ở infrastructure.persistence. */
public interface PaymentRepository {

    Optional<Payment> findByOrderId(String orderId);

    /** Ghi ngay để unique order_id báo trùng tại chỗ. */
    Payment saveAndFlush(Payment payment);
}
