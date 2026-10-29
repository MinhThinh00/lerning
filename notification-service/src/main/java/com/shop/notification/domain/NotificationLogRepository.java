package com.shop.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

/**
 * Port: implement ở infrastructure.persistence.
 * Các method chuyển trạng thái trả về số dòng bị đổi: 0 = điều kiện không thỏa (đã có bên khác xử lý).
 */
public interface NotificationLogRepository {

    NotificationLog save(NotificationLog log);

    Optional<NotificationLog> findById(Long id);

    List<NotificationLog> findByOrderIdOrderByIdAsc(String orderId);

    /**
     * Nhận quyền gửi: PENDING → SENDING (attempts + 1). Cũng nhận lại dòng SENDING bị bỏ dở quá lâu
     * (worker chết giữa chừng, claimed_at < staleBefore). 1 = mình được gửi.
     */
    int claim(Long id, Instant now, Instant staleBefore);

    int markSent(Long id, String recipient, Instant now);

    /** Lỗi tạm thời: SENDING → PENDING, job thử lại sau. */
    int markRetry(Long id, String error, Instant now);

    /** Lỗi vĩnh viễn / hết lượt thử: SENDING → FAILED. */
    int markFailed(Long id, String error, Instant now);

    /** Việc cho retry job: PENDING chưa đụng tới từ trước {@code before}, hoặc SENDING bị bỏ dở. */
    List<Long> findDueIds(Instant before, Instant staleBefore, Pageable page);
}
