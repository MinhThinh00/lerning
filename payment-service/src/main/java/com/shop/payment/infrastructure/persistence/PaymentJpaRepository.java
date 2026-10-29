package com.shop.payment.infrastructure.persistence;

import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.model.Payment.Status;
import com.shop.payment.domain.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface PaymentJpaRepository extends JpaRepository<Payment, String>, PaymentRepository {

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
        update Payment p
           set p.status = :status, p.providerTxnId = :providerTxnId, p.reason = :reason,
               p.version = p.version + 1, p.updatedAt = :now
         where p.id = :id and p.status = com.shop.payment.domain.model.Payment.Status.PENDING
        """)
    int completeIfPending(@Param("id") String id, @Param("status") Status status,
                          @Param("providerTxnId") String providerTxnId, @Param("reason") String reason,
                          @Param("now") Instant now);

    @Override
    @Query("""
        select p.id from Payment p
         where p.status = com.shop.payment.domain.model.Payment.Status.PENDING and p.createdAt < :before
         order by p.createdAt
        """)
    List<String> findPendingIdsCreatedBefore(@Param("before") Instant before, Pageable page);
}
