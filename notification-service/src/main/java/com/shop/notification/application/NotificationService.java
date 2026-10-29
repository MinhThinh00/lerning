package com.shop.notification.application;

import com.shop.common.messaging.inbox.InboxGuard;
import com.shop.notification.domain.NotificationLog;
import com.shop.notification.domain.NotificationLog.Type;
import com.shop.notification.domain.NotificationLogRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Báo khách khi đơn PAID / CANCELLED (docs/07 §10). Tiêu thụ thẳng domain event, không có topic notification riêng.
 * Inbox chống gửi trùng: Kafka giao lại order.paid thì khách không nhận 2 thông báo.
 *
 * Bản mock "gửi" = log INFO + ghi notification_log, nằm trong cùng transaction với dòng inbox.
 * Khi gửi thật (email/SMS) thì việc gửi là side effect ra ngoài, không rollback được: khi đó phải tách bước gửi
 * ra sau commit và chấp nhận at-least-once phía nhà cung cấp (docs/10 T8).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository logs;
    private final InboxGuard inbox;

    public NotificationService(NotificationLogRepository logs, InboxGuard inbox) {
        this.logs = logs;
        this.inbox = inbox;
    }

    @Transactional
    public void orderPaid(String eventId, String orderId, String userId, String totalAmount, String currency) {
        notify(eventId, "notification:order.paid", orderId, userId, Type.ORDER_PAID,
            "Đơn hàng " + orderId + " đã thanh toán thành công (" + totalAmount + " " + currency + ")");
    }

    @Transactional
    public void orderCancelled(String eventId, String orderId, String userId, String reason) {
        notify(eventId, "notification:order.cancelled", orderId, userId, Type.ORDER_CANCELLED,
            "Đơn hàng " + orderId + " đã bị hủy (" + reason + ")");
    }

    @Transactional(readOnly = true)
    public List<NotificationLog> byOrder(String orderId) {
        return logs.findByOrderIdOrderByIdAsc(orderId);
    }

    private void notify(String eventId, String consumer, String orderId, String userId, Type type, String message) {
        if (!inbox.tryMarkProcessed(eventId, consumer)) {
            return;
        }
        logs.save(new NotificationLog(eventId, orderId, userId, type, message));
        log.info("Sending notification to user {}: {}", userId, message);
    }
}
