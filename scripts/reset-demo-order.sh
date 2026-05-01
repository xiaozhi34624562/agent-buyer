#!/usr/bin/env bash
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-agent_buyer_demo}"
DEMO_ORDER_CREATED_AT="${DEMO_ORDER_CREATED_AT:-$(TZ=Asia/Shanghai date -v-1d '+%Y-%m-%d 12:00:00.000' 2>/dev/null || TZ=Asia/Shanghai date -d 'yesterday' '+%Y-%m-%d 12:00:00.000')}"

MYSQL_PWD="${MYSQL_PASSWORD}" mysql -u"${MYSQL_USER}" -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -D agent_buyer <<SQL
UPDATE business_order
SET status = 'PAID',
    created_at = '${DEMO_ORDER_CREATED_AT}',
    cancel_reason = NULL,
    cancelled_at = NULL
WHERE order_id = 'O-1001';

SELECT order_id, status, item_name, amount
FROM business_order
WHERE order_id = 'O-1001';
SQL
