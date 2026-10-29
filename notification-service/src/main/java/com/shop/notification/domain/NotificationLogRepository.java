package com.shop.notification.domain;

import java.util.List;

/** Port: implement ở infrastructure.persistence. */
public interface NotificationLogRepository {

    NotificationLog save(NotificationLog log);

    List<NotificationLog> findByOrderIdOrderByIdAsc(String orderId);
}
