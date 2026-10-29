package com.shop.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param gateway     cách nói chuyện với cổng thanh toán (thật hay giả đều như nhau)
 * @param fakeGateway cổng GIẢ chạy ngay trong payment-service (docs/10 T7), tắt khi có cổng thật
 * @param reconcile   job đối soát payment PENDING không nhận được callback
 */
@ConfigurationProperties("payment")
public record PaymentProperties(
    @DefaultValue Gateway gateway,
    @DefaultValue FakeGateway fakeGateway,
    @DefaultValue Reconcile reconcile) {

    /**
     * @param baseUrl     gốc URL API của cổng
     * @param callbackUrl URL cổng gọi lại (IPN) khi có kết quả: phải là địa chỉ cổng nhìn thấy được
     * @param hmacSecret  secret dùng chung với cổng để ký callback (env PAYMENT_GATEWAY_HMAC_SECRET)
     * @param timeout     connect + read timeout khi gọi cổng
     */
    public record Gateway(
        @DefaultValue("http://localhost:8085/fake-gateway") String baseUrl,
        @DefaultValue("http://localhost:8085/api/payments/callback") String callbackUrl,
        @DefaultValue("dev-payment-hmac-secret") String hmacSecret,
        @DefaultValue("2s") Duration timeout) {
    }

    /**
     * @param successRate   xác suất giao dịch thành công (env PAYMENT_MOCK_SUCCESS_RATE)
     * @param minDelay      cổng xử lý xong sau min..max rồi mới gọi callback
     * @param noCallbackRate xác suất cổng KHÔNG gọi callback (mất IPN) → chỉ reconcile cứu được
     * @param maxCallbacks  gọi callback 1..max lần, có lúc đồng thời (cổng thật cũng retry IPN)
     */
    public record FakeGateway(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.9") double successRate,
        @DefaultValue("100ms") Duration minDelay,
        @DefaultValue("3s") Duration maxDelay,
        @DefaultValue("0.1") double noCallbackRate,
        @DefaultValue("3") int maxCallbacks) {

        public FakeGateway {
            requireRate("payment.fake-gateway.success-rate", successRate);
            requireRate("payment.fake-gateway.no-callback-rate", noCallbackRate);
            if (maxCallbacks < 1) {
                throw new IllegalArgumentException("payment.fake-gateway.max-callbacks must be >= 1");
            }
        }
    }

    /**
     * @param interval     chu kỳ chạy job
     * @param pendingAfter chỉ hỏi cổng về payment PENDING lâu hơn ngưỡng này (đủ lâu để callback bình thường đã về)
     */
    public record Reconcile(
        @DefaultValue("30s") Duration interval,
        @DefaultValue("2m") Duration pendingAfter) {
    }

    private static void requireRate(String name, double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}
