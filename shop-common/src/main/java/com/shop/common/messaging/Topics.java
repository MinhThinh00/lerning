package com.shop.common.messaging;

/**
 * Tên topic. Phải khớp deploy/infra/kafka/topics.sh (topic do job kafka-init tạo, code không khai báo NewTopic).
 * Key của mọi topic = orderId (docs/05-MESSAGING.md §1).
 */
public final class Topics {

    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_FAILED = "inventory.failed";
    public static final String PAYMENT_SUCCESS = "payment.success";
    public static final String PAYMENT_FAILED = "payment.failed";

    /** Hậu tố DLT, DeadLetterPublishingRecoverer gửi message lỗi sang {@code <topic>.DLT}. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
