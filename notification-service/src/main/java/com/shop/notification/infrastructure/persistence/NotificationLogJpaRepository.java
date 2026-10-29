package com.shop.notification.infrastructure.persistence;

import com.shop.notification.domain.NotificationLog;
import com.shop.notification.domain.NotificationLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data sinh implementation; đồng thời là implementation của port domain.
 * Mỗi UPDATE tự là 1 transaction ngắn (@Transactional ở đây): dispatcher gọi chúng xen giữa các bước gọi mạng.
 */
public interface NotificationLogJpaRepository extends JpaRepository<NotificationLog, Long>, NotificationLogRepository {

    @Override
    @Transactional
    @Modifying
    @Query("""
        update NotificationLog n
           set n.status = com.shop.notification.domain.NotificationLog.Status.SENDING,
               n.claimedAt = :now, n.attempts = n.attempts + 1, n.updatedAt = :now
         where n.id = :id
           and (n.status = com.shop.notification.domain.NotificationLog.Status.PENDING
                or (n.status = com.shop.notification.domain.NotificationLog.Status.SENDING and n.claimedAt < :staleBefore))
        """)
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

    @Override
    @Transactional
    @Modifying
    @Query("""
        update NotificationLog n
           set n.status = com.shop.notification.domain.NotificationLog.Status.SENT,
               n.recipient = :recipient, n.sentAt = :now, n.lastError = null, n.updatedAt = :now
         where n.id = :id and n.status = com.shop.notification.domain.NotificationLog.Status.SENDING
        """)
    int markSent(@Param("id") Long id, @Param("recipient") String recipient, @Param("now") Instant now);

    @Override
    @Transactional
    @Modifying
    @Query("""
        update NotificationLog n
           set n.status = com.shop.notification.domain.NotificationLog.Status.PENDING,
               n.lastError = :error, n.updatedAt = :now
         where n.id = :id and n.status = com.shop.notification.domain.NotificationLog.Status.SENDING
        """)
    int markRetry(@Param("id") Long id, @Param("error") String error, @Param("now") Instant now);

    @Override
    @Transactional
    @Modifying
    @Query("""
        update NotificationLog n
           set n.status = com.shop.notification.domain.NotificationLog.Status.FAILED,
               n.lastError = :error, n.updatedAt = :now
         where n.id = :id and n.status = com.shop.notification.domain.NotificationLog.Status.SENDING
        """)
    int markFailed(@Param("id") Long id, @Param("error") String error, @Param("now") Instant now);

    @Override
    @Query("""
        select n.id from NotificationLog n
         where (n.status = com.shop.notification.domain.NotificationLog.Status.PENDING and n.updatedAt < :before)
            or (n.status = com.shop.notification.domain.NotificationLog.Status.SENDING and n.claimedAt < :staleBefore)
         order by n.updatedAt
        """)
    List<Long> findDueIds(@Param("before") Instant before, @Param("staleBefore") Instant staleBefore, Pageable page);
}
