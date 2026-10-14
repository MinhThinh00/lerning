# 01 — Kiến trúc code

## 1. Cấu trúc repository

```
demo/
├── docs/                        ← tài liệu (file này)
├── deploy/                      ← Docker Compose, config infra (xem 02-DEPLOYMENT)
├── jhipster/
│   └── shop.jdl                 ← định nghĩa toàn bộ apps cho JHipster
├── shop-common/                 ← thư viện dùng chung (Maven jar, KHÔNG phải service)
├── gateway/
├── user-service/
├── product-service/
├── inventory-service/
├── order-service/
├── payment-service/
└── notification-service/
```

Mỗi service là 1 project JHipster độc lập (build, version, image riêng). `shop-common` được `mvn install` rồi khai báo dependency trong từng service.

## 2. Generate bằng JHipster JDL

Thay vì trả lời prompt 7 lần, khai báo 1 file JDL (tái lập được, review được):

```jdl
// jhipster/shop.jdl
application {
  config {
    baseName gateway
    applicationType gateway
    packageName com.shop.gateway
    authenticationType oauth2
    serviceDiscoveryType consul
    databaseType no
    clientFramework no
    serverPort 8080
    buildTool maven
  }
}

application {
  config {
    baseName inventoryService
    applicationType microservice
    packageName com.shop.inventory
    authenticationType oauth2
    serviceDiscoveryType consul
    databaseType sql
    prodDatabaseType mysql
    devDatabaseType mysql
    cacheProvider no
    enableHibernateCache false
    messageBroker no          // tự thêm spring-kafka: không dùng Spring Cloud Stream của JHipster
    serverPort 8083
    buildTool maven
  }
}

// ... user (8081), product (8082), order (8084), payment (8085), notification (8086)
// cùng mẫu với inventoryService
```

```bash
cd jhipster && jhipster jdl shop.jdl --skip-git
```

> Kiểm tra lại tên option với `jhipster jdl --help` của đúng version JHipster đang cài trước khi chạy.
> Entity **không** khai báo trong JDL: entity theo DDD viết tay (mục 3).

Sau khi generate, thêm vào `pom.xml` mỗi service theo nhu cầu:

| Dependency | Service |
|---|---|
| `com.shop:shop-common` | tất cả |
| `spring-kafka` | inventory, order, payment, notification |
| `redisson-spring-boot-starter` | inventory, gateway (rate limit) |
| `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template` | inventory, order |
| `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | tất cả |
| `keycloak-admin-client` | user |
| `org.testcontainers:mysql`, `kafka` | test |

## 3. Bố cục package: JHipster + DDD (hybrid)

**Nguyên tắc: không di chuyển code JHipster sinh ra.** Các package hạ tầng của JHipster (`config`, `security`, `management`, `web.rest.errors`) giữ nguyên chỗ. Code nghiệp vụ viết mới đặt trong 4 layer DDD. Nhờ vậy regenerate/upgrade JHipster không đụng vào code nghiệp vụ.

```
com.shop.inventory/
├── config/ security/ management/      ← JHipster sinh ra, GIỮ NGUYÊN
│
├── domain/                            ═══ DOMAIN ═══ (không phụ thuộc Spring Web/Kafka/Redis)
│   ├── model/                         ← Aggregate, Entity, Value Object (JPA annotation chấp nhận được)
│   ├── event/                         ← Domain event (record)
│   ├── exception/                     ← InsufficientStockException...
│   └── repository/                    ← Interface (port)
│
├── application/                       ═══ APPLICATION ═══ (use case, transaction boundary)
│   ├── command/ query/                ← DTO đầu vào/ra của use case
│   ├── service/                       ← ReservationService, StockAdminService...
│   ├── port/                          ← Interface ra ngoài: StockCounter, LockProvider, EventPublisher
│   └── job/                           ← @Scheduled + @SchedulerLock
│
├── infrastructure/                    ═══ INFRASTRUCTURE ═══ (adapter)
│   ├── persistence/                   ← Spring Data JPA repo + adapter implement domain.repository
│   ├── redis/                         ← RedisStockCounter (Lua), RedissonLockProvider
│   └── messaging/                     ← Kafka listener (inbound adapter)
│
└── web/rest/                          ═══ WEB ═══ (REST controller, request/response DTO)
```

Quy tắc phụ thuộc: `web → application → domain ← infrastructure`. `domain` không import gì từ 3 layer kia. Application chỉ biết **interface port**, infrastructure implement.

Đơn giản hóa so với plan v1:
- Spring Data repository **có thể** đứng trực tiếp làm implementation của domain repository (`interface InventoryJpaRepository extends JpaRepository<...>, InventoryRepository`). Không cần chuỗi Interface → Impl → JPAMapper cho mỗi aggregate.
- Không dùng Lombok `@Data` trên entity (hashCode/toString đệ quy với quan hệ 2 chiều, equals sai với Hibernate proxy). Dùng getter/setter tường minh hoặc `@Getter` + equals theo id.

## 4. `shop-common` — thư viện dùng chung

Gom phần lặp lại ở 6 service để không copy-paste:

```
shop-common/src/main/java/com/shop/common/
├── event/
│   ├── EventEnvelope.java              ← envelope chuẩn (05-MESSAGING)
│   └── EventTypes.java                 ← hằng số topic + eventType
├── outbox/
│   ├── OutboxEvent.java                ← entity
│   ├── OutboxRepository.java
│   ├── OutboxWriter.java               ← ghi outbox trong transaction nghiệp vụ
│   └── OutboxRelay.java                ← @Scheduled + ShedLock, publish lên Kafka
├── inbox/
│   ├── ProcessedEvent.java             ← entity (event_id, consumer)
│   └── InboxGuard.java                 ← tryMarkProcessed(eventId, consumer) trong cùng transaction
├── kafka/
│   └── KafkaCommonConfig.java          ← producer/consumer String JSON, DefaultErrorHandler + DLT
├── security/
│   └── ShopJwtAuthoritiesConverter.java← roles + client_roles → GrantedAuthority (03-SECURITY)
└── autoconfigure/
    └── ShopCommonAutoConfiguration.java← đăng ký bean qua AutoConfiguration.imports

shop-common/src/main/resources/
├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── db/changelog/common/
    ├── outbox_event.xml
    ├── processed_event.xml
    └── shedlock.xml
```

Mỗi service include changelog chung trong `db.changelog-master.xml`:

```xml
<include file="db/changelog/common/outbox_event.xml"/>
<include file="db/changelog/common/processed_event.xml"/>
<include file="db/changelog/common/shedlock.xml"/>
```

## 5. Quy tắc stateless (bắt buộc để scale)

Một service được coi là "scale được" khi chạy 3 replica cùng lúc không sai dữ liệu. Checklist cho mọi code review:

| # | Quy tắc | Vi phạm điển hình |
|---|---|---|
| S1 | Không giữ state nghiệp vụ trong memory giữa các request | Map đếm tồn kho trong RAM |
| S2 | Cache local (Caffeine) chỉ cho dữ liệu chấp nhận stale, TTL ngắn | Cache tồn kho local → mỗi replica 1 con số |
| S3 | Mọi `@Scheduled` phải có `@SchedulerLock` (hoặc idempotent tuyệt đối) | Outbox relay chạy trùng → publish trùng |
| S4 | Mọi Kafka consumer idempotent (Inbox / unique constraint) | Redelivery trừ kho 2 lần |
| S5 | Ghi DB + publish event = outbox trong cùng transaction | Commit DB xong, Kafka lỗi → mất event |
| S6 | Lock phân tán giải phóng **sau** commit | Unlock trước commit → replica khác đọc dữ liệu cũ |
| S7 | Startup không ghi đè state dùng chung | Warmup `SET` đè counter Redis đang chạy |
| S8 | Không đọc/ghi file local (upload, export) | Dùng object storage nếu cần |
| S9 | Graceful shutdown: dừng nhận request, dừng consumer, chờ in-flight | `SERVER_SHUTDOWN=graceful` (đã set trong compose) |
| S10 | Tổng connection pool × số replica < `max_connections` MySQL | 3 replica × 20 + ... > 300 |

## 6. Quy ước cấu hình

- `application.yml`: giá trị mặc định cho **dev chạy từ IDE** (localhost, port expose của compose).
- `application-prod.yml`: không chứa địa chỉ/secret; mọi thứ đến từ env var (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`...).
- Danh sách env var chuẩn: xem `deploy/apps/compose.yaml` (`x-app-env`) — đây là "hợp đồng cấu hình" của mọi service, sau này chuyển 1-1 sang ConfigMap/Secret.
- Custom property dùng `@ConfigurationProperties` (bind được list từ env, validate được), không dùng `@Value` cho list.
