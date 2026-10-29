package com.shop.payment.web.rest;

import com.shop.payment.application.callback.CallbackSignature;
import com.shop.payment.application.callback.PaymentCallbackService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/payments/callback: cổng gọi về (IPN). Không có Bearer token (cổng không có tài khoản Keycloak):
 * xác thực bằng chữ ký HMAC của body. Nhận body dạng byte[] để verify đúng byte cổng đã ký.
 *
 * 200 cả khi callback trùng (ALREADY_COMPLETED): trả lỗi thì cổng sẽ gửi lại mãi.
 */
@RestController
@RequestMapping("/api/payments")
class PaymentCallbackController {

    private final PaymentCallbackService callbacks;

    PaymentCallbackController(PaymentCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping(path = "/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> callback(@RequestBody byte[] body,
                                 @RequestHeader(name = CallbackSignature.HEADER, required = false) String signature) {
        return Map.of("result", callbacks.handle(body, signature).name());
    }
}
