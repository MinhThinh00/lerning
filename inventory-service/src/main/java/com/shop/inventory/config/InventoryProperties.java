package com.shop.inventory.config;

import com.shop.inventory.application.reservation.Strategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param defaultStrategy strategy cho luồng Kafka (order.created). Env INVENTORY_RESERVE_DEFAULT_STRATEGY. */
@ConfigurationProperties("inventory.reserve")
public record InventoryProperties(@DefaultValue("L1") Strategy defaultStrategy) {
}
