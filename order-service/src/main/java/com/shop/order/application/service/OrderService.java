package com.shop.order.application.service;

import com.shop.common.web.PageResponse;
import com.shop.common.web.error.BusinessException;
import com.shop.common.web.error.NotFoundException;
import com.shop.order.application.access.Caller;
import com.shop.order.application.port.ProductCatalog;
import com.shop.order.application.port.ProductCatalog.CatalogProduct;
import com.shop.order.application.port.ProductCatalog.CatalogUnavailableException;
import com.shop.order.application.service.OrderDtos.OrderAccepted;
import com.shop.order.application.service.OrderDtos.OrderView;
import com.shop.order.application.service.OrderDtos.PlaceOrderCommand;
import com.shop.order.application.service.OrderDtos.RequestedItem;
import com.shop.order.domain.model.Order;
import com.shop.order.domain.model.OrderItem.PricedLine;
import com.shop.order.domain.model.OrderStatus;
import com.shop.order.domain.model.Transition;
import com.shop.order.domain.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Use case phía người dùng: đặt đơn, xem đơn, hủy đơn.
 *
 * Đặt đơn (docs/07 §4):
 *  1. Idempotency-Key trùng → trả lại đơn cũ (client retry an toàn khi mất response).
 *  2. Tra giá ở product-service NGOÀI transaction: không giữ connection DB trong lúc chờ HTTP.
 *     Server tự tính tổng, không bao giờ nhận giá từ client.
 *  3. 1 transaction: insert đơn + items + outbox order.created.
 *  4. 202 Accepted. Phần còn lại của saga chạy bất đồng bộ qua Kafka.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductCatalog catalog;
    private final OrderEventPublisher events;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, ProductCatalog catalog, OrderEventPublisher events, TransactionTemplate tx) {
        this.orders = orders;
        this.catalog = catalog;
        this.events = events;
        this.tx = tx;
    }

    public OrderAccepted place(Caller caller, PlaceOrderCommand cmd) {
        Optional<Order> existing = findByIdempotencyKey(caller, cmd.idempotencyKey());
        if (existing.isPresent()) {
            return accepted(existing.get());
        }
        List<PricedLine> lines = price(cmd.items());
        try {
            return tx.execute(s -> {
                Order order = orders.saveAndFlush(Order.create(caller.id(), lines, cmd.shippingAddress(), cmd.idempotencyKey()));
                events.created(order);
                return accepted(order);
            });
        } catch (DataIntegrityViolationException race) {
            // 2 request cùng Idempotency-Key chạy song song: unique (user_id, idempotency_key) chặn request sau
            return findByIdempotencyKey(caller, cmd.idempotencyKey()).map(OrderService::accepted).orElseThrow(() -> race);
        }
    }

    @Transactional(readOnly = true)
    public OrderView get(Caller caller, String id) {
        return OrderView.from(loadVisible(caller, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderView> listMine(Caller caller, int page, int size) {
        return PageResponse.from(orders.findByUserId(caller.id(), newestFirst(page, size)), OrderView::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderView> listAll(OrderStatus status, int page, int size) {
        PageRequest pageable = newestFirst(page, size);
        return PageResponse.from(status == null ? orders.findAll(pageable) : orders.findByStatus(status, pageable),
            OrderView::from);
    }

    /** Chủ đơn hủy khi còn PENDING/CONFIRMED. Đơn PAID không hủy được (hoàn tiền ngoài phạm vi). */
    @Transactional
    public OrderView cancel(Caller caller, String id) {
        Order order = loadVisible(caller, id);
        if (order.cancel("USER_CANCELLED") != Transition.APPLIED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                "Order in status " + order.getStatus() + " cannot be cancelled");
        }
        events.cancelled(order);
        return OrderView.from(order);
    }

    /**
     * User thường: chỉ thấy đơn của mình, lọc ngay trong query. Đơn người khác → 404 (không phải 403),
     * để không lộ việc id đó có tồn tại (docs/10 T9). Admin thấy mọi đơn.
     */
    private Order loadVisible(Caller caller, String id) {
        Optional<Order> order = caller.isAdmin() ? orders.findById(id) : orders.findByIdAndUserId(id, caller.id());
        return order.orElseThrow(() -> new NotFoundException("Order", id));
    }

    private Optional<Order> findByIdempotencyKey(Caller caller, String key) {
        return key == null ? Optional.empty() : orders.findByUserIdAndIdempotencyKey(caller.id(), key);
    }

    private List<PricedLine> price(List<RequestedItem> items) {
        List<CatalogProduct> found;
        try {
            found = catalog.findByIds(items.stream().map(RequestedItem::productId).toList());
        } catch (CatalogUnavailableException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SERVICE_UNAVAILABLE",
                "Cannot price the order right now, try again later");
        }
        Map<Long, CatalogProduct> byId = found.stream().collect(Collectors.toMap(CatalogProduct::id, Function.identity()));
        List<Long> missing = items.stream().map(RequestedItem::productId).filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRODUCT_UNAVAILABLE", "Products not available: " + missing);
        }
        if (found.stream().anyMatch(p -> !Order.CURRENCY.equals(p.currency()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", "Only " + Order.CURRENCY + " is supported");
        }
        return items.stream().map(it -> {
            CatalogProduct p = byId.get(it.productId());
            return new PricedLine(p.id(), p.sku(), p.name(), p.price(), it.quantity());
        }).toList();
    }

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private static OrderAccepted accepted(Order o) {
        return new OrderAccepted(o.getId(), o.getStatus().name());
    }
}
