package com.shop.payment.domain.repository;

import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.model.Payment.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/** Port: implement ở infrastructure.persistence. */
public interface PaymentRepository {

    Optional<Payment> findById(String id);

    Optional<Payment> findByOrderId(String orderId);

    /** Ghi ngay để unique order_id báo trùng tại chỗ. */
    Payment saveAndFlush(Payment payment);

    /**
     * PENDING → status (SUCCEEDED | FAILED), có điều kiện. Kiểm tra và ghi nằm trong 1 câu UPDATE dưới row lock.
     * @return 1 = mình là bên chuyển trạng thái; 0 = payment không còn PENDING (callback trùng / reconcile đã chốt)
     */
    int completeIfPending(String id, Status status, String providerTxnId, String reason, Instant now);

    /** Id các payment PENDING tạo trước {@code before}, cũ nhất trước (reconcile job). */
    List<String> findPendingIdsCreatedBefore(Instant before, Pageable page);
}
