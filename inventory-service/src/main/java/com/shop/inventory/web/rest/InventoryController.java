package com.shop.inventory.web.rest;

import com.shop.inventory.application.reservation.Item;
import com.shop.inventory.application.reservation.ReservationService;
import com.shop.inventory.application.reservation.ReserveOutcome;
import com.shop.inventory.application.reservation.ReserveOutcome.AlreadyHandled;
import com.shop.inventory.application.reservation.ReserveOutcome.Rejected;
import com.shop.inventory.application.reservation.ReserveOutcome.Reserved;
import com.shop.inventory.application.reservation.Strategy;
import com.shop.inventory.application.stock.StockService;
import com.shop.inventory.application.stock.StockService.StockView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * | Endpoint                                       | Quyền                    |
 * |------------------------------------------------|--------------------------|
 * | GET  /api/inventory/{productId}                | inventory:read           |
 * | POST /api/inventory/{productId}/adjustments    | inventory:write          |
 * | POST /api/inventory/reserve?strategy=L1..L5    | ROLE_ADMIN / ROLE_SERVICE (test, benchmark) |
 *
 * Luồng thật giữ hàng qua Kafka (order.created); endpoint reserve chỉ để đo và test từng strategy.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    private final StockService stock;
    private final ReservationService reservations;

    InventoryController(StockService stock, ReservationService reservations) {
        this.stock = stock;
        this.reservations = reservations;
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('inventory:read')")
    StockView get(@PathVariable long productId) {
        return stock.get(productId);
    }

    @PostMapping("/{productId}/adjustments")
    @PreAuthorize("hasAuthority('inventory:write')")
    StockView adjust(JwtAuthenticationToken auth, @PathVariable long productId, @Valid @RequestBody AdjustmentRequest req) {
        return stock.adjust(auth.getToken().getSubject(), productId, req.delta(), req.reason());
    }

    /** 200 = giữ được (hoặc đơn đã xử lý trước đó), 409 = bị từ chối (hết hàng...). Client k6 coi cả 2 là "đã xử lý". */
    @PostMapping("/reserve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SERVICE')")
    ResponseEntity<ReserveResponse> reserve(@RequestParam(defaultValue = "L1") Strategy strategy,
                                            @Valid @RequestBody ReserveRequest req) {
        List<Item> items = req.items().stream().map(it -> new Item(it.productId(), it.quantity())).toList();
        ReserveOutcome outcome = reservations.reserve(req.orderId(), items, strategy);
        return switch (outcome) {
            case Reserved r -> ResponseEntity.ok(new ReserveResponse("RESERVED", null, null));
            case AlreadyHandled a -> ResponseEntity.ok(new ReserveResponse("ALREADY_HANDLED", null, null));
            case Rejected r -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ReserveResponse("REJECTED", r.reason().name(), r.productId()));
        };
    }

    record AdjustmentRequest(@NotNull @Min(-100_000) @Max(100_000) Integer delta, @NotBlank @Size(max = 200) String reason) {
    }

    record ReserveRequest(@NotBlank @Size(max = 36) String orderId,
                          @NotEmpty @Size(max = 50) List<@Valid ReserveItem> items) {
    }

    record ReserveItem(@NotNull @Positive Long productId, @NotNull @Positive @Max(10_000) Integer quantity) {
    }

    record ReserveResponse(String outcome, String reason, Long productId) {
    }
}
