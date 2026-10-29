package com.shop.payment.application;

import com.shop.common.messaging.Topics;
import com.shop.common.messaging.outbox.OutboxWriter;
import com.shop.common.web.error.NotFoundException;
import com.shop.payment.application.PaymentEvents.PaymentFailed;
import com.shop.payment.application.PaymentEvents.PaymentSucceeded;
import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.application.port.PaymentGateway.ChargeResult;
import com.shop.payment.domain.model.Payment;
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
 * Bước thu tiền của saga: order.confirmed → thu tiền → payment.success / payment.failed.
 *
 * Gọi cổng thanh toán NGOÀI transaction: không giữ connection DB trong lúc chờ mạng (docs/10 checklist).
 * Chống thu tiền 2 lần: đã có payment cho đơn → bỏ qua; 2 consumer chạy song song → unique order_id chặn bên sau.
 * Với cổng thật, idempotency phải đẩy sang cả phía cổng (gửi orderId làm idempotency key): xem docs/10 T7.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String AGGREGATE = "Payment";

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments, PaymentGateway gateway, OutboxWriter outbox, TransactionTemplate tx) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void onOrderConfirmed(String orderId, BigDecimal amount, String currency) {
        if (payments.findByOrderId(orderId).isPresent()) {
            log.info("Order {} already charged, skip redelivered order.confirmed", orderId);
            return;
        }
        ChargeResult result = gateway.charge(orderId, amount, currency);
        try {
            tx.executeWithoutResult(s -> {
                if (result.success()) {
                    Payment p = payments.saveAndFlush(Payment.succeeded(orderId, amount, currency));
                    outbox.write(Topics.PAYMENT_SUCCESS, AGGREGATE, p.getId(), orderId, "PaymentSucceeded",
                        new PaymentSucceeded(orderId, p.getId(), amount.toPlainString(), currency));
                } else {
                    Payment p = payments.saveAndFlush(Payment.failed(orderId, amount, currency, result.reason()));
                    outbox.write(Topics.PAYMENT_FAILED, AGGREGATE, p.getId(), orderId, "PaymentFailed",
                        new PaymentFailed(orderId, result.reason()));
                }
            });
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Order {} charged concurrently by another consumer, skip", orderId);
        }
    }

    @Transactional(readOnly = true)
    public PaymentView findByOrder(String orderId) {
        return payments.findByOrderId(orderId).map(PaymentView::from)
            .orElseThrow(() -> new NotFoundException("Payment of order", orderId));
    }

    public record PaymentView(String id, String orderId, BigDecimal amount, String currency, String status,
                              String reason, Instant createdAt) {

        static PaymentView from(Payment p) {
            return new PaymentView(p.getId(), p.getOrderId(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getReason(), p.getCreatedAt());
        }
    }
}
