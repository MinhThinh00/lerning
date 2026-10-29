package com.shop.payment.application.port;

import java.math.BigDecimal;

/**
 * Port tới cổng thanh toán kiểu bất đồng bộ (VNPay, OnePay, Stripe...): gửi yêu cầu thu → cổng trả "đã nhận"
 * → kết quả về sau qua callback (IPN). Callback có thể về nhiều lần, đồng thời, hoặc không về:
 * {@link #status} để đối soát.
 *
 * paymentId là mã tham chiếu phía mình (merchant reference): cổng dùng nó làm idempotency key, gửi lại cùng
 * paymentId không tạo giao dịch thứ hai.
 */
public interface PaymentGateway {

    /** @throws GatewayUnavailableException cổng không trả lời được */
    void submit(ChargeRequest request);

    /** @throws GatewayUnavailableException cổng không trả lời được */
    GatewayStatus status(String paymentId);

    record ChargeRequest(String paymentId, String orderId, BigDecimal amount, String currency) {
    }

    /** @param providerTxnId mã giao dịch phía cổng, có khi đã có kết quả */
    record GatewayStatus(State state, String providerTxnId, String reason) {

        public enum State { PENDING, SUCCEEDED, FAILED, NOT_FOUND }
    }

    class GatewayUnavailableException extends RuntimeException {

        public GatewayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
