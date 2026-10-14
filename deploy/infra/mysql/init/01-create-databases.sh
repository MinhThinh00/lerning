#!/bin/bash
# Tạo database + user riêng cho từng service (database-per-service thực sự:
# user của service A không đọc được DB của service B).
# Chạy 1 lần bởi docker-entrypoint khi volume MySQL còn rỗng.
set -euo pipefail

create_db() {
  local db="$1" user="$2" pass="$3"
  echo "Creating database ${db} / user ${user}"
  mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-SQL
    CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${pass}';
    GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${user}'@'%';
SQL
}

create_db db_user         user_svc         "${DB_PASSWORD_USER}"
create_db db_product      product_svc      "${DB_PASSWORD_PRODUCT}"
create_db db_inventory    inventory_svc    "${DB_PASSWORD_INVENTORY}"
create_db db_order        order_svc        "${DB_PASSWORD_ORDER}"
create_db db_payment      payment_svc      "${DB_PASSWORD_PAYMENT}"
create_db db_notification notification_svc "${DB_PASSWORD_NOTIFICATION}"
create_db db_keycloak     keycloak         "${DB_PASSWORD_KEYCLOAK}"

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
echo "All databases created."
