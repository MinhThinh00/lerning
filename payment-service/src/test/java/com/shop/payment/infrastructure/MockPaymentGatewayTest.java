package com.shop.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shop.payment.config.PaymentProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

    @Test
    void successRateZeroAlwaysDeclines() {
        MockPaymentGateway gateway = gateway(0.0);
        assertThat(IntStream.range(0, 50).mapToObj(i -> gateway.charge("o", BigDecimal.TEN, "VND")))
            .allSatisfy(r -> assertThat(r.success()).isFalse())
            .allSatisfy(r -> assertThat(r.reason()).isEqualTo("DECLINED"));
    }

    @Test
    void successRateOneAlwaysSucceeds() {
        MockPaymentGateway gateway = gateway(1.0);
        assertThat(IntStream.range(0, 50).mapToObj(i -> gateway.charge("o", BigDecimal.TEN, "VND")))
            .allSatisfy(r -> assertThat(r.success()).isTrue());
    }

    @Test
    void rejectsInvalidRate() {
        assertThatThrownBy(() -> new PaymentProperties(1.5, Duration.ZERO, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockPaymentGateway gateway(double rate) {
        return new MockPaymentGateway(new PaymentProperties(rate, Duration.ZERO, Duration.ZERO));
    }
}
