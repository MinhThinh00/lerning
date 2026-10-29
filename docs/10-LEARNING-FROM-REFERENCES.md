# 10 — Học từ 2 dự án tham khảo: pattern, kỹ năng, áp dụng

Hai dự án tham khảo:

| Ký hiệu | Đường dẫn | Là gì |
|---|---|---|
| **XX** | `D:\Thinh\private\xxxx.com-section-ddd-24-27042025\xxxx.com-27-04-25` | Khóa học DDD: flash sale vé tàu Tết. Boot 3.3, multi-module DDD, Redis Sentinel + Redisson, Guava cache, Resilience4j, JMeter. ~45 file Java, không có test |
| **SX** | `D:\Thinh\private\selex` | Hệ thống thật: xe máy điện + trạm đổi pin. JHipster 7 / Boot 2.7, Kafka + MQTT, Kafka Streams, OnePay, framework chung `be-selex-parent/selex-framework` |

Đường dẫn rút gọn dùng trong file này:
- `XX/` = gốc của XX.
- `SF/` = `selex/be-selex-parent/selex-framework`.
- `SX/` = `selex/`.

## 0. Cách dùng tài liệu này

Junior thường học bằng cách **copy pattern**. Middle được đánh giá ở chỗ biết **khi nào pattern hỏng**: đọc code của người khác và chỉ ra được nó sai trong tình huống nào (concurrency, retry, restart, scale N replica, mất mạng), chứng minh được bằng test, rồi sửa.

Hai dự án này hợp để học vì có cả hai thứ:
- **Pattern tốt** để mang sang demo.
- **Bug kinh điển** để luyện "đọc code tìm lỗi". Mọi bug liệt kê ở đây đã được đối chiếu với code thật.

Mỗi chủ đề (§2) viết theo cùng một khung:

> **Vấn đề** → **Tham khảo đúng / sai** → **Kỹ năng rút ra** → **Áp vào demo** → **Bước làm** → **DoD**

Quy tắc khi học: **với mỗi bug, viết test tái hiện trước rồi mới sửa.** Test đỏ, sửa, test xanh. Nếu chỉ đọc mà không tái hiện được thì chưa thật sự hiểu bug đó.

---

## 1. Kỹ năng junior → middle mà 2 dự án này dạy

| # | Kỹ năng | Junior thường làm | Middle làm | Chủ đề |
|---|---|---|---|---|
| K1 | Nghĩ theo failure mode | Code chạy đúng happy path | Hỏi "nếu chết ở giữa bước 2 và 3 thì sao?", "nếu chạy 2 lần?", "nếu 3 replica cùng chạy?" | T2, T3, T5, T6, T7 |
| K2 | Nhất quán khi có 2 hệ thống (DB + Redis/Kafka/cổng thanh toán) | Ghi lần lượt, hy vọng không lỗi | Outbox, bù trừ (compensation), đối soát (reconcile) | T3, T5, T7 |
| K3 | Idempotency | Không nghĩ tới | Unique key, inbox, so sánh trạng thái trước khi chuyển | T5, T7 |
| K4 | Concurrency có chứng minh | "Có `synchronized` / có lock rồi" | Test N thread + kiểm tra bất biến, biết lock nào đúng ở tầng nào | T2, T3 |
| K5 | Cache có chủ đích | `@Cacheable` mọi chỗ | Biết stampede, penetration, lệch L1 giữa replica, TTL, invalidation | T4 |
| K6 | Thiết kế cho N replica | Code chạy 1 instance | Scheduler có lock, rate limit phân tán, không state trong bộ nhớ | T6, T12 |
| K7 | Bảo mật ở mức thiết kế | Chỉ `@PreAuthorize` | Data scoping, phân biệt token user / token máy, không log/commit secret | T8, T9, T15 |
| K8 | Giữ kiến trúc bằng test | Quy ước miệng | ArchUnit chặn phụ thuộc sai | T1 |
| K9 | Đo trước khi tối ưu | Đoán nút thắt | Load test đúng cách, đọc metric, biết khi nào chính máy bắn tải là nút thắt | T13 |
| K10 | Code review | Đọc cú pháp | Có checklist anti-pattern (§4) | Tất cả |

---

## 2. Các chủ đề

Bảng tổng quan. Cột "Phase" trỏ vào [09-IMPLEMENTATION-PLAN](./09-IMPLEMENTATION-PLAN.md).

| # | Chủ đề | Nguồn | Phase demo | Ưu tiên |
|---|---|---|---|---|
| T1 | ArchUnit giữ layer DDD | XX (thiếu), SX (có boilerplate) | Ngay bây giờ | ★★★ |
| T2 | Tái hiện oversell bằng test đồng thời | XX | 5 | ★★★ |
| T3 | Nhất quán Redis ↔ DB khi trừ kho | XX | 8 | ★★★ |
| T4 | Cache: stampede, penetration, lệch L1 | XX | 4 | ★★ |
| T5 | Không dual-write, consumer không nuốt lỗi | SX (làm sai) | 3 | ★★★ |
| T6 | Scheduler an toàn khi scale | SX | 3, 6 | ★★ |
| T7 | Payment: callback idempotent, HMAC, đối soát | SX | 6 | ★★★ |
| T8 | Token user vs token máy (M2M) giữa service | SX | 6 | ★★ |
| T9 | Data scoping (seller chỉ thấy dữ liệu của mình) | SX | 4, 6 | ★★ |
| T10 | Audit hoạt động bằng annotation + AOP | SX | sau 7 | ★ |
| T11 | ID có thứ tự thời gian + partition bảng order | XX | lab sau 6 | ★ |
| T12 | Rate limit phân tán ở gateway | XX, SX (làm sai) | 9 | ★★ |
| T13 | Load test đúng cách + thí nghiệm pool size | XX | 11 | ★★ |
| T14 | CQRS read model bằng Kafka Streams | SX | lab sau 7 | ★ |
| T15 | Vệ sinh secret | SX (làm sai), demo (đang sai) | Ngay bây giờ | ★★★ |

---

### T1 — ArchUnit giữ layer DDD

**Vấn đề.** DDD chỉ giữ được khi có thứ gì đó *chặn* phụ thuộc sai. Quy ước miệng sẽ bị phá trong lần sửa gấp đầu tiên.

**Tham khảo.**
- XX chia module Maven `start → controller → application → {domain, infrastructure}`, nhưng:
  - `application` import thẳng `RedisInfrasService`, `RedisDistributedLocker` của infrastructure.
  - Domain kéo `spring-boot-starter-data-jpa`; entity là `@Entity @Data` (anemic).
  - POM cha đặt web, resilience4j, guava vào `<dependencies>`, nên domain cũng nhận hết.
  - Kết luận: chia module mà không kiểm tra thì vẫn rò.
- SX: `SX/device-shadow/src/test/java/vn/selex/TechnicalStructureTest.java` (boilerplate JHipster, chỉ kiểm tra layer kỹ thuật).

**Kỹ năng.** Biến quy tắc kiến trúc thành test chạy trong CI (K8).

**Áp vào demo.** user-service đã chia `domain / application / infrastructure / web`, nhưng chưa có gì kiểm tra. Ví dụ `application` có thể import `infrastructure.keycloak` mà không ai biết.

**Bước làm.**
1. Thêm `com.tngtech.archunit:archunit-junit5` (test) vào `shop-parent` `dependencyManagement`.
2. Trong `shop-common` tạo `src/test/.../ArchitectureRules.java` (đóng gói thành `test-jar` hoặc module `shop-test`) với các luật:
   - `domain` không phụ thuộc `application`, `infrastructure`, `web`, `org.springframework.web..`.
   - `application` không phụ thuộc `infrastructure`, `web` (chỉ phụ thuộc port của chính nó).
   - `web` không phụ thuộc `infrastructure`.
   - Class `*Controller` chỉ nằm trong `web..`.
   - Không dùng field injection (`@Autowired` trên field).
3. Mỗi service có `ArchitectureTest` gọi bộ luật trên với package gốc của mình.

**DoD.** Thêm tạm 1 import `infrastructure` vào `AccountService` → `mvn test` đỏ.

---

### T2 — Tái hiện oversell bằng test đồng thời

**Vấn đề.** "Code trừ kho đúng" chỉ có nghĩa khi chạy 200 thread cùng lúc mà bất biến vẫn giữ.

**Tham khảo.**
- XX chia các "level" trừ kho trong `XX/xxxx-application/.../service/order/impl/TicketOrderAppServiceImpl.java` và `StockOrderCacheService.java`:
  - `decreaseStockCache` dùng GET rồi SET, cố ý để race.
  - Bản Lua là atomic.
  - Bản "CAS" thực chất gọi conditional UPDATE của level 1.
- Kiểm tra bằng JMeter `XX/benchmark/jmeter/bin/decreaseStockLevel1.jmx` (10.000 thread, ramp 1s, stock 1.000), rồi **nhìn DB bằng mắt**. Không có assertion.

**Kỹ năng.** Viết test concurrency có assertion (K4):
- Dùng `CountDownLatch` để mọi thread xuất phát cùng lúc.
- Đếm số lần thành công.
- Kiểm tra bất biến trên DB sau khi chạy.

**Áp vào demo.** Đúng bằng DoD của Phase 5 ([04-INVENTORY §2](./04-INVENTORY.md)). Làm thêm phần "đối chứng":

**Bước làm.**
1. Viết một strategy **cố ý sai** `L0_ReadThenWrite`: SELECT stock → kiểm tra trong Java → UPDATE set giá trị mới. Chỉ đặt trong `src/test`.
2. Test chung `ReservationConcurrencyTest` có tham số strategy:
   - Testcontainers MySQL, stock 100, 200 thread, mỗi thread reserve 1.
   - Assert: `success == 100`, `available + reserved + sold == initial`, `available >= 0`.
3. Chạy với L0 → test phải **đỏ** (chứng minh test bắt được oversell). Chạy với L1 → xanh.
4. Khi tới Phase 8, tham số hóa thêm L2–L5 vào cùng test.

**DoD.** L0 đỏ, L1–L5 xanh, chạy ổn định 10 lần liên tiếp (`@RepeatedTest(10)`).

---

### T3 — Nhất quán Redis ↔ DB khi trừ kho

**Vấn đề.** L5 (Lua counter + DB) có 2 nguồn dữ liệu. Mọi điểm chết giữa hai bước ghi đều làm lệch.

**Tham khảo: 4 bug thật trong XX.**

| Bug | Vị trí | Hậu quả |
|---|---|---|
| Warmup chạy trong `@PostConstruct` trên **mọi** replica, hard-code id 4 | `XX/xxxx-application/.../cronjob/WarmupDataBeforeEvent.java` | Restart 1 replica giữa đợt bán → ghi đè stock Redis về giá trị DB cũ → **oversell** |
| Trừ Redis xong, DB lỗi → `catch (Exception)` → `return false`, không cộng lại Redis | `TicketOrderAppServiceImpl.decreaseStockLevel3CAS` | Redis thấp hơn DB vĩnh viễn → **undersell** (bán thiếu) |
| Lua `tonumber(redis.call('GET', k))` khi key chưa có | `StockOrderCacheService` | Script lỗi → request 500 thay vì fallback |
| `catch` nuốt exception trong `@Transactional(rollbackFor = Exception.class)` | như trên | `rollbackFor` vô tác dụng vì exception không thoát ra ngoài method |

**Kỹ năng.** K1 + K2:
- Với mỗi lần ghi vào 2 hệ thống, liệt kê các điểm chết và cách hệ thống tự hồi phục: bù trừ ngay, reconcile định kỳ, hoặc fencing.
- Biết `@Transactional` chỉ rollback khi exception **thoát ra khỏi** proxy.

**Áp vào demo.** Thiết kế L5 trong [04-INVENTORY](./04-INVENTORY.md) đã có restore + reconcile. Bước làm dưới đây là để kiểm chứng thiết kế đó.

**Bước làm (Phase 8).**
1. **Warmup an toàn:**
   - `SET stock:{id} <db_available> NX`: chỉ nạp khi key chưa có, không bao giờ ghi đè.
   - Chạy trong `ApplicationReadyEvent` + ShedLock (hoặc lazy khi gặp key thiếu), không dùng `@PostConstruct`.
2. **Lua chịu được key thiếu:** `local v = redis.call('GET', KEYS[1]); if not v then return -2 end`. Java nhận `-2` thì nạp lại bằng `SET NX` rồi thử lại 1 lần, hoặc fallback sang L1.
3. **Bù trừ:**
   - Trừ Redis OK nhưng DB thất bại (exception hoặc 0 row) → `INCRBY` trả lại trong `finally`/`catch`, và **ném lại** exception.
   - Đăng ký bù trừ bằng `TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)` để bắt cả trường hợp rollback xảy ra ở tầng ngoài.
4. **Reconcile job** (ShedLock): so `stock:{id}` với `inventory.available`. Lệch thì kéo Redis về DB (DB là nguồn sự thật). Phát gauge `inventory_redis_drift`.
5. **Test sự cố:**
   - Kill app giữa bước Redis và bước DB: dùng breakpoint, hoặc inject `FailingInventoryRepository` ném lỗi sau khi Redis đã trừ.
   - Restart 1 replica giữa lúc đang có tải → stock không bị ghi đè.
   - `DEL stock:{id}` giữa lúc chạy → request vẫn đúng.

**DoD.**
- Cả 3 kịch bản sự cố: bất biến DB giữ.
- Sau tối đa 1 chu kỳ reconcile, drift = 0.

---

### T4 — Cache: stampede, penetration, lệch L1 giữa replica

**Vấn đề.** Cache sai không làm sập ngay mà trả dữ liệu cũ hoặc dội tải vào DB đúng lúc cao điểm.

**Tham khảo.**

*Pattern tốt:*
- `XX/xxxx-application/.../ticket/cache/TicketDetailCacheService.java` (`getTicketDefaultCacheVip`): cache miss → `tryLock` → **kiểm tra Redis lần nữa** (double-check) → đọc DB → ghi cả giá trị null để chặn penetration.
- `XX/uml/err-local-cache.puml` → `XX/uml/fix-local-cache.puml`: hai sơ đồ giải thích rõ chuyện cache local (L1) trên mỗi replica lệch nhau, và cách client gửi kèm version. **Nên đọc kỹ.**
- `XX/uml/getEventItemUML.puml`: cache miss thì trả bản "degraded" và rebuild cache ở nền.

*Bug:*
- `ticketDetailLocalCache.put(id, null)`: Guava không nhận null → NPE.
- Không key Redis nào có TTL, nên giá trị null chặn penetration và dữ liệu cũ nằm mãi.
- Không lấy được lock thì `return null` (client nhận rỗng) thay vì chờ hoặc thử lại.
- `InterruptedException` bị bọc lại mà không gọi `Thread.currentThread().interrupt()`.
- "Version" = `System.currentTimeMillis()` của từng node, không phải version thật.

**Kỹ năng.** K5: với mỗi cache, trả lời được 5 câu:
- Nguồn sự thật là gì?
- Chấp nhận stale bao lâu?
- Ai invalidate, khi nào?
- Miss đồng loạt thì sao?
- Key không tồn tại thì sao?

**Áp vào demo.** product-service ([07 §8](./07-ORDER-SERVICE.md#8-product-service)) đã cho phép Caffeine TTL 30s. Làm thêm 1 tầng Redis để luyện đủ các tình huống.

**Bước làm (Phase 4).**
1. `ProductQueryService.getById`: Caffeine (TTL 30s, `maximumSize`) → Redis (TTL 10 phút + jitter ±10%) → DB.
2. Miss ở Redis → `RLock lock = redisson.getLock("lock:product:" + id)`, `tryLock(200ms, 5s)`:
   - Lấy được lock → đọc lại Redis → đọc DB → ghi Redis.
   - Không lấy được → chờ ngắn rồi đọc lại Redis, quá hạn thì đọc thẳng DB (không bao giờ trả `null` cho client).
3. Không tồn tại → ghi sentinel `"__NULL__"` với TTL ngắn (60s). Caffeine dùng `Optional` thay vì null.
4. `PUT /api/products/{id}`: commit DB → **sau commit** (`@TransactionalEventListener(AFTER_COMMIT)`) xóa key Redis.
   - Tùy chọn: publish `product.changed` để các replica xóa Caffeine của mình. Đây là bài toán lệch L1 trong sơ đồ của XX.
5. Metric: `cache.gets{result=hit|miss}` có sẵn từ Caffeine/Micrometer, cộng thêm counter cho lần rebuild.

**DoD.**
- k6 bắn 500 VU vào 1 sản phẩm vừa bị xóa cache → DB chỉ nhận 1 query (xem `show global status like 'Questions'` hoặc metric Hikari).
- Gọi id không tồn tại 1000 lần → DB nhận ≤ 1 query/phút.

---

### T5 — Không dual-write, consumer không nuốt lỗi

**Vấn đề.** "Ghi DB rồi gửi Kafka" hoặc "nhận Kafka rồi `catch(Exception){log}`" là hai cách mất dữ liệu phổ biến nhất trong microservice.

**Tham khảo: SX làm sai cả hai.**
- Dual-write: `kafkaTemplate.send` ngay sau khi ghi DB, không có outbox. App chết giữa hai bước → DB đã đổi nhưng event mất (hoặc ngược lại nếu gửi trước).
- Consumer nuốt lỗi:
  - `SX/device-shadow/src/main/java/vn/selex/transport/kafka/KafkaService.java` và `SX/subscription-service/.../transport/kafka/KafkaConsumer.java` đều `catch (Exception e) { log... }`.
  - Không retry, không DLT. Offset vẫn commit, message coi như mất.
  - Một xung đột `@Version` thành ra một lần cập nhật thiết bị bị mất.
- SX có `Event.java` (eventId, issuedAt, issuer, payload) làm envelope; điểm này giống thiết kế của demo.

**Kỹ năng.** K2 + K3:
- Phân biệt lỗi **tạm thời** (retry) với lỗi **vĩnh viễn** (DLT ngay).
- Biết rằng nuốt exception trong listener = commit offset = mất message.

**Áp vào demo.** Đây đúng là phần Phase 3 của [05-MESSAGING](./05-MESSAGING.md). Viết test chứng minh, đừng chỉ tin thiết kế.

**Bước làm (Phase 3).**
1. Làm theo checklist Phase 3 của plan 09.
2. Thêm 2 test "đối chứng" (đặt trong `src/test`, không ở main):
   - `DualWriteDemoTest`: service ghi DB rồi `kafkaTemplate.send`, ném exception giữa hai bước → assert DB có, Kafka không có. Sau đó chạy lại cùng kịch bản với `OutboxWriter` → cả hai cùng có hoặc cùng không.
   - `SwallowingListenerDemoTest`: listener `catch(Exception)` → message biến mất, consumer group không còn lag, DLT rỗng. Đổi sang để exception thoát ra + `DefaultErrorHandler` → message vào `.DLT`.
3. Thêm luật ArchUnit (T1): class trong package `..messaging..` không được gọi `KafkaTemplate.send` ngoài `OutboxRelay`.

**DoD.** 2 test đối chứng cho thấy rõ khác biệt. Luật ArchUnit chặn được `kafkaTemplate.send` ở chỗ khác.

---

### T6 — Scheduler an toàn khi scale

**Vấn đề.** `@Scheduled` chạy trên **mọi** replica. Scale lên 3 thì job billing tính tiền 3 lần.

**Tham khảo.**
- *Tốt:* `SX/bss-service/src/main/java/com/sonpj/config/SchedulerConfiguration.java` dùng ShedLock, có `initialDelay` ngẫu nhiên để các replica không cùng đập vào DB lúc khởi động.
- *Sai:*
  - Nhiều `@Scheduled` billing ở subscription-service và payment-service không có ShedLock.
  - Cursor của batch job (đã xử lý tới đâu) lưu trong cache local của từng instance.
  - Tên lock `updateReserveBatteryTask` bị dùng lại ở notification-service: 2 job khác nhau chặn nhau.
- *Pattern hay:* `SX/subscription-service/.../service/schedule/ScheduleSubscriptionDataUpdateService.java` chia batch thành 1 message Kafka cho mỗi subscription, nên việc xử lý tự trải ra các consumer.

**Kỹ năng.** K6: mọi state của job (lock, cursor, tiến độ) phải nằm ở chỗ dùng chung (DB/Redis), không nằm trong bộ nhớ.

**Áp vào demo.** Outbox relay, cleanup, order timeout, reconcile đều đã dùng ShedLock ([00 §6 D9](./00-OVERVIEW.md)).

**Bước làm (Phase 3 + 6).**
1. Quy ước tên lock: `<service>.<job>`, ví dụ `order.timeout`, `inventory.reconcile`. Hằng số đặt trong class job.
2. `lockAtMostFor` > thời gian chạy tối đa, `lockAtLeastFor` ≥ chênh lệch đồng hồ giữa các node (vài giây).
3. Job timeout (Phase 6) xử lý theo trang:
   - `WHERE status IN (...) AND updated_at < ? ORDER BY updated_at LIMIT 100`.
   - Cursor là chính dữ liệu, không lưu biến tạm.
   - Mỗi đơn xử lý trong 1 transaction riêng, nên 1 đơn lỗi không chặn cả lô.
4. Test: 2 instance (2 `ApplicationContext` hoặc 2 container) cùng chạy job → mỗi đơn chỉ bị hủy 1 lần, chỉ 1 `order.cancelled` trong outbox.

**DoD.** Test 2 instance pass. `--scale order-service=3` (Phase 10) không sinh event trùng.

---

### T7 — Payment: callback idempotent, HMAC, đối soát

**Vấn đề.** Cổng thanh toán gọi callback (IPN) **nhiều lần**, **đồng thời**, hoặc **không gọi**. Đây là chỗ mất tiền thật.

**Tham khảo.**
- *Tốt:*
  - `SX/payment-service/.../provider/onepay/schedule/NoIPNScheduler.java`: định kỳ hỏi OnePay về các giao dịch còn treo vì không nhận được IPN (reconcile).
  - Verify chữ ký HMAC-SHA256 (`OnePaySecureHashService.java`).
- *Sai* (`SX/payment-service/.../provider/onepay/service/OnePayService.java`):
  - Đọc `status == WAITING_FOR_CONFIRMATION` rồi xử lý, không khóa dòng, không unique key. 2 IPN đồng thời → cả hai đều qua bước kiểm tra → xử lý thành công **2 lần**.
  - `case PAYMENT_COMPLETED`: `try { successHandler; updateStatus(SUCCESS); sendNotification } catch (Exception e) { log }`. Nếu `successHandler` chạy xong một nửa rồi lỗi, exception bị nuốt, sau đó `paymentOrderRepository.save(paymentOrder)` vẫn lưu. Trạng thái nửa vời.
  - So sánh hash bằng `String.equals`, không phải constant-time (`MessageDigest.isEqual`), nên có nguy cơ timing attack.
  - Gọi Feign và gửi notification **bên trong** `@Transactional`: giữ connection DB trong lúc chờ mạng, và side effect không rollback được.

**Kỹ năng.** K1 + K3:
- Chuyển trạng thái phải có điều kiện (`UPDATE ... WHERE status = 'PENDING'`, kiểm tra số dòng bị ảnh hưởng).
- Side effect ra ngoài hệ thống (gọi HTTP, gửi thông báo) đi qua outbox, không làm trong transaction.

**Áp vào demo.** payment-service hiện là mock nội bộ ([07 §9](./07-ORDER-SERVICE.md#9-payment-service-mock)). Nâng cấp thành **mock có callback**, giả lập một cổng thanh toán thật.

**Bước làm (Phase 6, phần mở rộng).**
1. Bảng `payment` thêm:
   - `status` (`PENDING | SUCCEEDED | FAILED`).
   - `provider_txn_id`.
   - `version`.
   - Unique `provider_txn_id`.
2. Consume `order.confirmed` → tạo `payment(PENDING)` → "gửi" sang `fake-gateway`: một controller trong chính payment-service, profile `dev`. Fake gateway sau 0.1–3s gọi `POST /api/payments/callback` **từ 1 đến 3 lần, có lúc đồng thời, có lúc không gọi**.
3. Callback:
   - Verify HMAC bằng `MessageDigest.isEqual`.
   - `UPDATE payment SET status=?, provider_txn_id=? WHERE id=? AND status='PENDING'`. Được 0 dòng → callback trùng → trả 200, không làm gì.
   - Được 1 dòng → **cùng transaction** ghi outbox `payment.success/failed`.
   - Không gọi HTTP hay gửi notification trong transaction.
4. `PaymentReconcileJob` (ShedLock): `PENDING` quá 2 phút → hỏi `fake-gateway` `GET /status` → chuyển trạng thái giống callback (dùng chung 1 method).
5. Test:
   - 10 callback đồng thời cho 1 payment → đúng 1 event trong outbox.
   - Sai chữ ký → 401.
   - Không có callback → reconcile chốt được trạng thái.

**DoD.** Ba test trên pass. Saga Phase 6 vẫn pass với fake gateway bật.

---

### T8 — Token user vs token máy (M2M) giữa service

**Vấn đề.** Khi service A gọi B, phải phân biệt hai trường hợp: A gọi **thay mặt user** (B kiểm tra quyền của user), hay A gọi **với danh tính của chính nó** (job, consumer không có user nào).

**Tham khảo.**
- `SF/selex-adapter-web-oauth2/.../client/AuthorizedFeignClient` chuyển tiếp token của user.
- `SF/selex-adapter-web-oauth2/.../client/internal/AuthorizedInternalFeignClient.java` + `TokenInternalRelayRequestInterceptor.java` dùng token client_credentials được cache, có kèm `Accept-Language`.
- *Sai:* `UserContext` là bean `@Scope(request)` nhưng được dùng trong aspect chạy ở code async và Kafka consumer, nơi không có request → `ScopeNotActiveException` hoặc dữ liệu sai.

**Kỹ năng.** K7: trong consumer hoặc job **không có user**, chỉ có danh tính service. Thiết kế API nội bộ cho rõ hai đường gọi.

**Áp vào demo.**
- Giá sản phẩm là public nên order → product không cần token ([03 §6](./03-SECURITY.md#6-service-to-service)).
- Nhưng **notification-service cần email của user** để gửi, và nó chạy trong Kafka consumer, không có user. Đây chính là ca M2M.

**Bước làm (Phase 6).**
1. user-service: `GET /api/internal/users/{sub}/contact` → `{email, firstName, languageKey}`, `@PreAuthorize("hasRole('SERVICE')")` (role `ROLE_SERVICE` gán cho service account trong realm).
2. `shop-common`: `ServiceRestClientConfig` tạo `RestClient` với `OAuth2ClientHttpRequestInterceptor` (registration `shop-service`, `client_credentials`). user-service đã có sẵn mẫu trong `KeycloakAdminClientConfig`, chỉ cần tổng quát hóa nó.
3. Kèm theo: timeout (connect 1s, read 2s), retry 1 lần cho lỗi mạng, circuit breaker (Resilience4j). Mẫu cấu hình xem `XX/xxxx-controller/.../http/HiController.java` + `application.yml` của XX.
4. notification-service gọi endpoint trên khi nhận `order.paid`. Lỗi tạm thời → để exception thoát ra → retry/DLT (T5).
5. Test: gọi `/api/internal/...` bằng token của `user1` → 403. Bằng token service → 200.

**DoD.** Thông báo có email thật của user. Tắt user-service → message đi vào retry rồi DLT, không mất.

---

### T9 — Data scoping: seller chỉ thấy dữ liệu của mình

**Vấn đề.** `@PreAuthorize("hasAuthority('product:write')")` chỉ trả lời câu "có được sửa sản phẩm không". Câu "**sản phẩm nào**" thì nó không trả lời. Bỏ sót câu thứ hai là lỗ IDOR, một trong những lỗ hổng phổ biến nhất (OWASP API1).

**Tham khảo.**
- `SF/selex-app-core/.../core/aop/XDataAuthorizationAOP.java` + annotation `XDataAuthorization` / `MatrixPermission`: từ role và partner của user sinh ra một filter RSQL, ghép vào query. Ví dụ dùng: `SX/battery-service/src/main/java/com/sonpj/dao/BatteryDataRSQLDao.java`.
- Bản remote (`SelexDataRemoteAuthorizationAOP`) hỏi danh sách id được phép từ service khác qua Feign.
- Đây là ý tưởng giá trị nhất của SX. Nhưng làm bằng AOP + chuỗi RSQL thì khó đọc, khó test. Ở demo làm đơn giản hơn.

**Kỹ năng.** K7: phân quyền 2 lớp, gồm loại thao tác và **đối tượng** bị tác động. demo đã có lớp 2 ở user-service (`AccessPolicy`), giờ áp cho dữ liệu nghiệp vụ.

**Áp vào demo.**
- product: `seller_id` (= `sub`). Seller chỉ sửa sản phẩm của mình; admin sửa mọi sản phẩm.
- order: user chỉ xem đơn của mình; support/admin xem tất cả.

**Bước làm (Phase 4 + 6).**
1. Thêm `seller_id VARCHAR(36)` vào `product`.
2. Đọc danh sách: repository nhận `Specification<Product>` từ `ProductAccessPolicy.scopeFor(caller)`. Admin → không lọc; seller → `seller_id = :sub`.
3. Ghi theo id: load entity → `ProductAccessPolicy.checkCanModify(caller, product)` → không được thì trả **404 (không phải 403)** để không lộ việc id đó có tồn tại.
4. Order: `GET /api/orders/{id}` dùng `findByIdAndUserId` cho user thường.
5. Test: `seller1` sửa sản phẩm của `seller2` → 404. `user1` xem đơn của `user2` → 404.

**DoD.** Test IDOR cho mọi endpoint có `{id}` trong product và order.

---

### T10 — Audit hoạt động bằng annotation + AOP (tùy chọn)

**Tham khảo.**
- `SF/selex-app-core/.../core/aop/XActivityAuditAOP.java` + `@XActivityAudit`: tạo log hoạt động nghiệp vụ từ template và gửi Kafka.
- Javers audit entity (`JaversEntityAuditResource.java` ở mỗi service).
- *Sai:* `@Async` gọi từ chính class của nó (`XActivityAuditAOP.onAudit`, `ApiLoggingFilter.logRequest`) đi qua `this.`, không qua proxy Spring, nên **không bao giờ chạy async**.

**Kỹ năng.** Hiểu proxy của Spring: `@Transactional`, `@Async`, `@Cacheable` đều mất tác dụng khi gọi nội bộ trong cùng class.

**Áp vào demo (sau Phase 7).**
- `@AuditActivity(action = "USER_LOCKED", target = "#id")` trên các method admin của user-service.
- Aspect ghi vào **outbox** (topic `audit.activity`), không ghi Kafka trực tiếp (T5).
- Viết 1 test chứng minh lỗi self-invocation: method A gọi `this.b()` có `@Async` → kiểm tra cùng thread.

---

### T11 — ID có thứ tự thời gian + partition bảng order (lab)

**Tham khảo.**
- XX chia bảng `ticket_order_yyyyMM` theo tháng. Mã đơn chứa timestamp nên tra là biết bảng (`XX/xxxx-infrastructure/.../repository/OrderDeductionInfrasRepositoryImpl.java`, `XX/environment/mysql/init/ticket_init.sql`).
- *Sai:*
  - Mã đơn = `"OKX-SGN-" + random(1..9) + "-" + currentTimeMillis()` → cùng millisecond là trùng.
  - Lúc insert lấy tháng bằng `LocalDate.now()`, lúc tra lấy từ timestamp với `ZoneId.systemDefault()` → sát ranh giới tháng sẽ lệch.
  - Endpoint `/order/{userId}/list?ntable=` ghép tham số của user vào tên bảng → **SQL injection**.

**Kỹ năng.**
- ID nên có thứ tự thời gian và duy nhất toàn cục.
- Định tuyến theo thời gian phải dùng một múi giờ cố định (UTC).
- Không bao giờ ghép input của user vào SQL, kể cả tên bảng.

**Áp vào demo.** `orders.id` hiện là UUID v4, ngẫu nhiên nên làm B-tree index bị phân mảnh.

**Bước làm (lab).**
1. Đổi sang **UUIDv7** (có thứ tự thời gian; ví dụ thư viện `com.fasterxml.uuid:java-uuid-generator`). Đo tốc độ insert 1 triệu dòng: v4 so với v7.
2. Thử MySQL native partition: `PARTITION BY RANGE (TO_DAYS(created_at))` theo tháng. Lưu ý MySQL yêu cầu cột partition nằm trong mọi unique key, nên PK phải đổi thành `(id, created_at)`. Ghi lại trade-off này.
3. So sánh với cách chia bảng thủ công của XX: ai định tuyến (DB hay app), xóa dữ liệu cũ thế nào (`DROP PARTITION` so với `DROP TABLE`).

**DoD.** File `docs/lab-order-partitioning.md` có số đo và kết luận.

---

### T12 — Rate limit phân tán ở gateway

**Tham khảo.**
- XX: Resilience4j `@RateLimiter` + `@CircuitBreaker` có fallback (`XX/xxxx-controller/.../http/HiController.java`). Giới hạn **theo từng instance**.
- SX: `SF/selex-app-core/.../core/aop/RateLimiter.java` dùng semaphore trong bộ nhớ, **không bao giờ release** permit nên dần dần chặn hết. Cũng chỉ theo từng instance.

**Kỹ năng.** K6: giới hạn "10 request/giây/user" mà chạy 3 replica thì thành 30. Rate limit phải dùng chung trạng thái (Redis) hoặc đặt ở edge.

**Bước làm (Phase 9).**
1. Gateway: Spring Cloud Gateway `RequestRateLimiter` + `RedisRateLimiter` (token bucket bằng Lua), `KeyResolver` theo `sub` trong JWT (chưa đăng nhập thì theo IP).
2. Riêng `POST /api/orders`: giới hạn chặt hơn (ví dụ 5/s/user, burst 10).
3. Test: scale gateway lên 2, bắn 50 request/s cho 1 user → tổng số request qua được ≈ giới hạn, không gấp đôi.

**DoD.** Có header `X-RateLimit-Remaining`. Vượt giới hạn → 429. Đúng giới hạn khi chạy 2 replica gateway.

---

### T13 — Load test đúng cách + thí nghiệm pool size

**Tham khảo.**
- XX: JMeter 10.000 thread, ramp 1s, chạy trên 1 máy, nên chủ yếu đo được giới hạn của **chính máy bắn tải**. Không có assertion.
- Cấu hình Tomcat 500 thread nhưng Hikari pool 10: 490 thread ngồi chờ connection.
- XX có commit cả bản phân phối JMeter (`bin/docs/lib`), `grafana.db`, `.DS_Store`: rác trong repo.

**Kỹ năng.** K9:
- Load test cần có kịch bản, ngưỡng pass/fail (`thresholds`) và metric phía server để biết nút thắt nằm ở đâu.
- Phải phân biệt giới hạn của hệ thống với giới hạn của máy bắn tải: CPU máy k6 > 80% thì kết quả vô nghĩa.

**Bước làm (Phase 11, bổ sung cho [04 §9](./04-INVENTORY.md)).**
1. Script k6 có `thresholds`: `http_req_failed < 1%`, `p(99) < 500ms`. Kiểm tra correctness sau khi chạy: gọi endpoint check bất biến.
2. **Thí nghiệm pool:** cố định strategy L1, đổi `spring.datasource.hikari.maximum-pool-size` ∈ {5, 10, 20, 50}. Ghi lại throughput, P99, `hikaricp_connections_pending`, `Threads_running` của MySQL. Tìm điểm tăng pool mà không tăng throughput nữa.
3. Virtual threads (`spring.threads.virtual.enabled=true`) so với platform threads ở cùng pool size: virtual thread **không** làm DB nhanh hơn. Chứng minh bằng số liệu.
4. Ghi vào `docs/benchmark-results.md`.

**DoD.** Có bảng pool size × throughput × P99 kèm giải thích nút thắt.

---

### T14 — CQRS read model bằng Kafka Streams (lab)

**Tham khảo.**
- `SX/device-shadow/src/main/java/vn/selex/config/KafkaStreamConfiguration.java` + `processor/BatteryProcessor.java`, `BssProcessor.java`: KTable join theo foreign key trên topic CDC, ghi ra topic `*_sink` đã denormalize.
- `SX/device-shadow/.../service/impl/DeviceShadowServiceImpl.java`: mô hình desired/reported/delta (giống AWS IoT shadow), có `@Version`, xử lý LWT. Đáng đọc nếu sau này làm IoT.

**Áp vào demo (sau Phase 7).** Service mới `order-query` (hoặc module trong order-service):
1. Kafka Streams đọc `order.*` + `payment.*` → KTable `order_summary` (trạng thái, tổng tiền, thời gian từng bước).
2. Interactive query hoặc ghi ra topic `order-summary` → dùng cho dashboard "đơn theo trạng thái" mà không query DB của order.
3. Bài học: eventual consistency của read model, rebuild bằng cách reset offset.

---

### T15 — Vệ sinh secret

**Tham khảo.**
- SX commit nhiều thứ không nên commit:
  - Secret client Keycloak và password trong `SX/VehicleMaintenanceService/src/main/resources/config/application-uat.yml`.
  - `keystore.p12` ở mọi service.
  - Log cả access token ở mức debug.
- XX hard-code IP LAN và password Sentinel trong `RedissonConfig.java`.

**Demo đang mắc lỗi này:** `deploy/.env` đã bị commit ở `341d220`. Thêm vào `.gitignore` sau đó không gỡ được file đã được track.

**Bước làm (ngay).**
1. `git rm --cached deploy/.env`, commit lại. File vẫn còn trong lịch sử. Vì đây là repo cá nhân, giá trị chỉ dùng cho dev, nên đổi các secret đó là đủ; không cần viết lại lịch sử.
2. Thêm pre-commit hook `gitleaks` (hoặc chạy `gitleaks detect` trong CI).
3. Rà log: không log header `Authorization`, không log token/password. Thêm mask trong logback nếu cần (giống ý tưởng masking của `SF/selex-adapter-web-oauth2/.../config/filter/ApiLoggingFilter.java`).

**DoD.** `git ls-files deploy/.env` rỗng; `gitleaks detect` sạch.

---

## 3. Lộ trình gợi ý: chèn vào plan 09

| Thời điểm | Làm | Chủ đề |
|---|---|---|
| **Ngay bây giờ** | Gỡ `deploy/.env`, gitleaks; ArchUnit cho user-service | T15, T1 |
| Phase 3 | Outbox/inbox + 2 test đối chứng; quy ước ShedLock | T5, T6 |
| Phase 4 | Cache 2 tầng có lock + null sentinel; `seller_id` + data scoping | T4, T9 |
| Phase 5 | Test đồng thời có tham số, kèm L0 cố ý sai | T2 |
| Phase 6 | M2M user→notification; payment có fake gateway + callback idempotent + reconcile; scoping cho order | T8, T7, T9, T6 |
| Phase 7+ | Audit qua outbox | T10 |
| Phase 8 | Warmup `SET NX`, Lua chịu key thiếu, bù trừ, reconcile, test sự cố | T3 |
| Phase 9 | Rate limit Redis ở gateway | T12 |
| Phase 11 | k6 có thresholds, thí nghiệm pool size, virtual threads | T13 |
| Lab | UUIDv7 + partition; Kafka Streams read model | T11, T14 |

Ước lượng thêm so với plan 09: khoảng **5–7 ngày công**. T7 và T3 tốn nhiều nhất, nhưng cũng đáng học nhất.

---

## 4. Checklist code review (rút từ bug của 2 dự án)

Khi review code (của mình hoặc người khác), hỏi lần lượt:

**Transaction & nhất quán**
- [ ] Có gọi HTTP, gửi Kafka hay gửi notification **bên trong** `@Transactional` không? → đưa ra ngoài, hoặc qua outbox. *(SX OnePayService)*
- [ ] Ghi 2 hệ thống (DB + Redis/Kafka): chết giữa chừng thì sao? Có bù trừ hoặc reconcile không? *(XX Level3CAS)*
- [ ] `catch (Exception)` trong method `@Transactional` có nuốt mất exception khiến rollback không xảy ra không? *(XX)*
- [ ] Gọi method có `@Transactional`/`@Async`/`@Cacheable` từ chính class của nó (self-invocation)? *(SX XActivityAuditAOP)*

**Concurrency & idempotency**
- [ ] "Đọc trạng thái → kiểm tra → ghi" mà không có lock hoặc `UPDATE ... WHERE` có điều kiện? *(SX callback, XX decreaseStockCache)*
- [ ] Message hoặc callback đến 2 lần thì có xử lý 2 lần không?
- [ ] Code có chạy trong `@PostConstruct` hoặc `@Scheduled` mà sẽ chạy trên **mọi** replica không? *(XX warmup, SX billing)*
- [ ] Có state nào chỉ nằm trong bộ nhớ của instance (cursor, rate limit, counter) không? *(SX)*

**Messaging**
- [ ] Listener có `catch(Exception){log}` không? → message mất. *(SX KafkaService)*
- [ ] Lỗi parse JSON có retry vô hạn không, hay vào DLT ngay?

**Cache**
- [ ] Key có TTL không? Null có được cache với TTL ngắn không? *(XX)*
- [ ] Miss đồng loạt có dội DB không? Không lấy được lock thì trả gì? *(XX return null)*
- [ ] Invalidate trước hay sau commit?

**Bảo mật**
- [ ] Endpoint có `{id}`: kiểm tra quyền trên **đối tượng** chưa (IDOR)? *(T9)*
- [ ] Input của user có đi vào SQL, tên bảng hay tên cột không? *(XX `ntable`)*
- [ ] So sánh chữ ký hoặc token bằng `equals`? → `MessageDigest.isEqual`. *(SX)*
- [ ] Log có chứa token, password hay PII không? Repo có secret không? *(SX, demo)*
- [ ] Request-scoped bean có bị dùng trong async hoặc consumer không? *(SX UserContext)*
- [ ] Thao tác thay đổi dữ liệu có dùng GET không? *(XX)*

**Vận hành**
- [ ] Actuator có expose `*` không? *(XX)*
- [ ] Tạo thread pool hoặc `ObjectMapper` mới trên mỗi request? *(SX AsyncMethodTimeout, XX RedisInfrasServiceImpl)*
- [ ] `InterruptedException` có gọi lại `Thread.currentThread().interrupt()` không? *(XX)*
- [ ] Hostname verifier hoặc SSL có bị tắt không? *(SX MqttService)*

---

## 5. Bài tập đọc code (nên làm trước khi code demo)

Mỗi bài: đọc file → viết ra giấy "bug là gì, xảy ra khi nào, hậu quả gì, sửa thế nào" → đối chiếu với tài liệu này.

1. `XX/xxxx-application/.../service/order/impl/TicketOrderAppServiceImpl.java`: tìm ít nhất 4 vấn đề trong `decreaseStockLevel3CAS`.
2. `XX/xxxx-application/.../cronjob/WarmupDataBeforeEvent.java`: vì sao gây oversell khi scale?
3. `XX/xxxx-application/.../ticket/cache/TicketDetailCacheService.java`: tìm các trường hợp trả `null` hoặc NPE.
4. `SX/payment-service/.../provider/onepay/service/OnePayService.java` (`checkOrderStatusAndHandlerForOnepayMethod`): vẽ timeline 2 IPN đồng thời.
5. `SX/device-shadow/src/main/java/vn/selex/transport/kafka/KafkaService.java`: điều gì xảy ra với offset khi handler ném `OptimisticLockException`?
6. `SF/selex-app-core/.../core/aop/RateLimiter.java`: sau bao nhiêu request thì endpoint bị chặn vĩnh viễn?
7. `XX/uml/err-local-cache.puml` → `fix-local-cache.puml`: giải thích lại bằng lời cho người khác nghe.
