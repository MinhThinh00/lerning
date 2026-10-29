package com.shop.order.web.rest;

import com.shop.common.web.PageResponse;
import com.shop.common.web.error.BusinessException;
import com.shop.order.application.access.Caller;
import com.shop.order.application.service.OrderDtos.OrderAccepted;
import com.shop.order.application.service.OrderDtos.OrderView;
import com.shop.order.application.service.OrderDtos.PlaceOrderCommand;
import com.shop.order.application.service.OrderDtos.RequestedItem;
import com.shop.order.application.service.OrderService;
import com.shop.order.domain.model.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * | Endpoint                                  | Quyền                                   |
 * |-------------------------------------------|-----------------------------------------|
 * | POST /api/orders  (Idempotency-Key)       | order:write → 202, saga chạy bất đồng bộ |
 * | GET  /api/orders/{id}                     | order:read + chủ đơn (admin: mọi đơn)    |
 * | GET  /api/orders?page=                    | order:read, chỉ đơn của mình             |
 * | POST /api/orders/{id}/cancel              | order:write + chủ đơn                    |
 * | GET  /api/admin/orders?status=&page=      | ROLE_ADMIN                               |
 */
@RestController
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/api/orders")
    @PreAuthorize("hasAuthority('order:write')")
    ResponseEntity<OrderAccepted> place(JwtAuthenticationToken auth,
                                        @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
                                        @Valid @RequestBody PlaceOrderRequest req) {
        if (new HashSet<>(req.items().stream().map(ItemRequest::productId).toList()).size() != req.items().size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "Each productId may appear only once");
        }
        OrderAccepted accepted = orders.place(caller(auth), new PlaceOrderCommand(
            req.items().stream().map(i -> new RequestedItem(i.productId(), i.quantity())).toList(),
            req.shippingAddress(), idempotencyKey));
        return ResponseEntity.accepted().location(URI.create("/api/orders/" + accepted.id())).body(accepted);
    }

    @GetMapping("/api/orders/{id}")
    @PreAuthorize("hasAuthority('order:read') or hasAuthority('ROLE_ADMIN')")
    OrderView get(JwtAuthenticationToken auth, @PathVariable String id) {
        return orders.get(caller(auth), id);
    }

    @GetMapping("/api/orders")
    @PreAuthorize("hasAuthority('order:read')")
    PageResponse<OrderView> listMine(JwtAuthenticationToken auth,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.listMine(caller(auth), page, size);
    }

    @PostMapping("/api/orders/{id}/cancel")
    @PreAuthorize("hasAuthority('order:write') or hasAuthority('ROLE_ADMIN')")
    OrderView cancel(JwtAuthenticationToken auth, @PathVariable String id) {
        return orders.cancel(caller(auth), id);
    }

    @GetMapping("/api/admin/orders")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    PageResponse<OrderView> listAll(@RequestParam(required = false) OrderStatus status,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orders.listAll(status, page, size);
    }

    private static Caller caller(JwtAuthenticationToken auth) {
        return new Caller(auth.getToken().getSubject(),
            auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
    }

    /** Không có trường giá: server tự tra giá ở product-service (v1 nhận giá từ client → khách tự đặt giá). */
    record PlaceOrderRequest(@NotEmpty @Size(max = 20) List<@Valid ItemRequest> items,
                             @Size(max = 500) String shippingAddress) {
    }

    record ItemRequest(@NotNull @Positive Long productId, @NotNull @Positive @Max(1000) Integer quantity) {
    }
}
