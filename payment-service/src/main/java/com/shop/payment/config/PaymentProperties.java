package com.shop.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Cấu hình cổng thanh toán GIẢ (docs/07 §9). Env PAYMENT_MOCK_SUCCESS_RATE. */
@ConfigurationProperties("payment.mock")
public record PaymentProperties(
    @DefaultValue("0.9") double successRate,
    @DefaultValue("100ms") Duration minDelay,
    @DefaultValue("500ms") Duration maxDelay) {

    public PaymentProperties {
        if (successRate < 0 || successRate > 1) {
            throw new IllegalArgumentException("payment.mock.success-rate must be in [0, 1]");
        }
    }
}
