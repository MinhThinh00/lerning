# 02 — Deployment: Local → 1 Server → Kubernetes

Toàn bộ file triển khai nằm trong `deploy/`. Tài liệu này **không chép lại** nội dung compose. File compose là nguồn sự thật, đọc trực tiếp ở đó.

## 1. Nguyên tắc thiết kế

Cấu hình v1 gắn chặt vào "mọi thứ trên 1 máy": `localhost` ở khắp nơi, `container_name` cố định, publish port cho từng service, Prometheus trỏ cứng `host.docker.internal:8081...`, log đẩy TCP tới `localhost:5044`. Với cấu hình đó, chạy thêm replica hay tách máy là phải sửa từng chỗ.

Bộ cấu hình mới theo 12-factor:

| Vấn đề v1 | Cách làm mới |
|---|---|
| Địa chỉ hardcode `localhost` | Tên DNS của service (`mysql`, `kafka`, `consul`...) qua env var |
| `container_name` cố định, publish port mọi service | Không `container_name`; chỉ publish gateway + keycloak → `--scale` được |
| Prometheus static target | `docker_sd_configs` theo label `prometheus.io/*` (giống annotation K8s) |
| Log TCP appender tới Logstash | JSON ra stdout, Filebeat thu theo container (giống DaemonSet K8s) |
| Elastic APM javaagent | OTel OTLP, endpoint qua env |
| Redis Sentinel + Kafka 3 broker luôn chạy | Mặc định 1 node; cluster là profile lab |
| Password trong compose | `.env` (không commit) → sau là K8s Secret |
| Topic tạo bằng `docker exec` từ host | Job one-shot `kafka-init` (giống K8s Job) |
| Keycloak cấu hình tay bằng kcadm | Realm import từ `realm/shop-realm.json` |
| Mọi port bind `0.0.0.0` | Infra bind `127.0.0.1` (`BIND_ADDR`), chỉ edge public |

## 2. Cấu trúc `deploy/`

```
deploy/
├── compose.yaml                 ← entry: include các file dưới
├── .env.example                 ← copy thành .env
├── infra/
│   ├── mysql/        compose.yaml, init/01-create-databases.sh
│   ├── consul/       compose.yaml
│   ├── keycloak/     compose.yaml, realm/shop-realm.json
│   ├── redis/        compose.yaml        (redis-single | redis-sentinel)
│   └── kafka/        compose.yaml, topics.sh  (kafka-single | kafka-cluster | tools)
├── observability/
│   ├── compose.yaml
│   ├── filebeat/ logstash/ prometheus/ grafana/
└── apps/
    ├── compose.yaml             ← gateway + 6 services
    └── env/redis-single.env, redis-sentinel.env
```

Tất cả file được `include` vào **một project** nên dùng chung network `default`. Không cần `docker network create`.

## 3. Profiles

| Profile | Service | Khi nào dùng |
|---|---|---|
| *(luôn chạy)* | mysql, consul, keycloak | luôn |
| `kafka-single` | kafka, kafka-init | dev, server |
| `kafka-cluster` | kafka-1..3, kafka-cluster-init | lab replication/ISR |
| `redis-single` | redis | dev, server |
| `redis-sentinel` | redis-master, 2 replica, 3 sentinel | lab failover (app phải chạy trong Docker) |
| `observability` | ES, Logstash, Filebeat, Kibana, APM, Prometheus, Grafana, exporters | khi cần quan sát |
| `tools` | kafka-ui | dev |
| `apps` | gateway + 6 services | chạy full trong Docker |

Chọn bằng `COMPOSE_PROFILES` trong `.env`. Khi đổi cặp `redis-*`/`kafka-*`, phải đổi luôn các biến đi kèm (`REDIS_MODE`, `REDIS_EXPORTER_TARGET`, `KAFKA_BOOTSTRAP`, `KAFKA_REPLICATION_FACTOR`). Comment trong `.env.example` có ghi rõ.

## 4. Giai đoạn 1 — Local dev (app chạy từ IDE)

### 4.1 Chuẩn bị một lần

1. Docker Desktop, tối thiểu 6 GB RAM cho VM (8 GB nếu bật `observability`). Docker Compose ≥ 2.20.
2. Thêm vào file hosts (`C:\Windows\System32\drivers\etc\hosts` hoặc `/etc/hosts`):
   ```
   127.0.0.1 keycloak
   ```
   Lý do: issuer của JWT phải giống nhau ở browser, IDE và container (xem [03-SECURITY](./03-SECURITY.md#2-hostname--issuer)).
3. Tạo `.env`:
   ```bash
   cd deploy
   cp .env.example .env
   ```

### 4.2 Chạy infra

```bash
docker compose up -d
docker compose ps
```

`kafka-init` sẽ ở trạng thái `exited (0)`. Đó là bình thường vì nó là job chạy một lần.

### 4.3 App trong IDE kết nối vào đâu

| Thành phần | Địa chỉ từ host |
|---|---|
| MySQL | `localhost:3306` (user/password riêng từng service, xem `.env`) |
| Redis | `localhost:6379` |
| Kafka | `localhost:9092` |
| Consul | `localhost:8500` |
| Keycloak | `http://keycloak:8180` |
| APM (OTLP) | `http://localhost:8200/v1/traces` |

Các giá trị này là default trong `application.yml` (profile `dev`) của mỗi service.

Muốn Prometheus scrape app chạy từ IDE: copy `observability/prometheus/targets/dev-host.yml.example` thành `dev-host.yml`.

### 4.4 Kiểm tra

```bash
# Kafka topics
docker compose exec kafka kafka-topics --bootstrap-server localhost:29092 --list

# Keycloak đã import realm, token có roles/client_roles/aud
curl -s -X POST http://keycloak:8180/realms/shop/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shop-app \
  -d username=user1 -d "password=<SHOP_TEST_USER_PASSWORD trong .env>"

# MySQL: user của service chỉ thấy DB của mình
docker compose exec mysql mysql -uinventory_svc -p -e "SHOW DATABASES;"
```

## 5. Giai đoạn 2 — Full stack trong Docker + scale

```bash
# build image từng service (trong thư mục service)
# build image vào Docker local, tag = APP_TAG trong .env (xem §6.4)
./mvnw -Pprod verify jib:dockerBuild -Djib.to.image=shop/inventory-service:0.1.0

# .env: COMPOSE_PROFILES=kafka-single,redis-single,observability,apps
docker compose up -d

# scale ngang
docker compose up -d --scale inventory-service=3 --scale order-service=2
```

Thêm replica thì những gì tự hoạt động, không phải sửa cấu hình:

| Cơ chế | Hoạt động thế nào |
|---|---|
| Load balancing HTTP | Mỗi replica tự đăng ký Consul, gateway (`lb://`) chia tải |
| Kafka | Replica cùng `group.id` → chia partition (tối đa = số partition, mặc định 3) |
| Scheduled job | ShedLock: chỉ 1 replica chạy mỗi lượt |
| Logs | Filebeat thấy container mới, gắn field `service` + `instance` |
| Metrics | Prometheus docker_sd thấy container mới qua label |
| Traces | Mỗi replica gửi OTLP về `apm-server` |

Bài thực hành nên làm: scale `inventory-service=3`, bắn tải đặt hàng, rồi `docker kill` một replica giữa chừng. Sau đó kiểm tra không có oversell, consumer rebalance, và outbox vẫn publish đủ.

## 6. Giai đoạn 3 — Triển khai lên 1 server

### 6.1 Thay đổi `.env`

```dotenv
BIND_ADDR=127.0.0.1                # infra chỉ truy cập qua SSH tunnel
PUBLIC_BIND_ADDR=127.0.0.1         # gateway/keycloak đứng sau reverse proxy trên cùng máy
KEYCLOAK_URL=https://auth.example.com
KEYCLOAK_COMMAND=start             # production mode
SHOP_APP_REDIRECT_URI=https://shop.example.com
KAFKA_EXTERNAL_HOST=localhost
TRACING_SAMPLING_PROBABILITY=0.1
# + đổi TOÀN BỘ password/secret
```

### 6.2 Reverse proxy + TLS

Đặt Caddy (hoặc Nginx/Traefik) trên server, terminate TLS:

```
shop.example.com  → 127.0.0.1:8080   (gateway)
auth.example.com  → 127.0.0.1:8180   (keycloak)
```

Keycloak đã bật `KC_PROXY_HEADERS=xforwarded`. Container gọi Keycloak qua `KEYCLOAK_URL` (domain public). Nếu server không tự resolve được domain của chính nó (hairpin NAT), thêm `extra_hosts` cho apps trỏ domain về IP nội bộ của proxy.

### 6.3 Vận hành tối thiểu

| Việc | Cách làm |
|---|---|
| Firewall | Chỉ mở 22, 80, 443 |
| Truy cập Kibana/Grafana/Kafka UI | `ssh -L 5601:localhost:5601 -L 3000:localhost:3000 server` |
| Backup | Cron `mysqldump` từng DB + snapshot volume; export realm Keycloak |
| Elasticsearch | Bật `xpack.security.enabled=true` nếu có khả năng lộ port; đặt ILM xóa `shop-logs-*` > 7–14 ngày |
| RAM | Server 8 GB: chạy full trừ `kafka-cluster`/`redis-sentinel`. Tham khảo `deploy.resources.limits` trong compose |
| Cập nhật | `docker compose pull && docker compose up -d` (hoặc build Jib trên CI và push registry) |

### 6.4 Release & rollback

Rollback = chạy lại **image cũ**. Điều kiện tiên quyết là image cũ vẫn còn và gọi được bằng tag, nên:

- **Không dùng tag `latest`.** Mỗi build gắn tag bất biến: git sha ngắn (`a1b2c3d`) hoặc semver (`1.4.0`).
- Image push lên registry (GHCR / Docker Hub). Image chỉ có ở máy build thì không tính là "còn".
- Mỗi service có biến tag riêng trong `.env` (`INVENTORY_SERVICE_TAG`...), mặc định lấy `APP_TAG`, nên deploy và rollback được từng service độc lập.

```bash
# build & push (trong thư mục service)
./mvnw -Pprod verify jib:build -Djib.to.image=ghcr.io/<user>/inventory-service -Djib.to.tags=$(git rev-parse --short HEAD)

# deploy: sửa .env  INVENTORY_SERVICE_TAG=a1b2c3d
docker compose up -d inventory-service

# rollback: đặt lại tag cũ
INVENTORY_SERVICE_TAG=9f8e7d6  →  docker compose up -d inventory-service
```

Ghi lại tag đang chạy mỗi lần deploy (commit `.env.deployed` không chứa secret, hoặc ghi vào changelog) để biết rollback về đâu.

**Rollback image KHÔNG rollback database và event.** Để quay lại bản cũ an toàn:

| Thành phần | Quy tắc |
|---|---|
| Liquibase | Migration theo kiểu **expand → contract**. Release N chỉ *thêm* (cột nullable, bảng mới); xóa/đổi tên ở release N+1 khi chắc chắn không rollback về N-1. Như vậy code cũ vẫn chạy được trên schema mới |
| Kafka event | Chỉ thêm field (consumer bỏ qua field lạ). Đổi nghĩa/xóa field thì tăng `eventVersion` và giữ consumer đọc được cả hai version (05-MESSAGING §3) |
| Outbox đang tồn | Bản cũ phải đọc được event do bản mới ghi, đây là lý do envelope có `eventVersion` |

Lên K8s: `kubectl rollout undo deployment/inventory-service` làm đúng việc trên (quay về ReplicaSet với image tag trước), và các quy tắc về DB/event vẫn áp dụng y nguyên.

### 6.5 Ngân sách tài nguyên (giới hạn trong compose)

| Nhóm | RAM |
|---|---|
| mysql + consul + keycloak | ~1.9 GB |
| kafka-single + redis-single | ~1.3 GB |
| observability | ~3.5 GB |
| 7 apps × 768 MB | ~5.4 GB (giới hạn trên, thực tế ~3 GB) |

Server 8 GB đủ chạy dev/demo. Nếu thiếu, tắt `observability` hoặc giảm limit của ES/Kibana trước.

## 7. Chuyển sang Kubernetes

Bộ compose đã được thiết kế để ánh xạ 1-1. Khi học K8s:

| Compose | Kubernetes |
|---|---|
| service `apps/*` | `Deployment` + `Service` (ClusterIP) |
| `--scale N` | `replicas: N` / `HorizontalPodAutoscaler` |
| `x-app-env` (biến thường) | `ConfigMap` |
| biến password/secret trong `.env` | `Secret` |
| `healthcheck` readiness | `readinessProbe: /management/health/readiness`, `livenessProbe: /management/health/liveness` |
| `deploy.resources.limits` | `resources.limits` / `requests` |
| `stop_grace_period` | `terminationGracePeriodSeconds` |
| label `prometheus.io/*` | annotation `prometheus.io/*` (hoặc `ServiceMonitor`) |
| Filebeat docker autodiscover | Filebeat DaemonSet (kubernetes autodiscover) |
| `kafka-init` | `Job` (Helm hook) |
| Keycloak realm import | ConfigMap mount + `--import-realm`, hoặc Keycloak Operator `KeycloakRealmImport` |
| gateway publish port | `Ingress` |
| Consul | giữ Consul (Helm chart) **hoặc** tắt (`SPRING_CLOUD_CONSUL_ENABLED=false`) và route gateway bằng URI tĩnh `http://inventory-service:8083` |
| mysql / redis / kafka | Operator (MySQL Operator, Strimzi cho Kafka, Bitnami Redis) hoặc managed service |

JHipster có `jhipster kubernetes` sub-generator để sinh manifest. Đây là điểm khởi đầu tốt khi học.

## 8. Port

| Port host | Service | Bind | Profile |
|---|---|---|---|
| 8080 | gateway | `PUBLIC_BIND_ADDR` | apps |
| 8180 | keycloak | `PUBLIC_BIND_ADDR` | — |
| 3306 | mysql | `BIND_ADDR` | — |
| 8500 | consul UI | `BIND_ADDR` | — |
| 6379 | redis | `BIND_ADDR` | redis-single |
| 9092 (9093, 9094) | kafka EXTERNAL | `BIND_ADDR` | kafka-* |
| 9080 | kafka-ui | `BIND_ADDR` | tools |
| 9200 / 5601 / 8200 | ES / Kibana / APM | `BIND_ADDR` | observability |
| 9090 / 3000 | Prometheus / Grafana | `BIND_ADDR` | observability |

Service nội bộ (8081–8086) **không** publish khi chạy trong Docker. Truy cập qua gateway.

## 9. Lỗi thường gặp

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| 401 `invalid issuer` | Token lấy từ `http://localhost:8180` thay vì `http://keycloak:8180`. Kiểm tra file hosts |
| App trên host không nối được Redis sentinel | Đúng như thiết kế: sentinel trả về hostname nội bộ. Dùng `redis-single` khi chạy từ IDE |
| MySQL user không tồn tại | Init script chỉ chạy khi volume rỗng: `docker compose down -v` (mất dữ liệu) |
| `bash\r: not found` khi chạy `.sh` | File bị CRLF. `.gitattributes` đã ép LF; kiểm tra editor |
| Filebeat không thấy log trên Docker Desktop | Kiểm tra mount `/var/lib/docker/containers`; Docker Desktop phải dùng Linux containers (WSL2) |
| `kafka-exporter` restart liên tục | Kafka chưa sẵn sàng hoặc `KAFKA_BOOTSTRAP` không khớp profile |
