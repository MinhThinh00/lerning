package com.shop.common.messaging.inbox;

import java.time.Duration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chống xử lý trùng ở consumer (docs/05-MESSAGING.md §5). Dùng trong CÙNG transaction với nghiệp vụ:
 * <pre>
 * &#64;Transactional
 * public void commit(String eventId, OrderPaid evt) {
 *     if (!inbox.tryMarkProcessed(eventId, "inventory:order.paid")) return;   // đã xử lý rồi
 *     ... nghiệp vụ + outbox
 * }
 * </pre>
 * Dòng inbox, thay đổi nghiệp vụ và outbox cùng commit hoặc cùng rollback. Nghiệp vụ lỗi → dòng inbox
 * cũng mất → lần giao lại sẽ xử lý lại, đúng ý muốn.
 *
 * Hai consumer nhận cùng event đồng thời: INSERT thứ hai bị chặn bởi row lock của INSERT đầu, chờ tới khi
 * transaction đầu commit (→ duplicate → false) hoặc rollback (→ insert thành công → xử lý).
 */
public class InboxGuard {

    private final JdbcClient jdbc;

    public InboxGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param consumer tên ổn định của handler, vd {@code "inventory:order.paid"}: một event có thể được
     *                 nhiều handler khác nhau trong cùng service xử lý độc lập
     * @return true nếu lần đầu thấy event này (hãy xử lý), false nếu đã xử lý (bỏ qua, coi là thành công)
     */
    public boolean tryMarkProcessed(String eventId, String consumer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                "InboxGuard.tryMarkProcessed must run in the same transaction as the business change");
        }
        // INSERT thường + bắt duplicate, KHÔNG dùng INSERT IGNORE: IGNORE còn nuốt cả lỗi khác thành warning
        // (vd eventId dài quá bị cắt cụt → 2 event khác nhau trùng khóa → event thứ hai bị bỏ qua âm thầm).
        // MySQL: lỗi 1 statement không làm hỏng transaction, nên bắt exception ở đây là an toàn.
        try {
            jdbc.sql("INSERT INTO processed_event (event_id, consumer, processed_at) VALUES (?, ?, UTC_TIMESTAMP(6))")
                .params(eventId, consumer)
                .update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    int deleteOlderThan(Duration age, int batchSize) {
        return jdbc.sql("""
                DELETE FROM processed_event
                WHERE processed_at < UTC_TIMESTAMP(6) - INTERVAL ? SECOND
                LIMIT ?
                """)
            .params(age.toSeconds(), batchSize)
            .update();
    }
}
