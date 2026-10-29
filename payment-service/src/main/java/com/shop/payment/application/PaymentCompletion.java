package com.shop.payment.application;

import com.shop.common.messaging.Topics;
import com.shop.common.messaging.outbox.OutboxWriter;
import com.shop.payment.application.PaymentEvents.PaymentFailed;
import com.shop.payment.application.PaymentEvents.PaymentSucceeded;
import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.model.Payment.Status;
import com.shop.payment.domain.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chốt kết quả 1 payment, dùng chung cho callback và reconcile (docs/10 T7 bước 3–4):
 * <pre>
 *   UPDATE payment SET status = ? ... WHERE id = ? AND status = 'PENDING'
 *     0 dòng → đã có bên khác chốt (callback trùng, callback đồng thời, reconcile) → không làm gì
 *     1 dòng → mình thắng → CÙNG transaction ghi outbox payment.success / payment.failed
 * </pre>
 * Không gọi HTTP, không gửi thông báo trong transaction: mọi side effect ra ngoài đi qua outbox.
 */
@Service
public class PaymentCompletion {

    public enum Result { APPLIED, ALREADY_COMPLETED, NOT_FOUND }

    /** Ai báo kết quả: tag metric + log, để biết bao nhiêu payment phải nhờ reconcile mới chốt được. */
    public enum Source { CALLBACK, RECONCILE }

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletion.class);
    private static final String AGGREGATE = "Payment";

    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public PaymentCompletion(PaymentRepository payments, OutboxWriter outbox, MeterRegistry meters) {
        this.payments = payments;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Transactional
    public Result succeed(String paymentId, String providerTxnId, Source source) {
        return complete(paymentId, Status.SUCCEEDED, providerTxnId, null, source);
    }

    @Transactional
    public Result fail(String paymentId, String providerTxnId, String reason, Source source) {
        return complete(paymentId, Status.FAILED, providerTxnId, reason == null ? "DECLINED" : reason, source);
    }

    private Result complete(String paymentId, Status status, String providerTxnId, String reason, Source source) {
        Result result;
        if (payments.completeIfPending(paymentId, status, providerTxnId, reason, Instant.now()) == 0) {
            result = payments.findById(paymentId).isPresent() ? Result.ALREADY_COMPLETED : Result.NOT_FOUND;
        } else {
            // Chưa load entity trong transaction này → đọc ra giá trị mới nhất (sau UPDATE)
            Payment p = payments.findById(paymentId).orElseThrow();
            if (status == Status.SUCCEEDED) {
                outbox.write(Topics.PAYMENT_SUCCESS, AGGREGATE, p.getId(), p.getOrderId(), "PaymentSucceeded",
                    new PaymentSucceeded(p.getOrderId(), p.getId(), p.getAmount().toPlainString(), p.getCurrency()));
            } else {
                outbox.write(Topics.PAYMENT_FAILED, AGGREGATE, p.getId(), p.getOrderId(), "PaymentFailed",
                    new PaymentFailed(p.getOrderId(), reason));
            }
            log.info("Payment {} of order {} → {} via {} (txn {})", paymentId, p.getOrderId(), status, source, providerTxnId);
            result = Result.APPLIED;
        }
        meters.counter("payment.completion", "source", source.name().toLowerCase(),
            "result", result.name().toLowerCase()).increment();
        return result;
    }
}
