package com.shop.order.domain.repository;

import com.shop.order.domain.model.Order;
import com.shop.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Port: implement ở infrastructure.persistence. */
public interface OrderRepository {

    Optional<Order> findById(String id);

    /** User thường chỉ tìm được đơn của mình: lọc ngay trong query (docs/10 T9), không lấy về rồi mới kiểm tra. */
    Optional<Order> findByIdAndUserId(String id, String userId);

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Page<Order> findByUserId(String userId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findAll(Pageable pageable);

    /** Job timeout: id các đơn kẹt ở status quá lâu, cũ nhất trước. Cursor là chính dữ liệu (docs/10 T6). */
    List<String> findStuckIds(OrderStatus status, Instant updatedBefore, Pageable pageable);

    Order save(Order order);

    Order saveAndFlush(Order order);
}
