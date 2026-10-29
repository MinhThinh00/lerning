package com.shop.payment.application;

import com.shop.payment.application.PaymentCompletion.Source;
import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.application.port.PaymentGateway.GatewayStatus;
import com.shop.payment.application.port.PaymentGateway.GatewayUnavailableException;
import com.shop.payment.config.PaymentProperties;
import com.shop.payment.domain.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đối soát (docs/10 T7 bước 4, ≈ NoIPNScheduler của SX): payment PENDING quá {@code pending-after} nghĩa là
 * callback có thể đã mất → hỏi thẳng cổng {@code GET status} rồi chốt bằng CÙNG method với callback
 * ({@link PaymentCompletion}). Callback về muộn sau đó → 0 dòng → bỏ qua.
 *
 * Cổng không biết giao dịch (NOT_FOUND: yêu cầu thu chưa từng tới cổng) → FAILED: chưa ai bị trừ tiền.
 * Cổng chết → dừng lượt này, lượt sau thử lại.
 */
@Component
public class PaymentReconcileJob {

    public static final String LOCK_NAME = "payment.reconcile";

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileJob.class);
    private static final int PAGE = 100;

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentCompletion completion;
    private final PaymentProperties props;

    public PaymentReconcileJob(PaymentRepository payments, PaymentGateway gateway, PaymentCompletion completion,
                               PaymentProperties props) {
        this.payments = payments;
        this.gateway = gateway;
        this.completion = completion;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval:30s}", initialDelayString = "${payment.reconcile.interval:30s}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void reconcile() {
        runOnce();
    }

    /** Một lượt, KHÔNG qua ShedLock (test gọi trực tiếp). @return số payment đã chốt */
    public int runOnce() {
        List<String> ids = payments.findPendingIdsCreatedBefore(
            Instant.now().minus(props.reconcile().pendingAfter()), PageRequest.of(0, PAGE));
        int completed = 0;
        for (String id : ids) {
            GatewayStatus st;
            try {
                st = gateway.status(id);
            } catch (GatewayUnavailableException e) {
                log.warn("Reconcile stopped: gateway unavailable ({})", e.getMessage());
                break;
            }
            PaymentCompletion.Result r = switch (st.state()) {
                case SUCCEEDED -> completion.succeed(id, st.providerTxnId(), Source.RECONCILE);
                case FAILED -> completion.fail(id, st.providerTxnId(), st.reason(), Source.RECONCILE);
                case NOT_FOUND -> completion.fail(id, null, "NOT_FOUND_AT_GATEWAY", Source.RECONCILE);
                case PENDING -> null;   // cổng chưa xử lý xong: lượt sau hỏi lại
            };
            if (r == PaymentCompletion.Result.APPLIED) {
                completed++;
            }
        }
        if (completed > 0) {
            log.warn("Reconcile completed {} payment(s) whose callback never arrived", completed);
        }
        return completed;
    }
}
