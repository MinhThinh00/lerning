package com.shop.inventory.infrastructure.persistence;

import com.shop.inventory.domain.model.Inventory;
import com.shop.inventory.domain.repository.InventoryRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data sinh implementation; đồng thời là implementation của port domain.
 *
 * flushAutomatically: flush thay đổi đang chờ (vd reservation vừa đổi trạng thái) TRƯỚC câu UPDATE hàng loạt,
 * để thứ tự ghi đúng như code. Không clearAutomatically: clear sẽ detach entity đang sửa dở trong transaction.
 * Mọi UPDATE đều tăng version → không phá strategy dùng @Version chạy xen kẽ.
 */
public interface InventoryJpaRepository extends JpaRepository<Inventory, Long>, InventoryRepository {

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
        update Inventory i
           set i.available = i.available - :qty, i.reserved = i.reserved + :qty,
               i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP
         where i.productId = :productId and i.active = true and i.available >= :qty
        """)
    int decreaseIfAvailable(@Param("productId") long productId, @Param("qty") int qty);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
        update Inventory i
           set i.reserved = i.reserved - :qty, i.sold = i.sold + :qty,
               i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP
         where i.productId = :productId and i.reserved >= :qty
        """)
    int commitReserved(@Param("productId") long productId, @Param("qty") int qty);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
        update Inventory i
           set i.reserved = i.reserved - :qty, i.available = i.available + :qty,
               i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP
         where i.productId = :productId and i.reserved >= :qty
        """)
    int releaseReserved(@Param("productId") long productId, @Param("qty") int qty);

    @Override
    @Modifying(flushAutomatically = true)
    @Query("""
        update Inventory i
           set i.available = i.available + :delta,
               i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP
         where i.productId = :productId and i.available + :delta >= 0
        """)
    int adjustAvailable(@Param("productId") long productId, @Param("delta") int delta);
}
