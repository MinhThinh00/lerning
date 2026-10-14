package com.shop.common.web.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Mọi lỗi trả về dạng ProblemDetail (RFC 9457, Content-Type application/problem+json):
 * <pre>
 * { "type": "about:blank", "title": "Not Found", "status": 404,
 *   "detail": "UserProfile abc not found", "instance": "/api/...", "code": "NOT_FOUND" }
 * </pre>
 * ResponseEntityExceptionHandler đã lo sẵn các lỗi chuẩn của Spring MVC (400, 405, 415...).
 * Được đăng ký bởi ShopCommonAutoConfiguration, không cần component scan.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode());
        return pd;
    }

    /**
     * @PreAuthorize từ chối → AccessDeniedException ném ra từ trong controller.
     * Phải bắt riêng, nếu không handler Exception bên dưới sẽ biến nó thành 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        pd.setProperty("code", "ACCESS_DENIED");
        return pd;
    }

    /** Lỗi không lường trước: log đầy đủ, nhưng không lộ stacktrace/chi tiết ra ngoài. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        pd.setProperty("code", "INTERNAL_ERROR");
        return pd;
    }

    /** @Valid lỗi → 400 kèm danh sách field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field", f.getField(), "message", Objects.requireNonNullElse(f.getDefaultMessage(), "")))
            .toList();
        ProblemDetail pd = ex.getBody();
        pd.setProperty("code", "VALIDATION_FAILED");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }
}
