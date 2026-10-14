package com.shop.common.web.error;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ có chủ đích → trả về client với status + code ổn định.
 * code là chuỗi máy đọc được (FE/service khác dựa vào để xử lý), message cho người đọc.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
