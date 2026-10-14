package com.shop.user.application.access;

import java.util.Set;

/** Realm role của Keycloak. */
public final class Roles {

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String USER = "ROLE_USER";
    public static final String SELLER = "ROLE_SELLER";
    public static final String INVENTORY_MANAGER = "ROLE_INVENTORY_MANAGER";
    public static final String SUPPORT = "ROLE_SUPPORT";

    /** Role được phép gán qua API. ROLE_SERVICE (service account) và role mặc định của Keycloak thì không. */
    public static final Set<String> ASSIGNABLE = Set.of(ADMIN, USER, SELLER, INVENTORY_MANAGER, SUPPORT);

    /** Role gán khi tạo user mà không chỉ định role. */
    public static final Set<String> DEFAULT = Set.of(USER);

    private Roles() {
    }
}
