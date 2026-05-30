#!/usr/bin/env bash
# smoke-test.sh — End-to-end smoke tests for SmartRetail prototype
# Usage: ./scripts/smoke-test.sh [flow1|flow2|flow3|flow4|flow8|flow9|all]

set -euo pipefail

ENV="${SMARTRETAIL_ENV:-dev}"
REGION="${AWS_REGION:-us-east-1}"

# Resolve the Python 3 binary name — python3 on macOS/Linux, python on Windows
PYTHON_CMD="${PYTHON_CMD:-$(command -v python3 2>/dev/null || command -v python 2>/dev/null || echo python3)}"

# ── Mode-specific configuration ───────────────────────────────────────────────
if [ "$ENV" = "local" ]; then
  API_ENDPOINT="http://localhost:8080"   # SIS
  ARS_ENDPOINT="http://localhost:8083"   # ARS (dashboards)
  RE_ENDPOINT="http://localhost:8082"    # RE  (replenishment)
  DB_HOST="localhost"
  DB_USER="smartretail_admin"
  DB_PASS="local_dev_password"
  DB_NAME="smartretail"
  LOCALSTACK_ENDPOINT="http://localhost:4566"
  # Export fake credentials so aws CLI can reach LocalStack without explicit env prefixes
  export AWS_ACCESS_KEY_ID=test
  export AWS_SECRET_ACCESS_KEY=test
  export AWS_DEFAULT_REGION=us-east-1
  AWS_CMD="aws --endpoint-url=${LOCALSTACK_ENDPOINT} --region us-east-1"
  # Use docker exec since psql may not be installed on the host
  PSQL="docker exec smartretail-postgres psql -U ${DB_USER} -d ${DB_NAME}"
else
  # Read endpoints from SSM Parameter Store (AWS mode)
  API_ENDPOINT=$(aws ssm get-parameter --name "/smartretail/${ENV}/api-gateway/endpoint" --query 'Parameter.Value' --output text)
  ARS_ENDPOINT="$API_ENDPOINT"
  RE_ENDPOINT="$API_ENDPOINT"
  RDS_PROXY=$(aws ssm get-parameter --name "/smartretail/${ENV}/rds/proxy-endpoint" --query 'Parameter.Value' --output text)
  AWS_CMD="aws --region ${REGION}"
  PSQL="psql postgresql://smartretail_admin@${RDS_PROXY}/smartretail"
fi

# ── Auth helper ───────────────────────────────────────────────────────────────
# In local mode services accept X-Dev-Role for auth bypass (no Cognito needed).
# In AWS mode a real Cognito JWT is fetched via get-cognito-token.py + SSM.
cognito_token() {
  local username=$1
  local pool_id client_id
  pool_id=$(aws ssm get-parameter --name "/smartretail/${ENV}/cognito/internal-pool-id"  --query 'Parameter.Value' --output text)
  client_id=$(aws ssm get-parameter --name "/smartretail/${ENV}/cognito/internal-client-id" --query 'Parameter.Value' --output text)
  ${PYTHON_CMD} scripts/get-cognito-token.py \
    --username "$username" --password "Test@12345!" \
    --pool-id "$pool_id" --client-id "$client_id"
}

echo "SmartRetail Prototype Smoke Tests"
echo "ENV: ${ENV}"
echo "API: ${API_ENDPOINT}"
echo ""

PASS=0
FAIL=0

check() {
  local test_name=$1
  local result=$2
  local expected=$3

  if [ "$result" = "$expected" ]; then
    echo "[PASS] $test_name"
    PASS=$((PASS + 1))
  else
    echo "[FAIL] $test_name"
    echo "  Expected: $expected"
    echo "  Got:   $result"
    FAIL=$((FAIL + 1))
  fi
}

# ─────────────────────────────────────────
# FLOW 1: POS Event → SIS → IMS → Alert
# ─────────────────────────────────────────
flow1() {
  echo "--- Flow 1: POS Event Ingestion ---"

  # Generate unique transaction ID
  TXID=$(${PYTHON_CMD} -c "import uuid; print(uuid.uuid4())")
  SKU="SKU-BEV-001"
  DC="DC-LONDON"
  QTY=30 # Will push ATP below reorder_point of 100 (on_hand=120, after=90)

  # Reset test position to known state so this test is repeatable
  ${PSQL} -c "
    UPDATE inventory.inventory_positions
       SET on_hand = 120, version = 0
     WHERE sku_id = '$SKU' AND dc_id = '$DC';
    DELETE FROM inventory.stock_alerts sa
      USING inventory.inventory_positions ip
      WHERE sa.position_id = ip.position_id
        AND ip.sku_id = '$SKU' AND ip.dc_id = '$DC';" > /dev/null 2>&1

  # 1. Publish POS event via Firehose (both local and AWS modes).
  #    LocalStack Firehose delivers to SIS FirehoseBatchFilter at http://host.docker.internal:8080
  echo "Publishing POS event: txId=$TXID, sku=$SKU, dc=$DC, qty=$QTY"
  ${PYTHON_CMD} scripts/publish-pos-event.py \
    --transaction-id "$TXID" \
    --sku-id "$SKU" \
    --dc-id "$DC" \
    --store-id "STORE-001" \
    --quantity "$QTY" \
    --unit-price "8.50" \
    --channel "POS" \
    --env "$ENV"

  echo "Waiting 15s for processing..."
  sleep 15

  # 2. Check sales_events row in RDS
  SALES_COUNT=$(${PSQL} -t -c "SELECT COUNT(*) FROM sales.sales_events WHERE transaction_id = '$TXID'::uuid" | tr -d ' ')
  check "1.5 RDS sales_events row created" "$SALES_COUNT" "1"

  # 3. Check RDS idempotency key (sales.idempotency_keys — RDS, same transaction as sales_events)
  SHA=$(${PYTHON_CMD} -c "import hashlib; print(hashlib.sha256('$TXID'.encode()).hexdigest())")
  IDEM_COUNT=$(${PSQL} -t -c "SELECT COUNT(*) FROM sales.idempotency_keys WHERE event_id = '$SHA'" | tr -d ' ')
  check "1.3 RDS idempotency key written" "$IDEM_COUNT" "1"

  # 4. Check inventory_positions updated (reset to 120, minus qty=30 = 90)
  ON_HAND=$(${PSQL} -t -c "SELECT on_hand FROM inventory.inventory_positions WHERE sku_id = '$SKU' AND dc_id = '$DC'" | tr -d ' ')
  check "1.8 IMS inventory_positions updated" "$ON_HAND" "$((120 - QTY))"

  # 5. Check stock_alert created (ATP=90 < reorder_point=100); alerts reset above so exactly 1 expected
  ALERT_COUNT=$(${PSQL} -t -c "
    SELECT COUNT(*) FROM inventory.stock_alerts sa
    JOIN inventory.inventory_positions ip ON ip.position_id = sa.position_id
    WHERE ip.sku_id = '$SKU' AND ip.dc_id = '$DC' AND sa.status = 'ACTIVE'" | tr -d ' ')
  check "1.9 Stock alert created" "$ALERT_COUNT" "1"

  # 6. Duplicate test
  echo "Testing duplicate rejection (direct POST path — Firehose always returns 200 for duplicates)..."
  HTTP_STATUS=$(${PYTHON_CMD} scripts/publish-pos-event.py \
    --transaction-id "$TXID" \
    --sku-id "$SKU" --dc-id "$DC" \
    --store-id "STORE-001" \
    --quantity "$QTY" --unit-price "8.50" --channel "POS" \
    --direct-api "$API_ENDPOINT" \
    --return-status)
  check "1.6 Duplicate event rejected with 409 (direct POST path)" "$HTTP_STATUS" "409"
}

# ─────────────────────────────────────────
# FLOW 2: Inventory Alert → RE → PO Generation
# ─────────────────────────────────────────
flow2() {
  echo "--- Flow 2: RE PO Generation ---"

  echo "Waiting 10s for RE to process alert from Flow 1..."
  sleep 10

  # Check auto-approve PO created for SKU-BEV-001 / DC-LONDON
  # (auto_approve_threshold = 50000, expected totalValue ~= 850 → auto-approved)
  AUTO_PO=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE sku_id = 'SKU-BEV-001' AND dc_id = 'DC-LONDON'
    AND workflow_status = 'APPROVED'
    ORDER BY created_at DESC
    LIMIT 1" | tr -d ' ')
  check "2a.4 Auto-approve PO created (APPROVED)" "$AUTO_PO" "APPROVED"

  # Inject alert for SKU-BEV-003 / DC-LONDON directly into RE FIFO queue
  # (auto_approve_threshold = 0 → always PENDING_APPROVAL; ATP already below reorder_point)
  echo "Injecting InventoryAlertEvent for SKU-BEV-003 / DC-LONDON..."
  ${PYTHON_CMD} scripts/publish-pos-event.py \
    --flow2-direct --sku-id SKU-BEV-003 --dc-id DC-LONDON --env "$ENV"
  sleep 5

  # Check manual approval PO for SKU-BEV-003 at DC-LONDON
  # (auto_approve_threshold = 0 → always PENDING_APPROVAL)
  MANUAL_PO=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE sku_id = 'SKU-BEV-003' AND dc_id = 'DC-LONDON'
    AND workflow_status = 'PENDING_APPROVAL'
    ORDER BY created_at DESC LIMIT 1" | tr -d ' ')
  check "2b.1 Manual PO created (PENDING_APPROVAL)" "$MANUAL_PO" "PENDING_APPROVAL"

  # Store PO ID for Flow 3
  PENDING_PO_ID=$(${PSQL} -t -c "
    SELECT po_id FROM replenishment.purchase_orders
    WHERE sku_id = 'SKU-BEV-003' AND dc_id = 'DC-LONDON'
    AND workflow_status = 'PENDING_APPROVAL'
    ORDER BY created_at DESC LIMIT 1" | tr -d ' ')
  echo "PENDING_PO_ID=$PENDING_PO_ID" > /tmp/smartretail-flow2-state
  echo " Stored PENDING_PO_ID: $PENDING_PO_ID"
}

# ─────────────────────────────────────────
# FLOW 3: SC Planner Approval via REST API
# ─────────────────────────────────────────
flow3() {
  echo "--- Flow 3: SC Planner Approval ---"

  source /tmp/smartretail-flow2-state 2>/dev/null || {
    echo "[FAIL] Flow 2 state not found. Run flow2 first."
    FAIL=$((FAIL + 1))
    return
  }

  # Ensure the stored PO is still PENDING_APPROVAL; if already consumed, inject a fresh one
  PO_STATUS=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE po_id = '${PENDING_PO_ID}'::uuid" | tr -d ' ')

  if [ "$PO_STATUS" != "PENDING_APPROVAL" ]; then
    echo "PO ${PENDING_PO_ID} is ${PO_STATUS} — injecting a fresh PENDING_APPROVAL PO..."
    ${PYTHON_CMD} scripts/publish-pos-event.py --flow2-direct --sku-id SKU-BEV-003 --dc-id DC-LONDON --env "$ENV"
    sleep 5
    PENDING_PO_ID=$(${PSQL} -t -c "
      SELECT po_id FROM replenishment.purchase_orders
      WHERE sku_id = 'SKU-BEV-003' AND dc_id = 'DC-LONDON'
      AND workflow_status = 'PENDING_APPROVAL'
      ORDER BY created_at DESC LIMIT 1" | tr -d ' ')
    echo "New PENDING_PO_ID: ${PENDING_PO_ID}"
  fi

  # Read version for optimistic locking
  PO_VERSION=$(${PSQL} -t -c "
    SELECT version FROM replenishment.purchase_orders
    WHERE po_id = '${PENDING_PO_ID}'::uuid" | tr -d ' ')

  IDEMPOTENCY_KEY=$(${PYTHON_CMD} -c "import uuid; print(uuid.uuid4())")

  # Approve the PO
  if [ "$ENV" = "local" ]; then
    APPROVE_STATUS=$(curl -s -o /tmp/approve-response.json -w "%{http_code}" \
      -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
      -H "X-Dev-Role: SC_PLANNER" \
      -H "X-Idempotency-Key: ${IDEMPOTENCY_KEY}" \
      -H "Content-Type: application/json" \
      -d "{\"version\":${PO_VERSION},\"notes\":\"Smoke test approval\"}")
  else
    SC_PLANNER_TOKEN=$(cognito_token "sc-planner-1")
    APPROVE_STATUS=$(curl -s -o /tmp/approve-response.json -w "%{http_code}" \
      -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
      -H "Authorization: Bearer ${SC_PLANNER_TOKEN}" \
      -H "X-Idempotency-Key: ${IDEMPOTENCY_KEY}" \
      -H "Content-Type: application/json" \
      -d "{\"version\":${PO_VERSION},\"notes\":\"Smoke test approval\"}")
  fi

  check "3a.1 Approve returns 200" "$APPROVE_STATUS" "200"

  # Check RDS updated
  APPROVED_STATUS=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE po_id = '${PENDING_PO_ID}'::uuid" | tr -d ' ')
  check "3a.5 RDS workflow_status = APPROVED" "$APPROVED_STATUS" "APPROVED"

  # Test wrong role rejection — need a PENDING_APPROVAL PO for the test
  OTHER_PO=$(${PSQL} -t -c "
    SELECT po_id FROM replenishment.purchase_orders
    WHERE workflow_status = 'PENDING_APPROVAL'
    LIMIT 1" | tr -d ' ')

  if [ -n "$OTHER_PO" ]; then
    OTHER_VERSION=$(${PSQL} -t -c "
      SELECT version FROM replenishment.purchase_orders
      WHERE po_id = '${OTHER_PO}'::uuid" | tr -d ' ')
    if [ "$ENV" = "local" ]; then
      WRONG_ROLE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${OTHER_PO}/approve" \
        -H "X-Dev-Role: STORE_MANAGER" \
        -H "Content-Type: application/json" \
        -H "X-Idempotency-Key: $(${PYTHON_CMD} -c 'import uuid; print(uuid.uuid4())')" \
        -d "{\"version\":${OTHER_VERSION}}")
    else
      SM_TOKEN=$(cognito_token "store-manager-1")
      WRONG_ROLE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${OTHER_PO}/approve" \
        -H "Authorization: Bearer ${SM_TOKEN}" \
        -H "Content-Type: application/json" \
        -H "X-Idempotency-Key: $(${PYTHON_CMD} -c 'import uuid; print(uuid.uuid4())')" \
        -d "{\"version\":${OTHER_VERSION}}")
    fi
    check "3c STORE_MANAGER role rejected with 403" "$WRONG_ROLE_STATUS" "403"
  fi

  # Test wrong status rejection — PENDING_PO_ID is now APPROVED, so this should return 409
  if [ "$ENV" = "local" ]; then
    WRONG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
      -H "X-Dev-Role: SC_PLANNER" \
      -H "Content-Type: application/json" \
      -H "X-Idempotency-Key: $(${PYTHON_CMD} -c 'import uuid; print(uuid.uuid4())')" \
      -d "{\"version\":${PO_VERSION}}")
  else
    WRONG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X POST "${RE_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
      -H "Authorization: Bearer ${SC_PLANNER_TOKEN}" \
      -H "Content-Type: application/json" \
      -H "X-Idempotency-Key: $(${PYTHON_CMD} -c 'import uuid; print(uuid.uuid4())')" \
      -d "{\"version\":${PO_VERSION}}")
  fi
  check "3d Wrong status returns 409" "$WRONG_STATUS" "409"
}

# ─────────────────────────────────────────
# FLOW 4: ARS → Store Manager Dashboard
# ─────────────────────────────────────────
flow4() {
  echo "--- Flow 4: Store Manager Dashboard API ---"

  if [ "$ENV" = "local" ]; then
    HTTP_STATUS=$(curl -s -o /tmp/dashboard-response.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/store-manager?dcId=DC-LONDON" \
      -H "X-Dev-Role: STORE_MANAGER")
  else
    SM_TOKEN=$(cognito_token "store-manager-1")
    HTTP_STATUS=$(curl -s -o /tmp/dashboard-response.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/store-manager?dcId=DC-LONDON" \
      -H "Authorization: Bearer ${SM_TOKEN}")
  fi

  check "4.1 Dashboard API returns 200" "$HTTP_STATUS" "200"

  DATA_FRESHNESS=$(${PYTHON_CMD} -c "
import json
with open('/tmp/dashboard-response.json') as f:
  d = json.load(f)
print('present' if d.get('dataFreshness') else 'missing')")
  check "4.8 dataFreshness present in response" "$DATA_FRESHNESS" "present"

  ALERT_COUNT=$(${PYTHON_CMD} -c "
import json
with open('/tmp/dashboard-response.json') as f:
  d = json.load(f)
print(str(d.get('alertKpi', {}).get('totalActive', 0)))")
  check "4.6 Dashboard shows alert counts > 0" "$([ "$ALERT_COUNT" -gt 0 ] && echo 'yes' || echo 'no')" "yes"
}

# ─────────────────────────────────────────
# FLOW 8: Executive Dashboard
# ─────────────────────────────────────────
flow8() {
  echo "--- Flow 8: Executive Dashboard API ---"

  if [ "$ENV" = "local" ]; then
    HTTP_STATUS=$(curl -s -o /tmp/exec-dashboard.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/executive" \
      -H "X-Dev-Role: EXECUTIVE")
    FORBIDDEN=$(curl -s -o /dev/null -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/sc-planner" \
      -H "X-Dev-Role: EXECUTIVE")
  else
    EXEC_TOKEN=$(cognito_token "executive-1")
    HTTP_STATUS=$(curl -s -o /tmp/exec-dashboard.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/executive" \
      -H "Authorization: Bearer ${EXEC_TOKEN}")
    FORBIDDEN=$(curl -s -o /dev/null -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/sc-planner" \
      -H "Authorization: Bearer ${EXEC_TOKEN}")
  fi

  check "8.1 Executive dashboard returns 200" "$HTTP_STATUS" "200"

  HISTORY_COUNT=$(${PYTHON_CMD} -c "
import json
with open('/tmp/exec-dashboard.json') as f:
  d = json.load(f)
print(len(d.get('kpis', {}).get('forecastAccuracy', {}).get('history', [])))")
  check "8.2 MAPE history has 30 data points" "$HISTORY_COUNT" "30"

  check "8.5 EXECUTIVE cannot access SC Planner (403)" "$FORBIDDEN" "403"
}

# ─────────────────────────────────────────
# FLOW 9: Supplier Performance
# ─────────────────────────────────────────
flow9() {
  echo "--- Flow 9: SC Planner Console ---"

  if [ "$ENV" = "local" ]; then
    HTTP_STATUS=$(curl -s -o /tmp/sc-planner-summary.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/sc-planner" \
      -H "X-Dev-Role: SC_PLANNER")
    PERF_STATUS=$(curl -s -o /tmp/supplier-perf.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/supplier-performance" \
      -H "X-Dev-Role: SC_PLANNER")
  else
    SCP_TOKEN=$(cognito_token "sc-planner-1")
    HTTP_STATUS=$(curl -s -o /tmp/sc-planner-summary.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/sc-planner" \
      -H "Authorization: Bearer ${SCP_TOKEN}")
    PERF_STATUS=$(curl -s -o /tmp/supplier-perf.json -w "%{http_code}" \
      "${ARS_ENDPOINT}/v1/dashboard/supplier-performance" \
      -H "Authorization: Bearer ${SCP_TOKEN}")
  fi

  check "9.1 SC Planner dashboard returns 200" "$HTTP_STATUS" "200"
  check "9.2 Supplier performance returns 200" "$PERF_STATUS" "200"

  SUPPLIER_COUNT=$(${PYTHON_CMD} -c "
import json
with open('/tmp/supplier-perf.json') as f:
  d = json.load(f)
print(len(d.get('suppliers', [])))")
  check "9.3 5 suppliers in response" "$SUPPLIER_COUNT" "5"
}

# ─────────────────────────────────────────
# Main
# ─────────────────────────────────────────
case "${1:-all}" in
  flow1) flow1 ;;
  flow2) flow2 ;;
  flow3) flow3 ;;
  flow4) flow4 ;;
  flow8) flow8 ;;
  flow9) flow9 ;;
  all)
    flow1
    flow2
    flow3
    flow4
    flow8
    flow9
    ;;
  *)
    echo "Usage: $0 [flow1|flow2|flow3|flow4|flow8|flow9|all]"
    exit 1
    ;;
esac

echo ""
echo "─────────────────────────────────"
echo "Results: $PASS passed $FAIL failed"
echo "─────────────────────────────────"

[ $FAIL -eq 0 ] && exit 0 || exit 1

