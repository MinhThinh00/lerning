package com.shop.payment.infrastructure.fakegateway;

import com.shop.payment.application.callback.CallbackSignature;
import com.shop.payment.application.callback.GatewayCallback;
import com.shop.payment.config.PaymentProperties;
import com.shop.payment.config.PaymentProperties.FakeGateway;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cổng thanh toán GIẢ (docs/10 T7 bước 2), chạy ngay trong payment-service, đóng vai một hệ thống BÊN NGOÀI:
 * payment-service chỉ nói chuyện với nó qua HTTP (HttpPaymentGateway), y như với cổng thật.
 *
 * Hành vi khó chịu giống cổng thật, để code phía mình phải chịu được:
 * <ul>
 *   <li>Trả 202 ngay, xử lý sau min..max-delay.</li>
 *   <li>Callback 1..max-callbacks lần, khoảng một nửa số lần là bắn ĐỒNG THỜI.</li>
 *   <li>Với xác suất no-callback-rate thì KHÔNG gọi callback nào (mất IPN) → chỉ reconcile chốt được.</li>
 *   <li>Idempotent theo merchantRef: gửi lại cùng paymentId không tạo giao dịch mới.</li>
 * </ul>
 * Trạng thái giữ trong bộ nhớ: restart là mất → reconcile thấy NOT_FOUND → FAILED (chưa ai bị trừ tiền).
 * Tắt bằng {@code payment.fake-gateway.enabled=false} khi tích hợp cổng thật.
 */
@RestController
@RequestMapping("/fake-gateway")
@ConditionalOnProperty(name = "payment.fake-gateway.enabled", havingValue = "true", matchIfMissing = true)
class FakeGatewayController {

    record CreateCharge(String merchantRef, String orderId, String amount, String currency, String callbackUrl) {
    }

    record Charge(String merchantRef, String amount, String currency, String callbackUrl,
                  String status, String providerTxnId, String reason) {

        Charge complete(String newStatus, String txnId, String why) {
            return new Charge(merchantRef, amount, currency, callbackUrl, newStatus, txnId, why);
        }
    }

    record ChargeStatus(String status, String providerTxnId, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(FakeGatewayController.class);

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final FakeGateway cfg;
    private final CallbackSignature signature;
    private final JsonMapper json;
    private final RestClient http;

    FakeGatewayController(PaymentProperties props, JsonMapper json) {
        this.cfg = props.fakeGateway();
        this.signature = new CallbackSignature(props.gateway().hmacSecret());
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @PostMapping("/charges")
    ResponseEntity<ChargeStatus> create(@RequestBody CreateCharge req) {
        Charge fresh = new Charge(req.merchantRef(), req.amount(), req.currency(), req.callbackUrl(), "PENDING", null, null);
        Charge existing = charges.putIfAbsent(req.merchantRef(), fresh);
        if (existing == null) {
            workers.submit(() -> process(req.merchantRef()));
        }
        Charge current = charges.get(req.merchantRef());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toStatus(current));
    }

    @GetMapping("/charges/{merchantRef}")
    ResponseEntity<ChargeStatus> status(@PathVariable String merchantRef) {
        Charge c = charges.get(merchantRef);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(toStatus(c));
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    private void process(String ref) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sleep(randomBetween(cfg.minDelay(), cfg.maxDelay()));
        boolean success = rnd.nextDouble() < cfg.successRate();
        Charge done = charges.computeIfPresent(ref, (k, c) -> c.complete(success ? "SUCCEEDED" : "FAILED",
            "FGW-" + UUID.randomUUID(), success ? null : "DECLINED"));
        if (done == null) {
            return;
        }
        if (rnd.nextDouble() < cfg.noCallbackRate()) {
            log.info("[fake-gateway] {} → {}, dropping callback (simulated lost IPN)", ref, done.status());
            return;
        }
        int times = rnd.nextInt(1, cfg.maxCallbacks() + 1);
        boolean concurrent = times > 1 && rnd.nextBoolean();
        log.info("[fake-gateway] {} → {}, sending {} callback(s){}", ref, done.status(), times, concurrent ? " concurrently" : "");
        for (int i = 0; i < times; i++) {
            if (concurrent) {
                workers.submit(() -> sendCallback(done));
            } else {
                sendCallback(done);
                sleep(Duration.ofMillis(rnd.nextLong(50, 300)));
            }
        }
    }

    private void sendCallback(Charge c) {
        byte[] body = json.writeValueAsBytes(
            new GatewayCallback(c.merchantRef(), c.providerTxnId(), c.status(), c.reason(), c.amount(), c.currency()));
        try {
            http.post().uri(c.callbackUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header(CallbackSignature.HEADER, signature.sign(body))
                .body(body)
                .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // Cổng thật sẽ retry IPN theo lịch; bản giả bỏ qua: reconcile là lưới an toàn
            log.warn("[fake-gateway] callback for {} failed: {}", c.merchantRef(), e.getMessage());
        }
    }

    private static ChargeStatus toStatus(Charge c) {
        return new ChargeStatus(c.status(), c.providerTxnId(), c.reason());
    }

    private static Duration randomBetween(Duration min, Duration max) {
        long lo = min.toMillis();
        long hi = Math.max(lo, max.toMillis());
        return Duration.ofMillis(lo == hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
