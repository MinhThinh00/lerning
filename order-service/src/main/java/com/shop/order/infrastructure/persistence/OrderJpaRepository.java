package com.shop.order.infrastructure.persistence;

import com.shop.order.domain.model.Order;
import com.shop.order.domain.model.OrderStatus;
import com.shop.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface OrderJpaRepository extends JpaRepository<Order, String>, OrderRepository {

    @Override
    @Query("select o.id from Order o where o.status = :status and o.updatedAt < :before order by o.updatedAt")
    List<String> findStuckIds(@Param("status") OrderStatus status, @Param("before") Instant updatedBefore, Pageable pageable);
}
