package com.shop.order.application.service;

import com.shop.order.domain.model.Order;
import com.shop.order.domain.model.OrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Input/output của use case (request DTO có validation nằm ở tầng web). */
public final class OrderDtos {

    private OrderDtos() {
    }

    public record PlaceOrderCommand(List<RequestedItem> items, String shippingAddress, String idempotencyKey) {
    }

    public record RequestedItem(long productId, int quantity) {
    }

    public record OrderView(String id, String userId, String status, BigDecimal totalAmount, String currency,
                            String shippingAddress, String cancelReason, boolean refundRequired,
                            Instant createdAt, Instant confirmedAt, Instant paidAt, Instant cancelledAt,
                            List<ItemView> items) {

        public static OrderView from(Order o) {
            return new OrderView(o.getId(), o.getUserId(), o.getStatus().name(), o.getTotalAmount(), o.getCurrency(),
                o.getShippingAddress(), o.getCancelReason(), o.isRefundRequired(), o.getCreatedAt(), o.getConfirmedAt(),
                o.getPaidAt(), o.getCancelledAt(), o.getItems().stream().map(ItemView::from).toList());
        }
    }

    public record ItemView(long productId, String sku, String productName, BigDecimal unitPrice, int quantity,
                           BigDecimal lineTotal) {

        static ItemView from(OrderItem i) {
            return new ItemView(i.getProductId(), i.getSku(), i.getProductName(), i.getUnitPrice(), i.getQuantity(),
                i.getLineTotal());
        }
    }

    /** Trả về ngay khi tạo đơn (202): client poll GET /api/orders/{id} để theo dõi saga. */
    public record OrderAccepted(String id, String status) {
    }
}
