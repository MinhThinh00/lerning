package com.shop.payment.application;

/** Payload event (docs/05 §3). Tiền dạng string. Chỉ cần khớp tên field với bên kia, không chia sẻ class. */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** Nhận: order.confirmed */
    public record OrderConfirmed(String orderId, String userId, String totalAmount, String currency) {
    }

    /** Phát: payment.success */
    public record PaymentSucceeded(String orderId, String paymentId, String amount, String currency) {
    }

    /** Phát: payment.failed */
    public record PaymentFailed(String orderId, String reason) {
    }
}
