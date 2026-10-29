package com.shop.common.messaging.outbox;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Truy cập bảng outbox_event bằng JDBC thuần, không dùng JPA entity:
 * - không phụ thuộc @EntityScan của service (entity nằm ngoài package com.shop.&lt;service&gt; sẽ không được quét);
 * - vẫn chung transaction với JPA, vì JpaTransactionManager expose cùng Connection cho JdbcTemplate/JdbcClient.
 *
 * Mọi mốc thời gian lấy từ đồng hồ DB (UTC_TIMESTAMP) cho nhất quán giữa các replica và không phụ thuộc
 * timezone của JVM. SQL viết cho MySQL.
 */
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(String eventId, String aggregateType, String aggregateId, String eventType,
                String topic, String messageKey, String payload, String traceParent) {
        jdbc.sql("""
                INSERT INTO outbox_event
                  (event_id, aggregate_type, aggregate_id, event_type, topic, message_key, payload, trace_parent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """)
            .params(eventId, aggregateType, aggregateId, eventType, topic, messageKey, payload, traceParent)
            .update();
    }

    /** Lấy theo thứ tự id → thứ tự ghi = thứ tự publish. Dùng index idx_outbox_pending (status, id). */
    List<OutboxEvent> findPending(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, event_type, topic, message_key, payload, trace_parent, attempts
                FROM outbox_event
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT ?
                """)
            .param(limit)
            .query((rs, n) -> new OutboxEvent(
                rs.getLong("id"), rs.getString("event_id"), rs.getString("event_type"), rs.getString("topic"),
                rs.getString("message_key"), rs.getString("payload"), rs.getString("trace_parent"), rs.getInt("attempts")))
            .list();
    }

    void markPublished(long id) {
        jdbc.sql("UPDATE outbox_event SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6) WHERE id = ?")
            .param(id)
            .update();
    }

    void markAttemptFailed(long id, String error) {
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = ? WHERE id = ?")
            .params(error, id)
            .update();
    }

    /**
     * Xóa PUBLISHED cũ theo từng lô (DELETE ... LIMIT) để không giữ lock lâu trên bảng nóng.
     * @return số dòng đã xóa trong lô này
     */
    int deletePublishedOlderThan(Duration age, int batchSize) {
        return jdbc.sql("""
                DELETE FROM outbox_event
                WHERE status = 'PUBLISHED' AND published_at < UTC_TIMESTAMP(6) - INTERVAL ? SECOND
                ORDER BY id
                LIMIT ?
                """)
            .params(age.toSeconds(), batchSize)
            .update();
    }

    long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")
            .query(Long.class)
            .single();
    }

    /** Tuổi (giây) của event PENDING lâu nhất; 0 nếu không còn event nào chờ. */
    long oldestPendingAgeSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)), 0)
                FROM outbox_event WHERE status = 'PENDING'
                """)
            .query(Long.class)
            .single();
    }
}
