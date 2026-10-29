package com.shop.inventory.config;

import com.shop.inventory.application.reservation.Strategy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultStrategy strategy cho luồng Kafka (order.created). Env INVENTORY_RESERVE_DEFAULT_STRATEGY.
 * @param l3MaxAttempts   L3: số lần thử tối đa khi đụng version, quá thì Rejected(CONFLICT)
 * @param l4Wait          L4: chờ lấy Redisson lock tối đa, quá thì Rejected(LOCK_TIMEOUT)
 */
@ConfigurationProperties("inventory.reserve")
public record InventoryProperties(
    @DefaultValue("L5") Strategy defaultStrategy,
    @DefaultValue("20") int l3MaxAttempts,
    @DefaultValue("5s") Duration l4Wait) {
}
