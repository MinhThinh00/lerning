package com.shop.payment.web.rest;

import com.shop.payment.application.PaymentService;
import com.shop.payment.application.PaymentService.PaymentView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/admin/payments?orderId= (ADMIN): tra cứu khi điều tra đơn (docs/07 §9). */
@RestController
@RequestMapping("/api/admin/payments")
class PaymentAdminController {

    private final PaymentService payments;

    PaymentAdminController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    PaymentView byOrder(@RequestParam String orderId) {
        return payments.findByOrder(orderId);
    }
}
