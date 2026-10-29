# 09 — Implementation Plan

## Thứ tự & ước lượng

Làm theo **lát cắt dọc**: có luồng đặt hàng end-to-end sớm (dù đơn giản), rồi mới làm sâu từng phần. Plan v1 làm user-service trước và observability cuối, nên phải đến gần cuối mới chạy được luồng chính, và lúc debug hệ phân tán thì chưa có công cụ quan sát.

| Phase | Nội dung | Ước lượng |
|---|---|---|
| 0 | Chuẩn bị môi trường, git | 0.5 ngày |
| 1 | Infra local + Keycloak realm | 0.5–1 ngày |
| 2 | JHipster JDL + `shop-common` skeleton | 1–1.5 ngày |
| 3 | `shop-common`: security, envelope, outbox, inbox, kafka, ShedLock | 2 ngày |
| 4 | product-service | 0.5–1 ngày |
| 5 | inventory-service: L1 + reservation + consumer | 2 ngày |
| 6 | order-service + payment + notification → **saga end-to-end** | 2–3 ngày |
| 7 | Observability end-to-end (log ↔ trace ↔ metric) | 1 ngày |
| 8 | inventory: L2–L5, Redis, reconcile | 2 ngày |
| 9 | gateway + user-service | 1–1.5 ngày |
| 10 | Full Docker + scale + diễn tập sự cố | 1–1.5 ngày |
| 11 | Benchmark 5 strategy | 1 ngày |
| 12 | Lên server | 1 ngày |
| | **Tổng** | **~15–20 ngày công** |

Plan v1 ước tính 25–35 giờ. Con số đó không tính tới test, debug hệ phân tán và triển khai.

Mỗi phase có **Definition of Done (DoD)**. Chỉ chuyển phase khi DoD đạt.

---

## Phase 0 — Chuẩn bị

- [ ] Java 21, Maven 3.9, Node 20 LTS (cho JHipster CLI), Docker Desktop (≥ 6 GB RAM), k6
- [ ] `npm i -g generator-jhipster@8`
- [ ] `git init`, commit trạng thái hiện tại (docs + deploy)
- [ ] File hosts: `127.0.0.1 keycloak`

**DoD:** `java -version` = 21, `jhipster --version` = 8.x, `docker compose version` ≥ 2.20.

## Phase 1 — Infra local

- [ ] `cd deploy && cp .env.example .env && docker compose up -d`
- [ ] Kiểm tra theo 02-DEPLOYMENT §4.4

**DoD:**
- `docker compose ps`: mysql, consul, keycloak, redis, kafka `healthy`; kafka-init `exited (0)`.
- Đủ 8 topic + 8 DLT.
- Token của `user1` có `roles: [ROLE_USER]`, `client_roles: [order:read, order:write]`, và `aud` chứa `shop-service`.
- User `inventory_svc` **không** thấy `db_order`.

## Phase 2 — Generate

- [ ] Viết `jhipster/shop.jdl` (7 app, 01-ARCHITECTURE §2), generate
- [ ] Tạo Maven project `shop-common` (jar), `mvn install`
- [ ] Mỗi service: thêm dependency theo bảng 01-ARCHITECTURE §2; tạo khung package DDD
- [ ] Mỗi service: `application.yml` default cho IDE (localhost), `application-prod.yml` không chứa địa chỉ
- [ ] Jib: `-Djib.to.image=shop/<service>`

**DoD:** 7 app start từ IDE, đăng ký Consul (UI `localhost:8500`), `/management/health/readiness` = UP; `./mvnw -Pprod verify jib:dockerBuild` tạo image.

## Phase 3 — `shop-common`

- [x] `ShopJwtAuthoritiesConverter` + cắm vào `SecurityConfiguration` của từng service (bean `JwtAuthenticationConverter` tự nạp; user-service đã dùng)
- [x] `EventEnvelope`, `Topics`, `EnvelopeCodec`
- [x] `OutboxEvent`, `OutboxWriter` (`MANDATORY` tx), `OutboxRelay` (ShedLock), cleanup job, gauge
- [x] `ProcessedEvent`, `InboxGuard.tryMarkProcessed`
- [x] `KafkaCommonConfig`: String serde, `DefaultErrorHandler` + DLT (partition -1)
- [x] Liquibase changelog chung: `outbox_event`, `processed_event`, `shedlock`
- [x] Auto-configuration
- [x] 2 test đối chứng của [10 T5](./10-LEARNING-FROM-REFERENCES.md): `DualWriteDemoTest`, listener nuốt lỗi (trong `KafkaErrorHandlingTest`)
- [ ] Luật ArchUnit "chỉ `OutboxRelay` được gọi `KafkaTemplate.send`": dời sang Phase 4–6, khi đã có code service để kiểm

**Trạng thái (2026-10-29):** xong, mọi DoD có test xanh (`mvn -pl shop-common test`, cần Docker). Viết tay, Boot 4 — không qua JHipster. `trace_parent` để `null`, Phase 7 mới điền.

**DoD (test bằng Testcontainers MySQL + Kafka):**
- Ghi outbox trong tx bị rollback → không có event nào được publish.
- Relay chạy ở 2 instance song song → mỗi event chỉ publish 1 lần, đúng thứ tự.
- Kafka down 30s → event dồn lại, Kafka lên → publish đủ.
- Gửi cùng một message 2 lần → handler nghiệp vụ chỉ chạy 1 lần.
- Message JSON hỏng → vào `<topic>.DLT` ngay, không retry.

## Phase 4 — product-service

- [x] Entity `Product`, `Category`; Liquibase + seed `context="dev"`
- [x] `GET /api/products/{id}`, `GET /api/products?ids=` (public), `POST/PUT` (`product:write`)
- [x] Data scoping [10 T9](./10-LEARNING-FROM-REFERENCES.md): `seller_id`, `ProductAccessPolicy`, sửa sản phẩm người khác → 404; trang quản lý `GET /api/seller/products`
- [x] `@Version` + client gửi `version` khi PUT → 409 `VERSION_CONFLICT` thay vì ghi đè
- [ ] Cache 2 tầng Caffeine → Redis có lock + null sentinel ([10 T4](./10-LEARNING-FROM-REFERENCES.md)): làm sau, cần Redis + k6 để đo DoD

**DoD:** `seller1` tạo được sản phẩm; `user1` nhận 403; GET không cần token.

**Trạng thái (2026-10-29):** xong, DoD kiểm bằng `ProductApiIntegrationTest` (Testcontainers MySQL, 15 test) và chạy thật với token Keycloak của `seller1`/`user1`/`admin`. Sản phẩm seed có `seller_id = 'seed-seller'` (sub của seller1 do Keycloak sinh ngẫu nhiên) → chỉ admin sửa được. Cột boolean dùng `BIT(1)`: Liquibase dịch `BOOLEAN` thành `TINYINT`, Hibernate 7 validate báo sai kiểu.

## Phase 5 — inventory-service (L1)

- [x] `inventory`, `inventory_reservation` + seed
- [x] `ReservationService`, `ReservationRecorder`, strategy L1
- [x] Consumer `order.created` → reserve (L1) → outbox `inventory.reserved/failed`
- [x] Consumer `order.paid` (commit), `order.cancelled` (release + tombstone)
- [x] REST: GET, adjustments, `reserve?strategy=` (benchmark)
- [x] Test đối chứng [10 T2](./10-LEARNING-FROM-REFERENCES.md): `L0ReadThenWriteStrategy` (chỉ trong `src/test`) bị test bắt oversell; L1 xanh 10 lần liên tiếp

**Trạng thái (2026-10-29):** xong, DoD kiểm bằng Testcontainers MySQL + Kafka (`mvn -pl inventory-service test`, 26 test). Chưa chạy thật trên compose vì cần bật `kafka-single`. `INVENTORY_RESERVE_DEFAULT_STRATEGY` chưa cài thì app từ chối khởi động; (Phase 8 đã cài đủ L1–L5, mặc định L5.) Endpoint `reset` cho benchmark để tới Phase 11.

**DoD:**
- Test: 200 thread reserve đồng thời 1 sản phẩm tồn 100 → đúng 100 thành công, bất biến 04 §2 thỏa.
- Redeliver `order.created` → không trừ thêm.
- `order.cancelled` cho đơn `FAILED` → tồn kho không đổi.
- `order.cancelled` đến trước `order.created` → không giữ hàng.

## Phase 6 — order + payment + notification (saga)

- [x] Order aggregate (UUID, `userId` String, `@Version`), state machine, xử lý event muộn (07 §3)
- [x] `POST /api/orders` lấy giá qua ~~Feign~~ `RestClient` (timeout 2s), Idempotency-Key, trả 202
- [x] Consumer: `inventory.reserved/failed`, `payment.success/failed`
- [x] Job timeout (ShedLock)
- [x] payment-service mock; notification-service ghi log
- [x] Data scoping đơn hàng ([10 T9](./10-LEARNING-FROM-REFERENCES.md)): `findByIdAndUserId`, đơn người khác → 404
- [x] Circuit breaker cho product-service (Resilience4j core); fake gateway + callback HMAC + reconcile ([10 T7](./10-LEARNING-FROM-REFERENCES.md)); email thật qua M2M + Mailpit ([10 T8](./10-LEARNING-FROM-REFERENCES.md))

**DoD (chạy cả 7 app từ IDE):**
- Happy path: đơn → PAID; tồn `reserved → sold`; có notification.
- Hết hàng: đơn → CANCELLED (`OUT_OF_STOCK`).
- `PAYMENT_MOCK_SUCCESS_RATE=0` → CANCELLED, tồn kho được trả lại.
- Tắt payment-service → sau 15 phút đơn CANCELLED (`TIMEOUT`), hàng trả lại. Bật lại → `payment.success` muộn → `refund_required=true`.
- Không có message nào trong DLT ở các kịch bản trên.

**Trạng thái (2026-10-29):** xong. Integration test cho từng service (order 12, payment 5, notification 2) và **chạy thật end-to-end** 5 service (product, inventory, order, payment, notification) với Keycloak + MySQL + Kafka của compose: cả 5 kịch bản DoD đạt, 8 topic `.DLT` đều rỗng. Kịch bản timeout chạy với `--order.payment-timeout=40s` thay cho 15 phút. Chưa có gateway (Phase 9) nên gọi thẳng từng service.

## Phase 7 — Observability

- [ ] `COMPOSE_PROFILES` thêm `observability`; dependencies OTel; histogram HTTP
- [ ] Data view Kibana, ILM; dashboard import + **Shop Business**

**DoD:**
- Một đơn hàng hiện thành **một** trace trong Kibana APM, đi qua order → inventory → order → payment → order.
- Từ `traceId` tìm được log của mọi service liên quan.
- Grafana hiển thị RPS, P99, consumer lag, outbox pending.
- Tắt inventory-service → alert `TargetDown` firing.

## Phase 8 — inventory: L2–L5

- [x] L2 (`lockAllActive` theo thứ tự, `innodb_lock_wait_timeout`), L3 (retry ngoài tx, backoff + jitter), L4 (MultiLock, watchdog, unlock sau commit, `@Version` làm fencing), L5 (Lua all-or-nothing + restore, Redis chết → đi thẳng L1)
- [x] ~~Redisson starter~~ Redisson core (starter chưa theo kịp Boot 4), cấu hình từ `spring.data.redis.*`, `lazyInitialization`; `StringRedisTemplate`
- [x] Warmup `SET NX`, đồng bộ counter sau commit (release / nhập kho / giữ hàng bằng L1–L4), reconcile job (lệch 2 lượt liên tiếp mới sửa), gauge `inventory.stock.drift`
- [x] Metric `inventory.reserve{strategy,outcome}`

**DoD:**
- Test đồng thời của Phase 5 pass cho cả 5 strategy.
- L4: kill Redis giữa lúc đang giữ lock → `@Version` chặn ghi đè (test).
- L5: xóa key Redis → request vẫn đúng (fallback/nạp lại); làm lệch Redis → reconcile kéo về khớp DB.

**Trạng thái (2026-10-29):** xong. `mvn -pl inventory-service test`: 36 test, gồm race 200 thread × 5 strategy (cả 5: 100 thành công / 100 OUT_OF_STOCK, không bán vượt), test L4 mất lock (FLUSHALL giữa chừng → `@Version` chặn, CONFLICT), L5 mất key / counter thấp hơn hoặc cao hơn DB / reconcile. Chạy thật end-to-end với Redis compose (L5 mặc định): DB `available` và counter Redis khớp nhau sau happy path, hết hàng, 10 đơn đồng thời, thanh toán bị từ chối. Redis host port đổi được bằng `REDIS_HOST_PORT` (6379 hay bị chiếm).

## Phase 9 — gateway + user-service

- [ ] Gateway route qua Consul, `oauth2Login` + bearer; (tùy chọn) rate limit Redis
- [x] user-service: lazy provisioning, admin API qua Keycloak Admin REST API, bù trừ khi tạo user lỗi, RBAC (làm sớm, trước Phase 2)

**DoD:** đặt hàng end-to-end **qua gateway** bằng token `user1`; admin tạo user mới đăng nhập được.

## Phase 10 — Full Docker & scale

- [ ] Build image 7 app; `COMPOSE_PROFILES=kafka-single,redis-single,observability,apps`
- [ ] `--scale inventory-service=3 --scale order-service=2`
- [ ] Diễn tập: `docker kill` 1 replica inventory giữa lúc tải; restart Kafka; restart MySQL

**DoD:** sau mỗi diễn tập, bất biến tồn kho thỏa, không đơn nào kẹt ở PENDING quá timeout, outbox về 0, DLT trống.

Lab tùy chọn: `kafka-cluster` + `redis-sentinel`. Kill broker leader hoặc Redis master, quan sát failover và hành vi của L4/L5.

## Phase 11 — Benchmark

- [ ] k6 script (04 §9), endpoint reset (profile `bench`)
- [ ] Đo 5 strategy × {1, 3 replica}; ghi kết quả + ảnh Grafana vào `docs/benchmark-results.md`

**DoD:** mọi lượt đo đúng (không oversell); có bảng so sánh throughput / P99 / tải DB kèm giải thích.

## Phase 12 — Lên server

- [ ] Server Linux 8 GB+, Docker, firewall (22/80/443)
- [ ] `.env` server (02-DEPLOYMENT §6.1), reverse proxy TLS, DNS
- [ ] Push image lên registry (GHCR / Docker Hub) hoặc build trên server
- [ ] Backup MySQL hằng ngày; ILM Elasticsearch

**DoD:** đặt hàng qua `https://shop.<domain>`, login qua `https://auth.<domain>`; Kibana/Grafana chỉ truy cập được qua SSH tunnel.

---

## Sau dự án: học Kubernetes

1. Cài k3d/kind, deploy **một** service (inventory) bằng manifest viết tay, theo bảng mapping 02-DEPLOYMENT §7.
2. Dùng `jhipster kubernetes` để sinh manifest cho cả hệ thống rồi so sánh với bản viết tay.
3. Chuyển infra sang operator/Helm: Strimzi (Kafka), Bitnami Redis, MySQL Operator, Keycloak Operator, ECK (Elastic).
4. Thêm HPA cho inventory/order theo CPU hoặc consumer lag (KEDA).
