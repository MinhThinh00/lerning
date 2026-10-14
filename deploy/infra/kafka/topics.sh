#!/bin/bash
# Tạo topic (idempotent). Chạy trong container kafka-init / kafka-cluster-init.
# Env: BOOTSTRAP, REPLICATION_FACTOR, PARTITIONS
# Danh sách topic là nguồn sự thật duy nhất — xem docs/05-MESSAGING.md
set -euo pipefail

: "${BOOTSTRAP:?}" "${REPLICATION_FACTOR:?}" "${PARTITIONS:?}"

DAY_MS=86400000

create() {
  local topic="$1" retention_days="$2"
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms=$((retention_days * DAY_MS))
  echo "  ok  $topic"
}

echo "Creating business topics (partitions=$PARTITIONS, rf=$REPLICATION_FACTOR)"
# key của mọi topic = orderId
create order.created        7
create order.confirmed      7
create order.paid           30
create order.cancelled      7
create inventory.reserved   7
create inventory.failed     7
create payment.success      30
create payment.failed       30

echo "Creating dead-letter topics"
# Mỗi topic có consumer đều có DLT tương ứng (DeadLetterPublishingRecoverer → <topic>.DLT)
for t in order.created order.confirmed order.paid order.cancelled \
         inventory.reserved inventory.failed payment.success payment.failed; do
  create "$t.DLT" 30
done

echo "Done:"
kafka-topics --bootstrap-server "$BOOTSTRAP" --list
