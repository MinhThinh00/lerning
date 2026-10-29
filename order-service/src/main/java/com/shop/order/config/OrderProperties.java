package com.shop.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pendingTimeout     PENDING quá lâu (inventory không trả lời) → hủy TIMEOUT
 * @param paymentTimeout     CONFIRMED quá lâu (payment không trả lời) → hủy TIMEOUT, hàng được nhả
 * @param productServiceUrl  gốc URL product-service (IDE: localhost:8082, Docker: http://product-service:8082)
 * @param productTimeout     chờ product-service tối đa; quá thì trả 503 thay vì treo request đặt hàng
 * @param productBreaker     circuit breaker quanh lời gọi product-service
 */
@ConfigurationProperties("order")
public record OrderProperties(
    @DefaultValue("10m") Duration pendingTimeout,
    @DefaultValue("15m") Duration paymentTimeout,
    @DefaultValue("http://localhost:8082") String productServiceUrl,
    @DefaultValue("2s") Duration productTimeout,
    @DefaultValue ProductBreaker productBreaker) {

    /**
     * Ngưỡng tính trên {@code slidingWindowSize} lời gọi gần nhất (khi đã có ít nhất {@code minimumCalls}):
     * lỗi ≥ failureRateThreshold% hoặc chậm (> slowCallThreshold) ≥ slowCallRateThreshold% → OPEN.
     * OPEN: từ chối ngay, không gọi mạng, trong waitInOpen. Hết hạn → HALF_OPEN cho thử halfOpenCalls lời gọi.
     */
    public record ProductBreaker(
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("80") float slowCallRateThreshold,
        @DefaultValue("1500ms") Duration slowCallThreshold,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumCalls,
        @DefaultValue("10s") Duration waitInOpen,
        @DefaultValue("3") int halfOpenCalls) {
    }
}
