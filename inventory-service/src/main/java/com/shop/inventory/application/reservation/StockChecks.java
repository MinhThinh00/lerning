package com.shop.inventory.application.reservation;

import com.shop.inventory.application.reservation.ReserveOutcome.Reason;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.domain.model.Inventory;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Kiểm tra dùng chung cho các strategy đọc entity rồi ghi (L2, L3, L4). */
final class StockChecks {

    private StockChecks() {
    }

    static List<Long> ids(List<Item> items) {
        return items.stream().map(Item::productId).toList();
    }

    static Map<Long, Inventory> byProductId(List<Inventory> rows) {
        return rows.stream().collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
    }

    /** @return item đầu tiên không giữ được (null = đủ hàng cho cả đơn) */
    static Rejected firstShortage(List<Item> items, Map<Long, Inventory> stock) {
        for (Item it : items) {
            Inventory inv = stock.get(it.productId());
            if (inv == null || !inv.isActive()) {
                return new Rejected(Reason.NOT_FOUND, it.productId());
            }
            if (!inv.canReserve(it.quantity())) {
                return new Rejected(Reason.OUT_OF_STOCK, it.productId());
            }
        }
        return null;
    }
}
