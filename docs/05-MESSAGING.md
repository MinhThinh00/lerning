# 05 — Messaging: Kafka, Outbox, Inbox, DLT

## 1. Nguyên tắc

1. **Không dual-write.** Service không bao giờ vừa ghi DB vừa gọi `kafkaTemplate.send()` trong cùng một request. Event được ghi vào bảng `outbox_event` trong **cùng transaction** với thay đổi nghiệp vụ, rồi relay publish sau.
2. **At-least-once, xử lý idempotent.** Kafka có thể giao lại message. Consumer chống trùng bằng Inbox (`processed_event`) hoặc unique constraint nghiệp vụ.
3. **Message = JSON string + envelope.** Không dùng `JsonSerializer` kèm type header `__TypeId__`, vì cách đó buộc producer và consumer chia sẻ class Java và vỡ khi đổi package. Ở v1 còn thêm lỗi generic `DomainEvent<T>` bị erase thành `LinkedHashMap`.
4. **Key = `orderId`** cho mọi topic. Mọi event của một đơn rơi vào cùng partition, và không có hot partition khi một sản phẩm bán chạy.
5. **Topic tạo bởi `deploy/infra/kafka/topics.sh`** (job `kafka-init`). Code không khai báo `NewTopic`.

## 2. Topic

Partition và replication factor lấy từ env (`KAFKA_PARTITIONS`, mặc định 3; RF = 1 với single, 3 với cluster).

| Topic | Producer | Consumer (group = tên service) | Retention |
|---|---|---|---|
| `order.created` | order | inventory | 7d |
| `inventory.reserved` | inventory | order | 7d |
| `inventory.failed` | inventory | order | 7d |
| `order.confirmed` | order | payment | 7d |
| `payment.success` | payment | order | 30d |
| `payment.failed` | payment | order | 30d |
| `order.paid` | order | inventory, notification | 30d |
| `order.cancelled` | order | inventory, notification | 7d |
| `<topic>.DLT` | error handler | (người vận hành) | 30d |

So với v1: bỏ `notification.send` (notification tiêu thụ domain event trực tiếp), bỏ `audit.events` (không có producer), thêm `order.paid`, và mọi topic có consumer đều có DLT.

## 3. Envelope & payload

```json
{
  "eventId": "7b0c6a52-1d1e-4a0e-9a57-2a3c3f1d9f10",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "occurredAt": "2026-10-12T08:15:30.123Z",
  "source": "order-service",
  "aggregateType": "Order",
  "aggregateId": "c1f6...-orderId",
  "payload": { }
}
```

```java
// shop-common
public record EventEnvelope(
    String eventId, String eventType, int eventVersion, Instant occurredAt,
    String source, String aggregateType, String aggregateId, JsonNode payload) {

    public <T> T payloadAs(ObjectMapper om, Class<T> type) {
        return om.convertValue(payload, type);
    }
}
```

Kafka record: `key = orderId`, `value = envelope JSON`, headers `eventId`, `eventType`, `traceparent`.

### Payload

| eventType (topic) | Payload |
|---|---|
| `OrderCreated` (order.created) | `orderId, userId, items[{productId, quantity}], totalAmount, currency` |
| `InventoryReserved` (inventory.reserved) | `orderId, items[{productId, quantity}], strategy` |
| `InventoryFailed` (inventory.failed) | `orderId, reason (OUT_OF_STOCK / LOCK_TIMEOUT / CONFLICT / NOT_FOUND), productId` |
| `OrderConfirmed` (order.confirmed) | `orderId, userId, totalAmount, currency` |
| `PaymentSucceeded` (payment.success) | `orderId, paymentId, amount, currency` |
| `PaymentFailed` (payment.failed) | `orderId, reason` |
| `OrderPaid` (order.paid) | `orderId, userId, items[], totalAmount, currency` |
| `OrderCancelled` (order.cancelled) | `orderId, userId, reason, items[]` |

Mọi id dạng String (`orderId` là UUID, `userId` là Keycloak `sub`), `productId` là `long`, tiền là `BigDecimal` serialize dạng string.

Muốn đổi schema thì tăng `eventVersion`, và consumer phải đọc được cả version cũ lẫn mới trong giai đoạn chuyển tiếp.

## 4. Outbox

### 4.1 Bảng (changelog chung trong `shop-common`)

```sql
CREATE TABLE outbox_event (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,   -- thứ tự publish
  event_id       VARCHAR(36)  NOT NULL UNIQUE,
  aggregate_type VARCHAR(50)  NOT NULL,
  aggregate_id   VARCHAR(64)  NOT NULL,
  event_type     VARCHAR(100) NOT NULL,
  topic          VARCHAR(100) NOT NULL,
  message_key    VARCHAR(100) NOT NULL,
  payload        JSON         NOT NULL,                -- envelope đầy đủ
  trace_parent   VARCHAR(64),                          -- W3C traceparent lúc ghi
  status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED
  attempts       INT          NOT NULL DEFAULT 0,
  last_error     VARCHAR(500),
  created_at     DATETIME(6)  NOT NULL,
  published_at   DATETIME(6),
  KEY idx_outbox_pending (status, id)
);
```

Không có trạng thái `FAILED`. Payload đã hợp lệ từ lúc ghi, nên lỗi khi publish chỉ có thể là lỗi tạm thời của Kafka: cứ thử lại mãi, đồng thời bắn alert `OutboxBacklog`.

### 4.2 Ghi (trong transaction nghiệp vụ)

```java
@Transactional
public OrderResponse createOrder(String userId, CreateOrderCommand cmd) {
    Order order = Order.create(userId, pricedItems(cmd), cmd.shippingAddress());
    orders.save(order);
    outbox.write(Topics.ORDER_CREATED, "Order", order.getId(), order.getId(),   // key = orderId
                 "OrderCreated", OrderCreated.from(order));
    return OrderResponse.from(order);
}
```

`OutboxWriter.write(...)` dựng envelope, lấy `traceparent` từ span hiện tại, và insert. Method có `@Transactional(propagation = MANDATORY)` nên gọi ngoài transaction sẽ lỗi ngay.

### 4.3 Relay

```java
@Component
class OutboxRelay {

    @Scheduled(fixedDelayString = "${shop.outbox.poll-interval-ms:500}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M")   // 1 replica chạy / lượt
    public void relay() {
        List<OutboxEvent> batch = repo.findPending(props.batchSize());  // ORDER BY id LIMIT n
        for (OutboxEvent e : batch) {
            try {
                ProducerRecord<String, String> rec =
                    new ProducerRecord<>(e.getTopic(), e.getMessageKey(), e.getPayload());
                rec.headers().add("eventId", bytes(e.getEventId()));
                rec.headers().add("eventType", bytes(e.getEventType()));
                if (e.getTraceParent() != null) rec.headers().add("traceparent", bytes(e.getTraceParent()));

                kafka.send(rec).get(10, TimeUnit.SECONDS);       // có timeout (v1: get() vô hạn)
                repo.markPublished(e.getId(), Instant.now());
            } catch (Exception ex) {
                repo.markAttemptFailed(e.getId(), truncate(ex.getMessage()));
                break;                                           // dừng batch → giữ thứ tự
            }
        }
    }
}
```

Các điểm đã sửa so với v1:
- **`@Transactional` gọi nội bộ không có tác dụng** (self-invocation). Bản mới không dựa vào nó: mỗi `markPublished` là một update riêng. Nếu crash giữa `send` và `markPublished`, event sẽ được gửi lại, và Inbox phía consumer xử lý trùng.
- **Chạy nhiều replica:** ShedLock đảm bảo chỉ một relay. Phương án thay thế là `SELECT … FOR UPDATE SKIP LOCKED`, nhưng cách đó làm mất thứ tự giữa các replica.
- **Payload gửi đi chính là envelope** đã lưu (v1 parse thành `Object` rồi gửi map thô).

Khi cần throughput cao hơn: gửi cả batch bất đồng bộ, chờ tất cả future rồi mới mark. Producer idempotent với `max.in.flight ≤ 5` vẫn giữ thứ tự trong từng partition.

### 4.4 Dọn dẹp & giám sát

- Job `outbox-cleanup` (ShedLock, mỗi giờ): xóa `PUBLISHED` cũ hơn 3 ngày.
- Gauge `outbox.pending.events` (số `PENDING`) và `outbox.oldest.pending.age.seconds`, dùng cho alert `OutboxBacklog`.

## 5. Inbox (idempotent consumer)

```sql
CREATE TABLE processed_event (
  event_id     VARCHAR(36) NOT NULL,
  consumer     VARCHAR(100) NOT NULL,     -- vd "inventory:order.paid"
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id, consumer)
);
```

```java
@KafkaListener(topics = Topics.ORDER_PAID)
public void onOrderPaid(ConsumerRecord<String, String> record) {
    EventEnvelope env = envelopes.parse(record.value());
    OrderPaid payload = env.payloadAs(om, OrderPaid.class);
    reservationService.commit(env.eventId(), payload);
}

// application service
@Transactional
public void commit(String eventId, OrderPaid evt) {
    if (!inbox.tryMarkProcessed(eventId, "inventory:order.paid")) return;   // INSERT IGNORE → 0 dòng = trùng
    ... // nghiệp vụ + outbox (nếu có) trong CÙNG transaction với dòng inbox
}
```

Dòng inbox, thay đổi nghiệp vụ và outbox đều nằm trong một transaction: hoặc cả ba cùng commit, hoặc không gì cả.

Job dọn `processed_event` cũ hơn 14 ngày. Khoảng này phải dài hơn mọi khả năng redelivery.

## 6. Cấu hình Spring Kafka (mặc định trong `shop-common`)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092            # prod: env SPRING_KAFKA_BOOTSTRAP_SERVERS
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      properties:
        enable.idempotence: true
        linger.ms: 5
        delivery.timeout.ms: 30000
    consumer:
      group-id: ${spring.application.name}
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 50
    listener:
      ack-mode: record             # container commit sau khi listener trả về thành công
      concurrency: ${KAFKA_LISTENER_CONCURRENCY:1}
      observation-enabled: true    # trace xuyên Kafka
    template:
      observation-enabled: true
```

Những thứ đã bỏ so với v1, và lý do:

| Cấu hình v1 | Vấn đề |
|---|---|
| `JsonSerializer` / `JsonDeserializer` + `trusted.packages` | Ràng buộc class Java giữa service, vỡ với generic |
| `listener.type: batch` | Mâu thuẫn với listener nhận một record |
| `ack-mode: MANUAL_IMMEDIATE` | Không cần: `RECORD` + error handler đã đủ at-least-once, và tránh quên `ack()` |
| `max.in.flight.requests.per.connection: 1` | Không cần khi đã bật idempotence; làm giảm throughput |
| `isolation.level: read_committed` | Không dùng Kafka transaction nên vô nghĩa |
| `concurrency: 3` cố định | Tổng thread consumer (replica × concurrency) > số partition thì phần dư ngồi không |

Quy tắc concurrency: `replica × concurrency ≤ partitions`. Với 3 partition: 1 replica dùng concurrency 3, 3 replica dùng concurrency 1.

## 7. Lỗi & Dead Letter Topic

```java
@Bean
DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
        // partition -1: để Kafka tự chọn. v1 giữ nguyên số partition → lỗi khi DLT ít partition hơn
        (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));

    var backOff = new ExponentialBackOffWithMaxRetries(3);   // v1 gọi setMaxAttempts trên ExponentialBackOff: không tồn tại
    backOff.setInitialInterval(1_000);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(10_000);

    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        JsonProcessingException.class,         // message hỏng → retry vô ích
        IllegalArgumentException.class,
        DomainRuleViolationException.class);   // vi phạm nghiệp vụ không tự khỏi
    return handler;
}
```

Spring Boot tự gắn bean `CommonErrorHandler` vào listener container factory.

Phân loại lỗi trong listener:

| Tình huống | Xử lý |
|---|---|
| Event trùng | Inbox bỏ qua, coi là thành công |
| Event "đến muộn" hợp lệ (vd `inventory.reserved` cho đơn đã `CANCELLED`) | Xử lý nghiệp vụ, **không** throw (xem 07-ORDER-SERVICE) |
| Lỗi tạm thời (DB/Redis timeout) | Throw → retry với backoff → DLT |
| Message hỏng / sai schema | Throw exception not-retryable → DLT ngay |

DLT không có consumer tự động. Alert `DeadLetterReceived` sẽ báo. Người vận hành xem message trong Kafka UI, sửa nguyên nhân, rồi replay bằng công cụ (vd `kcat`) hoặc endpoint admin.

## 8. Trace xuyên outbox

Observation của Kafka tự truyền `traceparent` khi gửi trực tiếp. Với outbox, relay chạy ở thread và thời điểm khác, nên `trace_parent` được lưu lúc ghi outbox. Relay tạo span con từ giá trị đó (`Propagator.extract`) trước khi `send`. Kết quả là Kibana APM hiển thị một trace liền mạch: `POST /api/orders` → `order.created` → inventory reserve → `inventory.reserved` → ...

## 9. Lệnh thường dùng

```bash
# trong deploy/
docker compose exec kafka kafka-topics --bootstrap-server localhost:29092 --describe --topic order.created
docker compose exec kafka kafka-consumer-groups --bootstrap-server localhost:29092 --describe --group inventoryservice
docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:29092 \
  --topic order.created --from-beginning --property print.key=true --property print.headers=true --max-messages 5
```
