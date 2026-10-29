package com.shop.notification.application;

import com.shop.notification.application.port.EmailSender;
import com.shop.notification.application.port.UserDirectory;
import com.shop.notification.application.port.UserDirectory.Contact;
import com.shop.notification.application.port.UserDirectory.UserDirectoryUnavailableException;
import com.shop.notification.config.NotificationProperties;
import com.shop.notification.domain.NotificationLog;
import com.shop.notification.domain.NotificationLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gửi 1 thông báo đã ghi trong notification_log. Chạy NGOÀI transaction của consumer: gửi email là side effect
 * ra ngoài, không rollback được, nên ghi "cần gửi" trước (cùng transaction với inbox), gửi sau khi commit.
 *
 * <pre>
 *  1. claim (PENDING → SENDING)        chỉ 1 worker (consumer hoặc retry job, replica nào cũng vậy) được gửi
 *  2. hỏi user-service email của khách  token máy (M2M)
 *  3. gửi SMTP
 *  4. SENT | lỗi tạm thời → PENDING (retry job thử lại) | không có email / hết lượt → FAILED
 * </pre>
 * At-least-once: chết giữa bước 3 và 4 thì sau claimTimeout dòng được nhận lại và gửi lần nữa → khách có thể
 * nhận 2 mail. Exactly-once với SMTP là không thể; chấp nhận trùng hiếm còn hơn mất thông báo.
 *
 * Không ném exception ra ngoài: consumer Kafka không phải retry vì email (đã có retry job lo).
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationLogRepository logs;
    private final UserDirectory users;
    private final EmailSender email;
    private final NotificationProperties props;
    private final MeterRegistry meters;

    public NotificationDispatcher(NotificationLogRepository logs, UserDirectory users, EmailSender email,
                                  NotificationProperties props, MeterRegistry meters) {
        this.logs = logs;
        this.users = users;
        this.email = email;
        this.props = props;
        this.meters = meters;
    }

    public void dispatch(Long id) {
        Instant now = Instant.now();
        if (logs.claim(id, now, now.minus(props.claimTimeout())) == 0) {
            return;   // worker khác đang gửi / đã gửi xong
        }
        NotificationLog n = logs.findById(id).orElseThrow();
        try {
            Optional<Contact> contact = users.findContact(n.getUserId());
            if (contact.isEmpty() || contact.get().email() == null || contact.get().email().isBlank()) {
                finish(id, logs.markFailed(id, "NO_CONTACT: user has no email", Instant.now()), "no_contact");
                log.warn("Notification {} for user {} dropped: no email address", id, n.getUserId());
                return;
            }
            Contact c = contact.get();
            email.send(c.email(), subject(n), body(n, c));
            finish(id, logs.markSent(id, c.email(), Instant.now()), "sent");
            log.info("Notification {} ({}) for order {} sent to user {}", id, n.getType(), n.getOrderId(), n.getUserId());
        } catch (RuntimeException e) {
            String error = describe(e);
            if (n.getAttempts() >= props.maxAttempts()) {
                finish(id, logs.markFailed(id, error, Instant.now()), "failed");
                log.error("Notification {} failed after {} attempts: {}", id, n.getAttempts(), error);
            } else {
                finish(id, logs.markRetry(id, error, Instant.now()), "retry");
                log.warn("Notification {} attempt {} failed, will retry: {}", id, n.getAttempts(), error);
            }
        }
    }

    private void finish(Long id, int updated, String outcome) {
        if (updated == 0) {
            // Bị nhận lại sau claimTimeout trong lúc mình còn đang gửi: worker kia sẽ ghi kết quả
            log.warn("Notification {} was reclaimed by another worker while sending", id);
        }
        meters.counter("notification.dispatch", "outcome", outcome).increment();
    }

    private static String subject(NotificationLog n) {
        return switch (n.getType()) {
            case ORDER_PAID -> "[Shop] Đơn hàng " + n.getOrderId() + " đã thanh toán";
            case ORDER_CANCELLED -> "[Shop] Đơn hàng " + n.getOrderId() + " đã bị hủy";
        };
    }

    private static String body(NotificationLog n, Contact c) {
        String name = c.firstName() == null || c.firstName().isBlank() ? "bạn" : c.firstName();
        return "Chào " + name + ",\n\n" + n.getMessage() + ".\n\nCảm ơn bạn đã mua sắm tại Shop.\n";
    }

    private static String describe(RuntimeException e) {
        String kind = e instanceof UserDirectoryUnavailableException ? "USER_SERVICE: " : "SEND: ";
        String msg = kind + e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
