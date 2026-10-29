package com.shop.inventory.reservation;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L0 — CỐ Ý SAI, chỉ nằm trong src/test (docs/10 T2): SELECT tồn kho → kiểm tra trong Java → UPDATE gán giá trị mới.
 * Giữa lúc đọc và lúc ghi, request khác cũng đọc được cùng giá trị cũ → cả hai cùng "thấy còn hàng"
 * và ghi đè lên nhau (lost update) → bán vượt tồn kho. Đây là cách viết hay gặp ở code thật (XX decreaseStockCache).
 *
 * Dùng để chứng minh test đồng thời bắt được oversell: chạy với L0 phải thấy bất biến vỡ.
 */
class L0ReadThenWriteStrategy {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    L0ReadThenWriteStrategy(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    boolean reserve(String orderId, long productId, int qty) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Map<String, Object> row = jdbc.sql("SELECT available, reserved FROM inventory WHERE product_id = ?")
                .param(productId).query().singleRow();
            int available = ((Number) row.get("available")).intValue();
            int reserved = ((Number) row.get("reserved")).intValue();
            if (available < qty) {
                return false;
            }
            sleepQuietly();   // nới rộng khoảng hở đọc→ghi để race xảy ra ổn định trong test (code thật không cần sleep vẫn dính)
            jdbc.sql("UPDATE inventory SET available = ?, reserved = ? WHERE product_id = ?")
                .params(available - qty, reserved + qty, productId).update();
            jdbc.sql("""
                    INSERT INTO inventory_reservation (order_id, product_id, quantity, status, strategy, created_at, updated_at)
                    VALUES (?, ?, ?, 'RESERVED', 'L0', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """).params(orderId, productId, qty).update();
            return true;
        }));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
