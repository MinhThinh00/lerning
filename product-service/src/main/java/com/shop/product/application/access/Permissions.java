package com.shop.product.application.access;

/** Permission = client role của shop-service trong Keycloak (docs/03-SECURITY.md). */
public final class Permissions {

    public static final String PRODUCT_WRITE = "product:write";

    private Permissions() {
    }
}
