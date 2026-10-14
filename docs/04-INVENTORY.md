# 04 — inventory-service: Tồn kho & 5 chiến lược chống oversell

Đây là service trọng tâm của dự án. Mục tiêu là so sánh **5 cơ chế đồng bộ** trên cùng một use case (giữ hàng cho đơn) và cùng một bộ test/benchmark.

## 1. Sửa lại một hiểu lầm của plan v1

Plan v1 ghi "L1/L2/L3 chỉ đủ cho single node, multi-node bắt buộc L4". Nhận định này **sai**. L1–L3 dựa vào lock/điều kiện của **MySQL**, mà mọi replica dùng chung một DB, nên cả ba vẫn đúng khi chạy N replica. Khác biệt giữa các chiến lược nằm ở **throughput dưới tranh chấp cao**, **tải đè lên DB**, và **cái gì bảo vệ cái gì**:

| | Cơ chế | Ai đảm bảo đúng | Đúng khi N replica | Điểm yếu |
|---|---|---|---|---|
| **L1** | `UPDATE … WHERE available >= qty` | Row lock ngầm của MySQL | ✅ | Mọi request đều chạm DB |
| **L2** | `SELECT … FOR UPDATE` | Row lock tường minh | ✅ | Giữ lock suốt transaction, chiếm connection lúc chờ |
| **L3** | `@Version` + retry | Compare-and-swap trên version | ✅ | Tranh chấp cao → retry bùng nổ |
| **L4** | Redisson lock quanh read-modify-write | Lock Redis **+ `@Version` làm fencing** | ✅ (khi mọi writer đi qua lock) | 2 round-trip Redis, serialize theo sản phẩm, lock có thể mất khi Redis failover |
| **L5** | Redis Lua counter làm cổng + L1 | DB (L1); Redis chỉ chặn sớm | ✅ | Redis và DB có thể lệch nhau (chỉ ảnh hưởng hiệu năng, không gây oversell) |

Distributed lock (L4) thực sự cần khi vùng cần bảo vệ **không nằm gọn trong 1 transaction DB**, ví dụ gọi hệ thống ngoài giữa chừng. Trong dự án này, L4 có mặt để học cơ chế lock, không phải vì bài toán bắt buộc phải dùng.

Chiến lược mặc định cho luồng Kafka: **L5** (`INVENTORY_RESERVE_DEFAULT_STRATEGY`).

## 2. Data model

```sql
CREATE TABLE inventory (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id  BIGINT      NOT NULL UNIQUE,
  sku         VARCHAR(50),
  available   INT         NOT NULL DEFAULT 0,
  reserved    INT         NOT NULL DEFAULT 0,
  sold        INT         NOT NULL DEFAULT 0,
  version     BIGINT      NOT NULL DEFAULT 0,
  active      BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  CONSTRAINT chk_inventory_non_negative CHECK (available >= 0 AND reserved >= 0)  -- lưới an toàn cuối
);

-- 1 dòng / (đơn, sản phẩm). Nguồn sự thật cho idempotency, release, commit.
CREATE TABLE inventory_reservation (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    VARCHAR(36) NOT NULL,
  product_id  BIGINT      NOT NULL,
  quantity    INT         NOT NULL,
  status      VARCHAR(16) NOT NULL,      -- RESERVED | COMMITTED | RELEASED | FAILED
  strategy    VARCHAR(4),
  reason      VARCHAR(64),               -- OUT_OF_STOCK, LOCK_TIMEOUT, ...
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  UNIQUE KEY uk_reservation_order_product (order_id, product_id)
);
-- + outbox_event, processed_event, shedlock (từ shop-common)
```

Bất biến kiểm tra được bất kỳ lúc nào (dùng trong test và benchmark):

```
available + reserved + sold = tổng nhập kho
reserved = SUM(quantity) của reservation status RESERVED
```

Bảng `inventory_transaction` của v1 được thay bằng `inventory_reservation` (có trạng thái), nên không cần bảng log riêng.

## 3. Use case: `ReservationService.reserve`

Luồng chung cho mọi strategy:

```
reserve(orderId, items, strategy)
 1. Idempotency: đã có reservation cho orderId?  → trả lại kết quả cũ, KHÔNG làm gì thêm
 2. Sắp xếp items theo productId                   → chống deadlock khi nhiều sản phẩm
 3. strategy.reserve(...)  — all-or-nothing
      thành công: trong CÙNG transaction
         - trừ available, cộng reserved
         - insert reservation (RESERVED)
         - insert outbox inventory.reserved
      thất bại:  transaction mới
         - insert reservation (FAILED, reason)
         - insert outbox inventory.failed
 4. Ghi metric inventory.reserve{strategy, outcome}
```

Hai consumer cùng nhận một `order.created` (redelivery) có thể vượt qua bước 1 cùng lúc. Khi đó **unique key `(order_id, product_id)`** làm transaction thứ hai fail (`DataIntegrityViolationException`). Code bắt exception này, đọc lại reservation, và trả kết quả cũ.

```java
public interface StockReservationStrategy {
    Strategy id();                                         // L1..L5
    ReserveOutcome reserve(String orderId, List<Item> sortedItems);
}

public record Item(long productId, int quantity) {}

public sealed interface ReserveOutcome {
    record Reserved() implements ReserveOutcome {}
    record Rejected(Reason reason, long productId) implements ReserveOutcome {}
    enum Reason { OUT_OF_STOCK, LOCK_TIMEOUT, CONFLICT, NOT_FOUND }
}
```

`ReservationRecorder` (dùng chung) ghi reservation + outbox. Strategy gọi recorder **bên trong** transaction của nó.

## 4. Các chiến lược

### L1 — Conditional UPDATE

```java
// InventoryJpaRepository
@Modifying
@Query("""
    update Inventory i
       set i.available = i.available - :qty,
           i.reserved  = i.reserved + :qty,
           i.version   = i.version + 1,
           i.updatedAt = CURRENT_TIMESTAMP
     where i.productId = :productId and i.active = true and i.available >= :qty
    """)
int decreaseIfAvailable(long productId, int qty);
```

```java
public ReserveOutcome reserve(String orderId, List<Item> items) {
    return tx.execute(status -> {
        for (Item it : items) {
            if (repo.decreaseIfAvailable(it.productId(), it.quantity()) == 0) {
                status.setRollbackOnly();                       // hoàn tác các item trước
                return new Rejected(OUT_OF_STOCK, it.productId());
            }
        }
        recorder.reserved(orderId, items, Strategy.L1);
        return new Reserved();
    });
}
```

L1 tăng `version` để không phá L3 khi hai chiến lược chạy xen kẽ.

### L2 — Pessimistic lock

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from Inventory i where i.productId in :ids and i.active = true order by i.productId")
List<Inventory> lockAll(Collection<Long> ids);
```

```java
public ReserveOutcome reserve(String orderId, List<Item> items) {
    try {
        return tx.execute(status -> {
            Map<Long, Inventory> locked = byProductId(repo.lockAll(ids(items)));   // FOR UPDATE, theo thứ tự id
            for (Item it : items) {
                Inventory inv = locked.get(it.productId());
                if (inv == null || !inv.canReserve(it.quantity())) {
                    status.setRollbackOnly();
                    return new Rejected(inv == null ? NOT_FOUND : OUT_OF_STOCK, it.productId());
                }
            }
            items.forEach(it -> locked.get(it.productId()).reserve(it.quantity()));  // domain method
            recorder.reserved(orderId, items, Strategy.L2);
            return new Reserved();
        });
    } catch (PessimisticLockingFailureException e) {          // hết innodb_lock_wait_timeout
        return new Rejected(LOCK_TIMEOUT, items.get(0).productId());
    }
}
```

Timeout chờ lock: MySQL **bỏ qua** hint `jakarta.persistence.lock.timeout`. Dùng `innodb_lock_wait_timeout` ở mức session. Biến này đã đặt qua JDBC URL trong compose (`sessionVariables=innodb_lock_wait_timeout=3`).

### L3 — Optimistic lock + retry

Retry phải bọc **bên ngoài** transaction, vì exception xảy ra lúc commit. Dùng vòng lặp tường minh thay cho `@Retryable` + `@Transactional` trên cùng một method (thứ tự proxy giữa hai annotation này không đảm bảo).

```java
public ReserveOutcome reserve(String orderId, List<Item> items) {
    for (int attempt = 1; ; attempt++) {
        try {
            return tx.execute(status -> {
                Map<Long, Inventory> inv = byProductId(repo.findAllByProductIdIn(ids(items)));  // không lock
                for (Item it : items) {
                    if (!inv.get(it.productId()).canReserve(it.quantity())) {
                        status.setRollbackOnly();
                        return new Rejected(OUT_OF_STOCK, it.productId());
                    }
                }
                items.forEach(it -> inv.get(it.productId()).reserve(it.quantity()));
                recorder.reserved(orderId, items, Strategy.L3);
                return new Reserved();                  // flush: UPDATE ... WHERE id=? AND version=?
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            if (attempt >= props.l3MaxAttempts()) return new Rejected(CONFLICT, items.get(0).productId());
            sleepWithJitter(attempt);                   // 20ms * 2^attempt + random
        }
    }
}
```

### L4 — Redisson distributed lock

```java
public ReserveOutcome reserve(String orderId, List<Item> items) {
    RLock[] locks = items.stream()
        .map(it -> redisson.getLock("inv:lock:" + it.productId()))   // đã sort theo productId
        .toArray(RLock[]::new);
    RLock lock = redisson.getMultiLock(locks);
    boolean acquired = false;
    try {
        // leaseTime = -1 → watchdog tự gia hạn khi transaction chạy lâu
        acquired = lock.tryLock(props.l4WaitMs(), -1, TimeUnit.MILLISECONDS);
        if (!acquired) return new Rejected(LOCK_TIMEOUT, items.get(0).productId());

        // tx.execute trả về SAU khi commit → unlock luôn xảy ra sau commit
        return tx.execute(status -> {
            Map<Long, Inventory> inv = byProductId(repo.findAllByProductIdIn(ids(items)));
            ...                                           // check + reserve như L3
            recorder.reserved(orderId, items, Strategy.L4);
            return new Reserved();
        });
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Rejected(LOCK_TIMEOUT, items.get(0).productId());
    } catch (ObjectOptimisticLockingFailureException e) {
        // Lock bị mất (Redis failover / GC pause) và một writer khác đã ghi → @Version chặn lại
        return new Rejected(CONFLICT, items.get(0).productId());
    } finally {
        if (acquired) lock.unlock();
    }
}
```

Các điểm đã sửa so với v1:
- **Unlock sau commit.** Unlock trước commit thì replica khác có thể đọc phải dữ liệu cũ.
- **Watchdog thay cho lease cố định 5s.** Với lease cố định, transaction chạy lâu hơn 5s sẽ mất lock giữa chừng.
- **`@Version` đóng vai fencing token.** Redis Sentinel replicate bất đồng bộ, nên lock có thể mất khi failover; `@Version` chặn ghi đè trong trường hợp đó.
- **MultiLock theo thứ tự** cho đơn nhiều sản phẩm.

### L5 — Redis Lua counter + L1

Redis giữ bộ đếm `inv:stock:{productId}` để **từ chối sớm** khi hết hàng mà không chạm DB. DB (L1) vẫn là nơi quyết định cuối cùng.

```lua
-- resources/lua/deduct.lua   KEYS[i] = inv:stock:<productId>, ARGV[i] = quantity
-- return 1 = OK, -1 = key chưa nạp, -2 = không đủ hàng  (không bao giờ trả 0 → không nhập nhằng như v1)
for i = 1, #KEYS do
  local v = redis.call('GET', KEYS[i])
  if not v then return -1 end
  if tonumber(v) < tonumber(ARGV[i]) then return -2 end
end
for i = 1, #KEYS do
  redis.call('DECRBY', KEYS[i], ARGV[i])
end
return 1
```

```java
@Component
class RedisStockCounter implements StockCounter {

    private static final RedisScript<Long> DEDUCT =
        RedisScript.of(new ClassPathResource("lua/deduct.lua"), Long.class);   // tạo 1 lần, EVALSHA

    private final StringRedisTemplate redis;   // String serializer → ARGV là "5", không phải "\"5\""

    public DeductResult tryDeduct(List<Item> items) {
        List<String> keys = items.stream().map(it -> key(it.productId())).toList();
        Object[] qty = items.stream().map(it -> String.valueOf(it.quantity())).toArray();
        long r = redis.execute(DEDUCT, keys, qty);
        return r == 1 ? DeductResult.OK : r == -2 ? DeductResult.INSUFFICIENT : DeductResult.NOT_LOADED;
    }

    public void restore(List<Item> items) {           // bù lại khi bước DB thất bại
        items.forEach(it -> redis.opsForValue().increment(key(it.productId()), it.quantity()));
    }
}
```

```java
public ReserveOutcome reserve(String orderId, List<Item> items) {
    DeductResult gate = counter.tryDeduct(items);
    if (gate == NOT_LOADED) { warmup.load(ids(items)); gate = counter.tryDeduct(items); }
    if (gate == INSUFFICIENT) return new Rejected(OUT_OF_STOCK, ...);      // không chạm DB
    if (gate == NOT_LOADED)  return l1.reserve(orderId, items);             // Redis có vấn đề → đi thẳng DB

    try {
        ReserveOutcome outcome = l1.reserveAs(orderId, items, Strategy.L5);
        if (!(outcome instanceof Reserved)) counter.restore(items);         // DB nói không → trả lại Redis
        return outcome;
    } catch (RuntimeException e) {
        counter.restore(items);
        throw e;
    }
}
```

Cluster mode: script dùng nhiều key, trên Redis Cluster các key phải nằm cùng slot (hash tag, ví dụ `inv:stock:{p}:<id>`). Sentinel và standalone không có ràng buộc này.

## 5. Đồng bộ Redis ↔ DB

DB là nguồn sự thật. Redis lệch thì chỉ ảnh hưởng hiệu năng, không gây oversell:
- Redis **cao hơn** DB: một vài request lọt qua cổng rồi bị L1 từ chối.
- Redis **thấp hơn** DB: một vài request bị từ chối oan. Đây là chiều cần sửa.

| Thời điểm | Hành động |
|---|---|
| Khởi động (`ApplicationReadyEvent`) | `SET inv:stock:<id> <available> NX` cho mọi sản phẩm active. **NX**: không đè counter mà replica khác đang dùng |
| Key thiếu khi reserve | Nạp lại sản phẩm đó với `SET NX` |
| Release / adjust / commit | Sau commit (`TransactionSynchronization.afterCommit`): `INCRBY`/`DECRBY` tương ứng |
| Reconcile job (ShedLock, 5 phút) | So `available` của DB với Redis. Lệch ở 2 lần kiểm tra liên tiếp thì `SET` lại theo DB. Ghi metric `inventory_stock_drift` |

## 6. Release & commit (consumer)

| Event | Xử lý (trong 1 transaction, có Inbox) |
|---|---|
| `order.paid` | Reservation `RESERVED` → `COMMITTED`; `reserved -= q`, `sold += q` |
| `order.cancelled` | Reservation `RESERVED` → `RELEASED`; `available += q`, `reserved -= q`; afterCommit `INCRBY` Redis |
| `order.cancelled`, reservation `FAILED` | Không làm gì (chưa từng giữ hàng) |
| `order.cancelled` đến **trước** `order.created` | Chưa có reservation → insert reservation status `RELEASED` (tombstone) cho từng item. Khi `order.created` đến sau, bước idempotency thấy đã có reservation và bỏ qua |

Release chỉ dựa trên reservation của **đúng `orderId`**. Bug v1 (release dựa vào `reserved >= qty` nên có thể nhả hàng của đơn khác) không thể xảy ra nữa.

## 7. REST API

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/inventory/{productId}` | `inventory:read` | available/reserved/sold (đọc DB, không cache local) |
| POST | `/api/inventory/{productId}/adjustments` | `inventory:write` | `{delta, reason}` nhập/xuất kho |
| POST | `/api/inventory/reserve?strategy=L1..L5` | ADMIN / SERVICE | `{orderId, items[]}` cho benchmark và test |
| POST | `/api/admin/inventory/{productId}/reset` | ADMIN, chỉ profile `dev`/`bench` | Đặt lại tồn kho cho benchmark (DB + Redis, xóa reservation `bench-*`) |

Bỏ cache 3 tầng (Caffeine → Redis → DB) cho tồn kho của v1. Mỗi lần reserve đều phải invalidate nên cache không bao giờ hit với sản phẩm hot, còn cache local thì lệch giữa các replica. Nếu cần, chỉ cache **metadata sản phẩm**.

## 8. Metrics

```java
Timer.builder("inventory.reserve")
    .tag("strategy", strategy.name())          // L1..L5
    .tag("outcome", outcome.tag())             // success | out_of_stock | lock_timeout | conflict | error
    .publishPercentileHistogram()
    .register(registry)
    .record(duration);
```

Prometheus: `inventory_reserve_seconds_count`, `inventory_reserve_seconds_bucket`. **Không** gắn tag `product_id`, vì cardinality sẽ không giới hạn. Mọi metric cùng tên phải có **cùng bộ tag key** (v1 vi phạm điều này, Micrometer sẽ ném lỗi).

## 9. Benchmark

Thay script `curl &` ×200 của v1. Script đó chủ yếu đo thời gian fork process. Dùng **k6**:

```javascript
// bench/reserve.js — k6 run -e STRATEGY=L5 -e TOKEN=... bench/reserve.js
import http from 'k6/http';
import { check } from 'k6';

export const options = { scenarios: { hot: { executor: 'constant-arrival-rate',
  rate: 500, timeUnit: '1s', duration: '30s', preAllocatedVUs: 200 } } };

export default function () {
  const body = JSON.stringify({ orderId: `bench-${__VU}-${__ITER}`, items: [{ productId: 1, quantity: 1 }] });
  const res = http.post(`${__ENV.BASE}/api/inventory/reserve?strategy=${__ENV.STRATEGY}`, body,
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.TOKEN}` } });
  check(res, { 'handled': r => r.status === 200 || r.status === 409 });
}
```

Quy trình cho mỗi strategy:

1. `reset` sản phẩm 1 về `available = 1000`.
2. Chạy k6 (tổng request > tồn kho để có tranh chấp và hết hàng).
3. Kiểm tra **đúng**: số `success` = 1000, `available = 0`, bất biến ở mục 2 thỏa, không request nào lỗi 5xx.
4. Ghi lại throughput, p95/p99 (Grafana), số connection Hikari đang chờ, CPU MySQL.
5. Lặp lại với `--scale inventory-service=3`.

Kết quả dự kiến (định tính, cần đo thật):
- L5 từ chối nhanh nhất khi hết hàng, vì không chạm DB.
- L2 chiếm connection lâu nhất, nên p99 tăng mạnh khi tranh chấp cao.
- L3 phát sinh nhiều `conflict` trên sản phẩm hot.
- L4 bị giới hạn bởi việc serialize theo sản phẩm (2 round-trip Redis cho mỗi request).

Đừng chạy xen kẽ các strategy trong cùng một lượt đo. Mỗi lượt chỉ dùng một strategy.
