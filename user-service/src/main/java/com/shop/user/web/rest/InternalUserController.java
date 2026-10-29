package com.shop.user.web.rest;

import com.shop.user.application.dto.UserDtos.UserContact;
import com.shop.user.application.service.UserContactService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API nội bộ cho service khác, gọi bằng token client_credentials của service account shop-service
 * (realm role ROLE_SERVICE). Token của user thường, kể cả ADMIN, đều bị 403: đây không phải API cho người.
 *
 * | Endpoint                                  | Quyền        |
 * |-------------------------------------------|--------------|
 * | GET /api/internal/users/{sub}/contact     | ROLE_SERVICE |
 */
@RestController
@RequestMapping("/api/internal/users")
class InternalUserController {

    private final UserContactService contacts;

    InternalUserController(UserContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping("/{id}/contact")
    @PreAuthorize("hasAuthority('ROLE_SERVICE')")
    UserContact contact(@PathVariable String id) {
        return contacts.contact(id);
    }
}
