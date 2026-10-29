package com.shop.notification.infrastructure.persistence;

import com.shop.notification.domain.NotificationLog;
import com.shop.notification.domain.NotificationLogRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface NotificationLogJpaRepository extends JpaRepository<NotificationLog, Long>, NotificationLogRepository {
}
