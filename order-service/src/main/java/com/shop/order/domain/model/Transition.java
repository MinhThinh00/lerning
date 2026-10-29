package com.shop.order.domain.model;

/**
 * Kết quả của 1 lần yêu cầu chuyển trạng thái. Method domain KHÔNG throw cho những tình huống có thể xảy ra
 * trong hệ phân tán (event trùng, event đến muộn): throw → retry 3 lần → DLT, trong khi dữ liệu không hề sai
 * (lỗi của plan v1, docs/07 §3).
 */
public enum Transition {
    /** Đã chuyển trạng thái → phát event tương ứng. */
    APPLIED,
    /** Không đổi gì (trùng / muộn / không áp dụng) → không phát event, coi là xử lý thành công. */
    IGNORED,
    /** payment.success cho đơn đã CANCELLED: tiền đã trừ nhưng không giao hàng → cần hoàn tiền (ngoài phạm vi). */
    REFUND_REQUIRED
}
