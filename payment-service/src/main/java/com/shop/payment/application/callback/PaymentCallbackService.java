package com.shop.payment.application.callback;

import com.shop.common.web.error.BusinessException;
import com.shop.common.web.error.NotFoundException;
import com.shop.payment.application.PaymentCompletion;
import com.shop.payment.application.PaymentCompletion.Result;
import com.shop.payment.application.PaymentCompletion.Source;
import com.shop.payment.config.PaymentProperties;
import com.shop.payment.domain.model.Payment;
import com.shop.payment.domain.repository.PaymentRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Xử lý callback (IPN) từ cổng (docs/10 T7 bước 3). Thứ tự kiểm tra:
 * <ol>
 *   <li>Chữ ký HMAC trên byte thô của body → sai: 401, không parse, không đụng DB.</li>
 *   <li>Body hợp lệ (status SUCCEEDED/FAILED, có merchantRef) → sai: 400.</li>
 *   <li>Payment tồn tại → không: 404.</li>
 *   <li>Số tiền/tiền tệ khớp với payment → lệch: 409 và log ERROR, không chốt (cần người xem).</li>
 *   <li>{@link PaymentCompletion}: chỉ 1 callback chuyển được PENDING → kết quả; các callback trùng trả 200
 *       ALREADY_COMPLETED để cổng thôi gửi lại.</li>
 * </ol>
 */
@Service
public class PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);

    private final CallbackSignature signature;
    private final JsonMapper json;
    private final PaymentRepository payments;
    private final PaymentCompletion completion;

    public PaymentCallbackService(PaymentProperties props, JsonMapper json, PaymentRepository payments,
                                  PaymentCompletion completion) {
        this.signature = new CallbackSignature(props.gateway().hmacSecret());
        this.json = json;
        this.payments = payments;
        this.completion = completion;
    }

    public Result handle(byte[] body, String signatureHeader) {
        if (!signature.verify(body, signatureHeader)) {
            log.warn("Rejected payment callback with invalid signature");
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid callback signature");
        }
        GatewayCallback cb = parse(body);
        Payment payment = payments.findById(cb.merchantRef())
            .orElseThrow(() -> new NotFoundException("Payment", cb.merchantRef()));
        if (!amountMatches(payment, cb)) {
            log.error("Callback amount mismatch for payment {}: expected {} {}, got {} {}", payment.getId(),
                payment.getAmount(), payment.getCurrency(), cb.amount(), cb.currency());
            throw new BusinessException(HttpStatus.CONFLICT, "AMOUNT_MISMATCH", "Callback amount does not match payment");
        }
        return "SUCCEEDED".equals(cb.status())
            ? completion.succeed(payment.getId(), cb.providerTxnId(), Source.CALLBACK)
            : completion.fail(payment.getId(), cb.providerTxnId(), cb.reason(), Source.CALLBACK);
    }

    private GatewayCallback parse(byte[] body) {
        GatewayCallback cb;
        try {
            cb = json.readValue(body, GatewayCallback.class);
        } catch (JacksonException e) {
            throw invalid("Malformed callback body");
        }
        if (cb.merchantRef() == null || !("SUCCEEDED".equals(cb.status()) || "FAILED".equals(cb.status()))) {
            throw invalid("Callback needs merchantRef and status SUCCEEDED|FAILED");
        }
        return cb;
    }

    private static boolean amountMatches(Payment p, GatewayCallback cb) {
        try {
            return cb.amount() != null && new BigDecimal(cb.amount()).compareTo(p.getAmount()) == 0
                && p.getCurrency().equals(cb.currency());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK", message);
    }
}
