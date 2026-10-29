package com.shop.order.infrastructure.product;

import com.shop.order.application.port.ProductCatalog;
import com.shop.order.config.OrderProperties;
import com.shop.order.config.OrderProperties.ProductBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi GET /api/products?ids=... (public, không cần token: docs/03 §6) bằng RestClient của Spring 7.
 * Thay cho Feign + Consul trong docs (stack JHipster): stack viết tay không có Spring Cloud. Trong Docker,
 * "product-service" là tên DNS của compose, tự chia đều cho các replica.
 *
 * Hai lớp bảo vệ khi product-service có vấn đề:
 * <ul>
 *   <li>Timeout: không có nó, product-service treo → thread đặt hàng treo theo → cạn thread pool.</li>
 *   <li>Circuit breaker (Resilience4j): timeout vẫn bắt MỖI request chờ đủ 2s. Khi phần lớn lời gọi gần đây
 *       đều lỗi/chậm, breaker OPEN và từ chối ngay (503 trong vài ms, không gọi mạng), cho product-service
 *       thời gian hồi phục thay vì bị dội thêm request. Sau waitInOpen cho thử vài lời gọi (HALF_OPEN):
 *       ổn thì CLOSED lại, còn lỗi thì OPEN tiếp.</li>
 * </ul>
 * 4xx không tính là lỗi của product-service (là request của mình sai) nên không làm breaker mở.
 * Trạng thái breaker có ở metric {@code resilience4j_circuitbreaker_state{name="product-service"}}.
 */
@Component
class RestProductCatalog implements ProductCatalog {

    static final String BREAKER = "product-service";

    private static final Logger log = LoggerFactory.getLogger(RestProductCatalog.class);

    private final RestClient http;
    private final CircuitBreaker breaker;

    @Autowired
    RestProductCatalog(OrderProperties props, MeterRegistry meters) {
        this(props.productServiceUrl(), props.productTimeout(), breaker(props.productBreaker(), meters));
    }

    RestProductCatalog(String baseUrl, Duration timeout, CircuitBreaker breaker) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.breaker = breaker;
    }

    @Override
    public List<CatalogProduct> findByIds(Collection<Long> ids) {
        String csv = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            CatalogProduct[] body = breaker.executeSupplier(
                () -> http.get().uri("/api/products?ids={ids}", csv).retrieve().body(CatalogProduct[].class));
            return body == null ? List.of() : Arrays.asList(body);
        } catch (CallNotPermittedException open) {
            throw new CatalogUnavailableException("product-service circuit breaker is OPEN, failing fast", open);
        } catch (RestClientException ex) {
            throw new CatalogUnavailableException("product-service call failed: " + ex.getMessage(), ex);
        }
    }

    static CircuitBreaker breaker(ProductBreaker cfg, MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(cfg.slidingWindowSize())
            .minimumNumberOfCalls(cfg.minimumCalls())
            .failureRateThreshold(cfg.failureRateThreshold())
            .slowCallRateThreshold(cfg.slowCallRateThreshold())
            .slowCallDurationThreshold(cfg.slowCallThreshold())
            .waitDurationInOpenState(cfg.waitInOpen())
            .permittedNumberOfCallsInHalfOpenState(cfg.halfOpenCalls())
            .recordExceptions(RestClientException.class)
            .ignoreExceptions(HttpClientErrorException.class)
            .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        CircuitBreaker breaker = registry.circuitBreaker(BREAKER);
        breaker.getEventPublisher().onStateTransition(e -> log.warn("Circuit breaker {}: {}",
            e.getCircuitBreakerName(), e.getStateTransition()));
        return breaker;
    }
}
