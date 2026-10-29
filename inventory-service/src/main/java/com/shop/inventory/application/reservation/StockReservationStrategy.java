package com.shop.inventory.application.reservation;

import java.util.List;

/**
 * Một cơ chế giữ hàng, all-or-nothing cho cả đơn.
 * Thành công: trừ kho + ReservationRecorder.reserved(...) trong CÙNG transaction của strategy.
 * Thất bại: rollback hết, trả Rejected; ReservationService ghi FAILED ở transaction riêng.
 */
public interface StockReservationStrategy {

    Strategy id();

    /** @param sortedItems đã sắp theo productId (lock luôn cùng thứ tự → không deadlock), không trùng productId */
    ReserveOutcome reserve(String orderId, List<Item> sortedItems);
}
