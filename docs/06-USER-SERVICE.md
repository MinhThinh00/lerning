# 06 — user-service

Code: `user-service/` (Spring Boot viết tay, không JHipster), dùng `shop-common`.

## 1. Trách nhiệm

- Lưu **profile mở rộng** của người dùng (số điện thoại, avatar, ngôn ngữ...). Thông tin đăng nhập, mật khẩu và role do **Keycloak** quản lý.
- Cho admin / support quản lý user (tìm kiếm, tạo, sửa, khóa, xóa, reset mật khẩu, gán role) thông qua Keycloak Admin API.
- DB riêng `db_user`. **Không** đọc/ghi DB của Keycloak: schema đó thuộc Keycloak, đổi theo version. Mọi thứ về danh tính đi qua Admin API.

Những phần của plan v1 bị bỏ:

| v1 | Lý do bỏ |
|---|---|
| `/api/public/authenticate`, `/refresh-token` (proxy password grant) | Client nói chuyện trực tiếp với Keycloak (03-SECURITY §7) |
| `/api/v2/register` + OTP | Dùng trang đăng ký của Keycloak |
| `UserContext` request-scoped + `UserContextFilter` + `PreXAuthorization` | Authority map sẵn trong JWT converter; lấy user bằng `@AuthenticationPrincipal Jwt` |
| Gọi Admin API trên **mỗi request** để lấy role | Role đã có trong token |
| `AUTH:BLACKLIST`, `USR:INFO` trong Redis | Token ngắn hạn (15 phút); không cần |

## 2. Data model

Liquibase: `user-service/src/main/resources/db/changelog/changes/001-create-user-profile.yaml`

```sql
CREATE TABLE user_profile (
  sub           VARCHAR(36)  PRIMARY KEY,      -- Keycloak user id = user id toàn hệ thống
  username      VARCHAR(100) NOT NULL,         -- bản sao để hiển thị; index, KHÔNG unique (Keycloak đảm bảo unique)
  email         VARCHAR(254),
  first_name    VARCHAR(100),
  last_name     VARCHAR(100),
  phone_number  VARCHAR(20),
  avatar_url    VARCHAR(500),
  language_key  VARCHAR(10)  NOT NULL DEFAULT 'vi',
  created_at    DATETIME(6)  NOT NULL,         -- 4 cột audit: AuditableEntity của shop-common tự điền
  created_by    VARCHAR(100),
  updated_at    DATETIME(6)  NOT NULL,
  updated_by    VARCHAR(100)
);
```

`username` không unique: user bị xóa thẳng trên Keycloak (không qua API) để lại profile cũ; user mới trùng username sẽ không tạo được profile nếu có unique.

`UserProfile` implement `Persistable`: id gán sẵn (sub), nếu không Spring Data coi là entity cũ và gọi `merge()` (thêm 1 SELECT).

## 3. Đồng bộ profile: lazy provisioning

User có thể được tạo ở nhiều nơi: trang đăng ký Keycloak, admin, hoặc import. Vì vậy user-service **không** đợi event mà tự tạo profile ở lần gọi `/api/account` đầu tiên (`AccountService`), và cập nhật lại username/email/tên nếu chúng đổi trong Keycloak.

Hai request đầu tiên của cùng một user có thể đến song song. Primary key `sub` chặn bản ghi trùng: `saveAndFlush` → bắt `DataIntegrityViolationException` → đọc lại.

## 4. Gọi Keycloak Admin API

`infrastructure/keycloak/`: `RestClient` + `OAuth2ClientHttpRequestInterceptor` của Spring Security, grant `client_credentials` với client `shop-service` (registration `keycloak-admin` trong `application.yml`). Token service account được cache, tự xin lại khi hết hạn.

Không dùng thư viện `keycloak-admin-client`: kéo theo RESTEasy + Jackson 2, dễ xung đột với Spring Boot 4; Admin API chỉ là REST/JSON.

Application chỉ biết port `IdentityProvider`; `KeycloakIdentityProvider` là adapter.

| Thao tác | Admin REST API |
|---|---|
| Tìm kiếm | `GET /users?search=&first=&max=` + `GET /users/count?search=` |
| Chi tiết | `GET /users/{id}`, `GET /users/{id}/role-mappings/realm` |
| Tạo | `POST /users` (id ở header `Location`) |
| Sửa / khóa | `PUT /users/{id}` |
| Xóa | `DELETE /users/{id}` |
| Reset mật khẩu | `PUT /users/{id}/reset-password` |
| Đăng xuất mọi session | `POST /users/{id}/logout` |
| Gán / gỡ role | `GET /roles/{name}` rồi `POST` / `DELETE /users/{id}/role-mappings/realm` |

### Tạo user

Hai hệ thống không có transaction chung, nên làm theo thứ tự có bù trừ:

```
1. Keycloak: create user (+ password, temporary mặc định = true → phải đổi ở lần đăng nhập đầu) → lấy sub
2. Keycloak: gán realm role (mặc định ROLE_USER)
3. DB: insert user_profile
   lỗi ở 2 hoặc 3 → Keycloak: delete user (bù trừ), trả lỗi
```

Sửa: Keycloak trước, DB sau. Khóa user, reset mật khẩu, gỡ role → gọi thêm logout để refresh token cũ hết hiệu lực.

## 5. API & phân quyền

| Method | Path | Permission | Mô tả |
|---|---|---|---|
| GET | `/api/account` | đăng nhập | Profile của mình (lazy create) |
| PUT | `/api/account` | đăng nhập | Cập nhật phone, avatar, language |
| GET | `/api/admin/users?keyword=&page=&size=` | `user:read` | Tìm theo username/email/tên (size ≤ 100) |
| GET | `/api/admin/users/{id}` | `user:read` | Chi tiết + role + profile |
| POST | `/api/admin/users` | `user:create` | Tạo user (§4) |
| PUT | `/api/admin/users/{id}` | `user:update` | Sửa email/tên/profile, khóa/mở (`enabled`) |
| DELETE | `/api/admin/users/{id}` | `user:delete` | Xóa trên Keycloak + xóa profile |
| POST | `/api/admin/users/{id}/reset-password` | `user:reset-password` | Đặt mật khẩu mới + logout |
| PUT | `/api/admin/users/{id}/roles` | `user:assign-role` | Đặt lại toàn bộ role |

Phân quyền 2 lớp:

1. **`@PreAuthorize`** trên controller: người gọi có permission cho loại thao tác này không.
2. **`AccessPolicy`** trong application service, theo user đích:
   - Chỉ admin được sửa / xóa / reset mật khẩu / đổi role của **tài khoản admin**.
   - Không tự xóa, tự khóa, tự đổi role của mình (409 `SELF_MODIFICATION`).
   - Chỉ gán được role trong `Roles.ASSIGNABLE` (không gán được `ROLE_SERVICE`); chỉ admin trao được `ROLE_ADMIN`.
   - Tạo user với role khác `ROLE_USER` cần thêm `user:assign-role`.

Lỗi trả về dạng ProblemDetail (RFC 9457) có field `code`: `ACCESS_DENIED`, `NOT_FOUND`, `USER_ALREADY_EXISTS`, `INVALID_ROLE`, `SELF_MODIFICATION`, `VALIDATION_FAILED`, `IDENTITY_REJECTED`.

Đổi mật khẩu của chính mình: Account Console của Keycloak (`${KEYCLOAK_URL}/realms/shop/account`). Không tự làm API riêng.

## 6. Cấu hình riêng

| Env | Ý nghĩa |
|---|---|
| `KEYCLOAK_ADMIN_SERVER_URL` | URL Keycloak (= `KEYCLOAK_URL`). Dùng cho cả `issuer-uri` lẫn Admin API |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Secret của client `shop-service` (= `SHOP_SERVICE_CLIENT_SECRET`) |
| `DB_PASSWORD_USER` | Password của user MySQL `user_svc` |

user-service không cần Kafka, Redis hay Caffeine.

Thử API: `user-service/api.http` (IntelliJ HTTP Client / VS Code REST Client).
