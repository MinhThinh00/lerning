package com.shop.notification.application;

import com.shop.common.messaging.inbox.InboxGuard;
import com.shop.notification.domain.NotificationLog;
import com.shop.notification.domain.NotificationLog.Type;
import com.shop.notification.domain.NotificationLogRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Báo khách khi đơn PAID / CANCELLED (docs/07 §10). Tiêu thụ thẳng domain event, không có topic notification riêng.
 *
 * Bước 1 (ở đây, 1 transaction): Inbox + ghi notification_log PENDING. Kafka giao lại order.paid → Inbox chặn
 * → không tạo thông báo thứ hai.
 * Bước 2 (sau commit): {@link NotificationDispatcher} gửi email. Tách ra vì gửi email không rollback được.
 */
@Service
public class NotificationService {

    private final NotificationLogRepository logs;
    private final InboxGuard inbox;

    public NotificationService(NotificationLogRepository logs, InboxGuard inbox) {
        this.logs = logs;
        this.inbox = inbox;
    }

    /** @return id thông báo cần gửi; empty nếu event đã xử lý rồi */
    @Transactional
    public Optional<Long> orderPaid(String eventId, String orderId, String userId, String totalAmount, String currency) {
        return record(eventId, "notification:order.paid", orderId, userId, Type.ORDER_PAID,
            "Đơn hàng " + orderId + " đã thanh toán thành công (" + totalAmount + " " + currency + ")");
    }

    /** @return id thông báo cần gửi; empty nếu event đã xử lý rồi */
    @Transactional
    public Optional<Long> orderCancelled(String eventId, String orderId, String userId, String reason) {
        return record(eventId, "notification:order.cancelled", orderId, userId, Type.ORDER_CANCELLED,
            "Đơn hàng " + orderId + " đã bị hủy (" + reason + ")");
    }

    @Transactional(readOnly = true)
    public List<NotificationLog> byOrder(String orderId) {
        return logs.findByOrderIdOrderByIdAsc(orderId);
    }

    private Optional<Long> record(String eventId, String consumer, String orderId, String userId, Type type, String message) {
        if (!inbox.tryMarkProcessed(eventId, consumer)) {
            return Optional.empty();
        }
        return Optional.of(logs.save(new NotificationLog(eventId, orderId, userId, type, message)).getId());
    }
}
