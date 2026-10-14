# Shop Microservice System

Dự án học microservice: JHipster 8 + DDD, saga qua Kafka (outbox/inbox), 5 chiến lược chống oversell tồn kho, Keycloak, Redis, ELK + Prometheus/Grafana + OpenTelemetry. Chạy local trước, sau đó lên 1 server; cấu hình viết sẵn theo hướng sang Kubernetes.

**Trạng thái:** plan v2 + hạ tầng Docker Compose, chưa có code service.

## Bắt đầu nhanh

```bash
# 1. file hosts: 127.0.0.1 keycloak
cd deploy
cp .env.example .env
docker compose up -d          # mysql, consul, keycloak, redis, kafka (+ tạo topic)
docker compose ps
```

## Tài liệu

Đọc theo thứ tự: [docs/00-OVERVIEW.md](docs/00-OVERVIEW.md) → [02-DEPLOYMENT](docs/02-DEPLOYMENT.md) → [09-IMPLEMENTATION-PLAN](docs/09-IMPLEMENTATION-PLAN.md).

| Thư mục | Nội dung |
|---|---|
| `docs/` | Thiết kế & kế hoạch |
| `deploy/` | Docker Compose (infra, observability, apps), config, realm Keycloak |