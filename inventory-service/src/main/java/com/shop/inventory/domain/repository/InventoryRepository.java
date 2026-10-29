package com.shop.inventory.domain.repository;

import com.shop.inventory.domain.model.Inventory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port: domain chỉ biết interface này, không biết JPA. Implement ở infrastructure.persistence.
 *
 * Các method ghi trả về số dòng bị ảnh hưởng: 0 = điều kiện không thỏa (hết hàng, không tồn tại...).
 * Điều kiện nằm TRONG câu UPDATE nên kiểm tra và ghi là một thao tác nguyên tử dưới row lock của MySQL.
 */
public interface InventoryRepository {

    Optional<Inventory> findByProductId(long productId);

    /** Đọc KHÔNG lock (L3, L4, nạp counter Redis). */
    List<Inventory> findAllByProductIdIn(Collection<Long> productIds);

    /** L2: SELECT ... FOR UPDATE các dòng active, theo thứ tự productId (mọi request lock cùng thứ tự → không deadlock). */
    List<Inventory> lockAllActive(Collection<Long> productIds);

    /** Warmup / reconcile counter Redis. */
    List<Inventory> findAllByActiveTrue();

    /** L1: available -= qty, reserved += qty nếu đủ hàng. */
    int decreaseIfAvailable(long productId, int qty);

    /** order.paid: reserved -= qty, sold += qty. */
    int commitReserved(long productId, int qty);

    /** order.cancelled: reserved -= qty, available += qty. */
    int releaseReserved(long productId, int qty);

    /** Nhập (+) / xuất (-) kho, không cho available âm. */
    int adjustAvailable(long productId, int delta);

    Inventory saveAndFlush(Inventory inventory);
}
