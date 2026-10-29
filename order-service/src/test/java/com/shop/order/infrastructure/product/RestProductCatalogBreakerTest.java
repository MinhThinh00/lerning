package com.shop.order.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.shop.order.application.port.ProductCatalog.CatalogUnavailableException;
import com.shop.order.config.OrderProperties.ProductBreaker;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Breaker thật + HTTP server thật (JDK HttpServer): đếm được bao nhiêu request THỰC SỰ tới product-service.
 * CLOSED → (lỗi liên tiếp) OPEN, từ chối không gọi mạng → hết waitInOpen → HALF_OPEN → server khỏe → CLOSED.
 */
class RestProductCatalogBreakerTest {

    private static final String PRODUCTS = """
        [{"id":1,"sku":"SKU-1","name":"Phone","price":"100.00","currency":"VND"}]""";

    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private HttpServer server;
    private CircuitBreaker breaker;
    private RestProductCatalog catalog;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/products", exchange -> {
            hits.incrementAndGet();
            byte[] body = (status == 200 ? PRODUCTS : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        // cửa sổ 4 lời gọi, ≥ 50% lỗi → OPEN; OPEN 300ms; HALF_OPEN thử 2 lời gọi
        breaker = RestProductCatalog.breaker(new ProductBreaker(50, 100, Duration.ofSeconds(5), 4, 4,
            Duration.ofMillis(300), 2), new SimpleMeterRegistry());
        catalog = new RestProductCatalog("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), breaker);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void opensAfterRepeatedFailures_failsFastWithoutCallingProduct_thenRecovers() {
        assertThat(catalog.findByIds(List.of(1L))).hasSize(1);

        status = 503;
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> catalog.findByIds(List.of(1L))).isInstanceOf(CatalogUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(State.OPEN);   // 3/4 lời gọi lỗi

        int hitsWhenOpened = hits.get();
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> catalog.findByIds(List.of(1L)))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        }
        assertThat(hits.get()).as("OPEN: no request reaches product-service").isEqualTo(hitsWhenOpened);

        status = 200;
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().untilAsserted(
            () -> assertThat(catalog.findByIds(List.of(1L))).hasSize(1));   // lời gọi đầu sau waitInOpen → HALF_OPEN
        assertThat(catalog.findByIds(List.of(1L))).hasSize(1);
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void clientErrorsDoNotOpenTheBreaker() {
        status = 400;
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> catalog.findByIds(List.of(1L))).isInstanceOf(CatalogUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(hits.get()).isEqualTo(10);
    }
}
