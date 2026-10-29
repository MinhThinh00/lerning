package com.shop.payment.infrastructure.persistence;

import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.repository.PaymentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface PaymentJpaRepository extends JpaRepository<Payment, String>, PaymentRepository {
}
