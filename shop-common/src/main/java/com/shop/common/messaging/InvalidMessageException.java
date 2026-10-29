package com.shop.common.messaging;

/**
 * Message hỏng / sai schema. Retry không bao giờ tự khỏi → error handler đẩy thẳng sang DLT
 * (đăng ký not-retryable trong {@link com.shop.common.messaging.kafka.KafkaErrorHandling}).
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
