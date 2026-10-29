package com.shop.payment.application;

import com.shop.common.web.error.NotFoundException;
import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.application.port.PaymentGateway.ChargeRequest;
import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.model.Payment.Status;
import com.shop.payment.domain.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bước thu tiền của saga, nửa đầu: order.confirmed → payment PENDING → gửi yêu cầu thu sang cổng.
 * Nửa sau (kết quả) về qua callback hoặc reconcile → {@link PaymentCompletion}.
 *
 * - Ghi PENDING TRƯỚC khi gọi cổng: nếu chết ngay sau khi gọi, reconcile vẫn biết có giao dịch để hỏi lại.
 *   Gọi cổng trước rồi mới ghi thì chết ở giữa = khách bị trừ tiền mà hệ thống không hề biết.
 * - Gọi cổng NGOÀI transaction: không giữ connection DB trong lúc chờ mạng.
 * - Cổng lỗi → để exception thoát ra → Kafka retry; lần giao lại thấy PENDING và gửi lại. Gửi lại an toàn vì
 *   cổng idempotent theo paymentId. Hết retry (DLT) thì reconcile thấy cổng không biết giao dịch → FAILED.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, PaymentGateway gateway, TransactionTemplate tx) {
        this.payments = payments;
        this.gateway = gateway;
        this.tx = tx;
    }

    public void onOrderConfirmed(String orderId, BigDecimal amount, String currency) {
        Payment payment = payments.findByOrderId(orderId).orElse(null);
        if (payment == null) {
            try {
                payment = tx.execute(s -> payments.saveAndFlush(Payment.pending(orderId, amount, currency)));
            } catch (DataIntegrityViolationException duplicate) {
                log.info("Payment for order {} created concurrently by another consumer, skip", orderId);
                return;
            }
        } else if (payment.getStatus() != Status.PENDING) {
            log.info("Order {} already {} (payment {}), skip redelivered order.confirmed",
                orderId, payment.getStatus(), payment.getId());
            return;
        } else {
            log.info("Payment {} of order {} still PENDING, re-submitting (gateway is idempotent by paymentId)",
                payment.getId(), orderId);
        }
        gateway.submit(new ChargeRequest(payment.getId(), orderId, payment.getAmount(), payment.getCurrency()));
    }

    @Transactional(readOnly = true)
    public PaymentView findByOrder(String orderId) {
        return payments.findByOrderId(orderId).map(PaymentView::from)
            .orElseThrow(() -> new NotFoundException("Payment of order", orderId));
    }

    public record PaymentView(String id, String orderId, BigDecimal amount, String currency, String status,
                              String providerTxnId, String reason, Instant createdAt, Instant updatedAt) {

        static PaymentView from(Payment p) {
            return new PaymentView(p.getId(), p.getOrderId(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getProviderTxnId(), p.getReason(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }
}
