# 03 — Security: Keycloak & phân quyền

## 1. Realm-as-code

Realm `shop` khai báo trong `deploy/infra/keycloak/realm/shop-realm.json`. Keycloak import khi start (`--import-realm`) nếu realm chưa tồn tại. Secret và password lấy từ env (`${SHOP_SERVICE_CLIENT_SECRET}`...), không nằm trong file.

Sửa realm trên UI xong thì export lại để file luôn là nguồn sự thật:

```bash
docker compose exec keycloak /opt/keycloak/bin/kc.sh export --realm shop --users realm_file --dir /tmp/export
docker compose cp keycloak:/tmp/export/shop-realm.json ./infra/keycloak/realm/shop-realm.json
# rồi thay lại secret/password bằng placeholder ${...} trước khi commit
```

### Clients

| Client | Loại | Dùng cho |
|---|---|---|
| `shop-app` | public, Authorization Code + PKCE (direct grant bật cho dev) | Gateway login, SPA/mobile, curl test |
| `shop-service` | confidential, service account | Audience của mọi resource server, gọi service-to-service, Keycloak Admin API (user-service) |

Mapper gắn thẳng trên từng client: `roles` (realm roles), `client_roles` (roles của client `shop-service`, dạng **list phẳng**), và audience `shop-service`.

### RBAC: user → role → permission

- **Permission** = client role của client `shop-service` (`user:read`...). Code chỉ kiểm tra permission: `hasAuthority('user:read')`.
- **Role** = realm role **composite** (`ROLE_SUPPORT`...), gom một nhóm permission.
- **User chỉ được gán role**, không gán permission trực tiếp. Keycloak tự expand composite: token của user có role `ROLE_SUPPORT` sẽ chứa `client_roles: [user:read, user:reset-password, ...]`.
- Đổi "role nào có quyền gì" = sửa composite trên Keycloak, không sửa code. Có hiệu lực ở token kế tiếp (tối đa 15 phút).

| Permission (`shop-service`) | USER | SELLER | INVENTORY_MANAGER | SUPPORT | ADMIN |
|---|:-:|:-:|:-:|:-:|:-:|
| `product:read` | x | x | | | x |
| `product:write` | | x | | | x |
| `inventory:read` / `inventory:write` | | x | x | | x |
| `order:read` / `order:write` | x | | | | x |
| `user:read` | | | | x | x |
| `user:reset-password` | | | | x | x |
| `user:create` / `user:update` / `user:delete` | | | | | x |
| `user:assign-role` | | | | | x |

`ROLE_SERVICE` chỉ gán cho service account `shop-service`, không có permission nào và không gán được qua API.

### Test users

Password chung lấy từ `SHOP_TEST_USER_PASSWORD` trong `.env`.

| User | Realm roles |
|---|---|
| `admin` | ADMIN, USER |
| `support1` | SUPPORT, USER |
| `seller1` | SELLER, USER |
| `inv.manager` | INVENTORY_MANAGER, USER |
| `user1` | USER |

Service account `service-account-shop-service`: `ROLE_SERVICE` + `realm-management` (`view-users`, `query-users`, `manage-users`, `view-realm`) để user-service gọi Admin API. `view-realm` cần để đọc role khi gán role cho user.

## 2. Hostname & issuer

Spring Resource Server kiểm tra claim `iss` của JWT phải **đúng bằng** `issuer-uri`. Keycloak ghi `iss` theo URL mà client dùng để gọi. Nếu browser gọi `localhost:8180` còn container gọi `keycloak:8080` thì `iss` sẽ khác nhau và mọi request bị 401.

Cách giải được chọn: **một URL duy nhất ở mọi nơi**.

- Keycloak lắng nghe `KEYCLOAK_PORT` (8180) cả trong lẫn ngoài container, và `KC_HOSTNAME_URL=${KEYCLOAK_URL}`.
- Local: `KEYCLOAK_URL=http://keycloak:8180` + dòng `127.0.0.1 keycloak` trong file hosts.
- Server: `KEYCLOAK_URL=https://auth.example.com` (reverse proxy).
- Apps: `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI=${KEYCLOAK_URL}/realms/shop`.

Đây cũng là cách JHipster khuyến nghị. Lên K8s chỉ cần Ingress host trùng với `KEYCLOAK_URL`.

## 3. Access token mẫu

```json
{
  "iss": "http://keycloak:8180/realms/shop",
  "aud": ["shop-service", "account"],
  "sub": "4f1c2b7e-....",
  "azp": "shop-app",
  "preferred_username": "user1",
  "email": "user1@shop.local",
  "roles": ["ROLE_USER"],
  "client_roles": ["order:read", "order:write"]
}
```

`sub` là **user id của toàn hệ thống** (String UUID). Không parse sang `Long`.

## 4. Map claim → GrantedAuthority

JHipster sinh sẵn `SecurityConfiguration` + `AudienceValidator` (đọc `jhipster.security.oauth2.audience`). Chỉ cần thay converter mặc định bằng converter trong `shop-common`. Converter này map **cả** realm roles lẫn client roles thành authority, nhờ vậy `@PreAuthorize` dùng trực tiếp `hasAuthority(...)`. Không cần `UserContext` request-scoped hay bean `PreXAuthorization` như plan v1.

```java
package com.shop.common.security;

/** roles → ROLE_*, client_roles → "inventory:write"... */
public class ShopJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Stream<String> realmRoles = claimAsList(jwt, "roles").stream()
            .filter(r -> r.startsWith("ROLE_"));
        Stream<String> clientRoles = claimAsList(jwt, "client_roles").stream();
        return Stream.concat(realmRoles, clientRoles)
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> claimAsList(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values != null ? values : List.of();
    }
}
```

Cắm vào `SecurityConfiguration` do JHipster sinh (chỉ sửa đúng chỗ converter):

```java
JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
converter.setJwtGrantedAuthoritiesConverter(new ShopJwtAuthoritiesConverter());
http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)));
```

Lấy user hiện tại trong controller:

```java
@PostMapping("/api/orders")
public ResponseEntity<OrderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody CreateOrderRequest req) {
    String userId = jwt.getSubject();
    ...
}
```

## 5. Ma trận phân quyền

| Endpoint | Rule |
|---|---|
| `GET /api/products/**` | `permitAll` |
| `POST/PUT /api/products/**` | `hasAuthority('product:write')` |
| `GET /api/inventory/{productId}` | `hasAuthority('inventory:read')` |
| `PUT /api/inventory/{productId}/stock` | `hasAuthority('inventory:write')` |
| `POST /api/inventory/reserve` (benchmark) | `hasRole('ADMIN') or hasRole('SERVICE')` |
| `POST /api/orders` | `hasAuthority('order:write')` |
| `GET /api/orders/{id}` | `hasAuthority('order:read')` và chủ đơn (`order.userId == jwt.sub`) hoặc `hasRole('ADMIN')` |
| `/api/account` | `authenticated` |
| `/api/admin/users/**` | theo từng endpoint: `user:read`, `user:create`, `user:update`, `user:delete`, `user:reset-password`, `user:assign-role` (06-USER-SERVICE §5) |
| `/management/health/**`, `/management/prometheus` | `permitAll` (chỉ trong network nội bộ, không route qua gateway) |

Kiểm tra quyền sở hữu (ownership) thực hiện trong application service chứ không viết bằng SpEL dài: lấy đơn ra, so `userId` với `sub`, sai thì ném `AccessDeniedException`.

Tương tự, luật phụ thuộc vào **đối tượng bị tác động** nằm ở application service. Ví dụ ở user-service (`AccessPolicy`): `user:reset-password` cho phép reset mật khẩu, nhưng chỉ admin mới reset được mật khẩu **của admin**. Thiếu luật này thì support reset mật khẩu admin rồi đăng nhập bằng tài khoản admin (leo thang đặc quyền).

## 6. Service-to-service

| Kênh | Xác thực |
|---|---|
| Kafka | Không có (network nội bộ). Lên production thật: SASL/mTLS |
| Feign (order → product) | API đọc giá là public nên không cần token. Nếu cần: JHipster `OAuth2` client registration `oidc` với `shop-service` + client credentials (env đã cấu hình sẵn) |
| user-service → Keycloak Admin API | `RestClient` + `OAuth2ClientHttpRequestInterceptor`, grant `client_credentials`, client `shop-service` |

## 7. Luồng đăng nhập

| Client | Luồng |
|---|---|
| Browser qua gateway | JHipster gateway `oauth2Login` (Authorization Code) → session → token relay xuống service |
| SPA / mobile | Authorization Code + PKCE với `shop-app`, gọi gateway bằng Bearer token |
| curl / test / benchmark | Direct grant (`grant_type=password`), **chỉ dev**. Server: tắt `directAccessGrantsEnabled` |
| Đăng ký | Trang đăng ký của Keycloak (`registrationAllowed: true`). Bỏ API register/OTP tự viết ở plan v1 |

Bỏ endpoint `/api/public/authenticate` (proxy password tới Keycloak) của plan v1: ROPC đã bị loại khỏi OAuth 2.1, và client nên nói chuyện trực tiếp với IdP.

## 8. Lên server

- `KEYCLOAK_COMMAND=start`, HTTPS ở reverse proxy, `KC_PROXY_HEADERS=xforwarded` (đã có).
- Đổi `SHOP_SERVICE_CLIENT_SECRET`, password admin. Xóa hoặc đổi password test user.
- `shop-app`: tắt direct grant, `redirectUris` chỉ domain thật.
- Import chỉ chạy khi realm **chưa tồn tại**: sửa secret/password trong `.env` sau lần start đầu sẽ không có tác dụng. Đổi trong Admin UI (hoặc xóa realm rồi import lại).
