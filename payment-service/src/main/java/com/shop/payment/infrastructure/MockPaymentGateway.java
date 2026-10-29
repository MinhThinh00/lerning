package com.shop.payment.infrastructure;

import com.shop.payment.application.port.PaymentGateway;
import com.shop.payment.config.PaymentProperties;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Cổng thanh toán GIẢ (docs/07 §9): trễ ngẫu nhiên min..max, thành công với xác suất success-rate.
 * Đủ để chạy saga và các kịch bản lỗi (PAYMENT_MOCK_SUCCESS_RATE=0 → mọi đơn bị hủy, hàng được nhả).
 */
@Component
class MockPaymentGateway implements PaymentGateway {

    private final PaymentProperties props;

    MockPaymentGateway(PaymentProperties props) {
        this.props = props;
    }

    @Override
    public ChargeResult charge(String orderId, BigDecimal amount, String currency) {
        sleep();
        return ThreadLocalRandom.current().nextDouble() < props.successRate()
            ? new ChargeResult(true, null)
            : new ChargeResult(false, "DECLINED");
    }

    private void sleep() {
        long min = props.minDelay().toMillis();
        long max = Math.max(min, props.maxDelay().toMillis());
        try {
            Thread.sleep(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
