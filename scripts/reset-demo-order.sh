#!/usr/bin/env bash
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-agent_buyer_demo}"

MYSQL_PWD="${MYSQL_PASSWORD}" mysql -u"${MYSQL_USER}" -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -D agent_buyer <<'SQL'
UPDATE business_order
SET status = 'PAID',
    cancel_reason = NULL,
    cancelled_at = NULL
WHERE order_id = 'O-1001';

SELECT order_id, status, item_name, amount
FROM business_order
WHERE order_id = 'O-1001';
SQL
