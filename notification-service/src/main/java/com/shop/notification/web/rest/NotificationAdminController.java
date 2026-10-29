package com.shop.notification.web.rest;

import com.shop.notification.application.NotificationService;
import com.shop.notification.domain.NotificationLog;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/admin/notifications?orderId= (ADMIN): kiểm tra khách đã được báo chưa. */
@RestController
@RequestMapping("/api/admin/notifications")
class NotificationAdminController {

    private final NotificationService notifications;

    NotificationAdminController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    List<NotificationView> byOrder(@RequestParam String orderId) {
        return notifications.byOrder(orderId).stream().map(NotificationView::from).toList();
    }

    record NotificationView(Long id, String orderId, String userId, String type, String message, String status,
                            String recipient, int attempts, String lastError, Instant createdAt, Instant sentAt) {

        static NotificationView from(NotificationLog n) {
            return new NotificationView(n.getId(), n.getOrderId(), n.getUserId(), n.getType().name(), n.getMessage(),
                n.getStatus().name(), n.getRecipient(), n.getAttempts(), n.getLastError(), n.getCreatedAt(), n.getSentAt());
        }
    }
}
