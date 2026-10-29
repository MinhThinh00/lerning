package com.shop.inventory.application.reservation;

/** Kết quả giữ hàng. Hết hàng là kết quả nghiệp vụ bình thường, không phải exception. */
public sealed interface ReserveOutcome {

    /** Tag cho metric inventory.reserve{outcome}. */
    String tag();

    record Reserved() implements ReserveOutcome {
        @Override
        public String tag() {
            return "success";
        }
    }

    record Rejected(Reason reason, long productId) implements ReserveOutcome {
        @Override
        public String tag() {
            return reason.name().toLowerCase();
        }
    }

    /** Đơn đã được xử lý trước đó (redelivery, hoặc đã bị hủy trước khi tới): không làm gì thêm, không phát event. */
    record AlreadyHandled() implements ReserveOutcome {
        @Override
        public String tag() {
            return "duplicate";
        }
    }

    enum Reason { OUT_OF_STOCK, LOCK_TIMEOUT, CONFLICT, NOT_FOUND }
}
