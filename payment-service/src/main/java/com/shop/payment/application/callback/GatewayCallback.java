package com.shop.payment.application.callback;

/**
 * Body callback (IPN) cổng gửi về. Tiền dạng string như event (docs/05 §3).
 *
 * @param merchantRef   paymentId phía mình
 * @param providerTxnId mã giao dịch phía cổng
 * @param status        SUCCEEDED | FAILED
 * @param reason        lý do khi FAILED
 */
public record GatewayCallback(String merchantRef, String providerTxnId, String status, String reason,
                              String amount, String currency) {
}
