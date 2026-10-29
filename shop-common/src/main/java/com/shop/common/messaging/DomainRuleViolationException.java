package com.shop.common.messaging;

/**
 * Event hợp lệ về cú pháp nhưng vi phạm quy tắc nghiệp vụ, retry không làm nó đúng ra
 * → error handler đẩy sang DLT ngay. Không dùng cho "event đến muộn hợp lệ": trường hợp đó
 * listener xử lý rồi return bình thường (docs/07-ORDER-SERVICE.md).
 */
public class DomainRuleViolationException extends RuntimeException {

    public DomainRuleViolationException(String message) {
        super(message);
    }
}
