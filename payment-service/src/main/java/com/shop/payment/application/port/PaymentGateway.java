package com.shop.payment.application.port;

import java.math.BigDecimal;

/** Port tới cổng thanh toán. Phase 6 dùng bản giả (MockPaymentGateway); cổng thật/fake-gateway: docs/10 T7. */
public interface PaymentGateway {

    ChargeResult charge(String orderId, BigDecimal amount, String currency);

    /** @param reason null khi thành công */
    record ChargeResult(boolean success, String reason) {
    }
}
