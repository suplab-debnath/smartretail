#!/usr/bin/env bash
# smoke-test.sh — End-to-end smoke tests for SmartRetail prototype
# Usage: ./scripts/smoke-test.sh [flow1|flow2|flow3|flow4|flow8|flow9|all]

set -euo pipefail

ENV="${SMARTRETAIL_ENV:-dev}"
REGION="${AWS_REGION:-us-east-1}"

# Ensure python3 / aws CLI installed via pip are on PATH
export PATH="$PATH:/opt/homebrew/opt/python@3.13/bin:/usr/local/bin"

# ── Mode-specific configuration ───────────────────────────────────────────────
if [ "$ENV" = "local" ]; then
  API_ENDPOINT="http://localhost:8080"
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
  RDS_PROXY=$(aws ssm get-parameter --name "/smartretail/${ENV}/rds/proxy-endpoint" --query 'Parameter.Value' --output text)
  AWS_CMD="aws --region ${REGION}"
  PSQL="psql postgresql://smartretail_admin@${RDS_PROXY}/smartretail"
fi

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
    echo "✅$test_name"
    PASS=$((PASS + 1))
  else
    echo "❌$test_name"
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
  TXID=$(python3 -c "import uuid; print(uuid.uuid4())")
  SKU="SKU-BEV-001"
  DC="DC-LONDON"
  QTY=30 # Will push ATP below reorder_point of 100 (on_hand=120, after=90)

  # 1. Publish POS event — direct to SIS in local mode (Lambda consumer not running locally),
  #    via Kinesis in AWS mode.
  echo "Publishing POS event: txId=$TXID, sku=$SKU, dc=$DC, qty=$QTY"
  if [ "$ENV" = "local" ]; then
    python3 scripts/publish-pos-event.py \
      --transaction-id "$TXID" \
      --sku-id "$SKU" \
      --dc-id "$DC" \
      --store-id "STORE-001" \
      --quantity "$QTY" \
      --unit-price "8.50" \
      --channel "POS" \
      --direct-api "$API_ENDPOINT"
  else
    python3 scripts/publish-pos-event.py \
      --transaction-id "$TXID" \
      --sku-id "$SKU" \
      --dc-id "$DC" \
      --store-id "STORE-001" \
      --quantity "$QTY" \
      --unit-price "8.50" \
      --channel "POS"
  fi

  echo "Waiting 15s for processing..."
  sleep 15

  # 2. Check sales_events row in RDS
  SALES_COUNT=$(${PSQL} -t -c "SELECT COUNT(*) FROM sales.sales_events WHERE transaction_id = '$TXID'::uuid" | tr -d ' ')
  check "1.5 RDS sales_events row created" "$SALES_COUNT" "1"

  # 3. Check DynamoDB idempotency key
  SHA=$(python3 -c "import hashlib; print(hashlib.sha256('$TXID'.encode()).hexdigest())")
  DDB_RESULT=$(${AWS_CMD} dynamodb get-item \
    --table-name "smartretail-idempotency-keys-${ENV}" \
    --key "{\"event_id\":{\"S\":\"$SHA\"}}" \
    --query 'Item.event_id.S' \
    --output text 2>/dev/null || echo "NOT_FOUND")
  check "1.3 DynamoDB idempotency key written" "$DDB_RESULT" "$SHA"

  # 4. Check inventory_positions updated
  ON_HAND=$(${PSQL} -t -c "SELECT on_hand FROM inventory.inventory_positions WHERE sku_id = '$SKU' AND dc_id = '$DC'" | tr -d ' ')
  check "1.8 IMS inventory_positions updated" "$ON_HAND" "90"

  # 5. Check stock_alert created (ATP=90 < reorder_point=100)
  ALERT_COUNT=$(${PSQL} -t -c "
    SELECT COUNT(*) FROM inventory.stock_alerts sa
    JOIN inventory.inventory_positions ip ON ip.position_id = sa.position_id
    WHERE ip.sku_id = '$SKU' AND ip.dc_id = '$DC' AND sa.status = 'ACTIVE'
    AND sa.raised_at > NOW() - INTERVAL '5 minutes'" | tr -d ' ')
  check "1.9 Stock alert created" "$ALERT_COUNT" "1"

  # 6. Duplicate test
  echo "Testing duplicate rejection..."
  HTTP_STATUS=$(python3 scripts/publish-pos-event.py \
    --transaction-id "$TXID" \
    --sku-id "$SKU" --dc-id "$DC" \
    --store-id "STORE-001" \
    --quantity "$QTY" --unit-price "8.50" --channel "POS" \
    --direct-api "$API_ENDPOINT" \
    --return-status)
  check "1.6 Duplicate event rejected with 409" "$HTTP_STATUS" "409"
}

# ─────────────────────────────────────────
# FLOW 2: Inventory Alert → RE → PO Generation
# ─────────────────────────────────────────
flow2() {
  echo "--- Flow 2: RE PO Generation ---"

  echo "Waiting 10s for RE to process alert from Flow 1..."
  sleep 10

  # Check auto-approve PO created for SKU-BEV-001 / DC-LONDON
  # (auto_approve_threshold = 50000, expected totalValue ~= 850)
  AUTO_PO=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE sku_id = 'SKU-BEV-001' AND dc_id = 'DC-LONDON'
    AND workflow_status = 'APPROVED'
    AND created_at > NOW() - INTERVAL '5 minutes'
    LIMIT 1" | tr -d ' ')
  check "2a.4 Auto-approve PO created (APPROVED)" "$AUTO_PO" "APPROVED"

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
    echo "❌Flow 2 state not found. Run flow2 first."
    FAIL=$((FAIL + 1))
    return
  }

  # Get SC_PLANNER JWT
  SC_PLANNER_TOKEN=$(python3 scripts/get-cognito-token.py \
    --username "sc-planner-1" \
    --password "Test@12345!" \
    --pool-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-pool-id --query Parameter.Value --output text)" \
    --client-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-client-id --query Parameter.Value --output text)")

  IDEMPOTENCY_KEY=$(python3 -c "import uuid; print(uuid.uuid4())")

  # Approve the PO
  APPROVE_STATUS=$(curl -s -o /tmp/approve-response.json -w "%{http_code}" \
    -X POST "${API_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
    -H "Authorization: Bearer ${SC_PLANNER_TOKEN}" \
    -H "X-Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -H "Content-Type: application/json" \
    -d '{"notes":"Smoke test approval"}')

  check "3a.1 Approve returns 200" "$APPROVE_STATUS" "200"

  # Check RDS updated
  APPROVED_STATUS=$(${PSQL} -t -c "
    SELECT workflow_status FROM replenishment.purchase_orders
    WHERE po_id = '${PENDING_PO_ID}'::uuid" | tr -d ' ')
  check "3a.5 RDS workflow_status = APPROVED" "$APPROVED_STATUS" "APPROVED"

  # Test wrong role rejection
  SM_TOKEN=$(python3 scripts/get-cognito-token.py \
    --username "store-manager-1" \
    --password "Test@12345!" \
    --pool-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-pool-id --query Parameter.Value --output text)" \
    --client-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-client-id --query Parameter.Value --output text)")

  # Get another pending PO for the wrong-role test
  OTHER_PO=$(${PSQL} -t -c "
    SELECT po_id FROM replenishment.purchase_orders
    WHERE workflow_status = 'PENDING_APPROVAL'
    LIMIT 1" | tr -d ' ')

  if [ -n "$OTHER_PO" ]; then
    WRONG_ROLE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -X POST "${API_ENDPOINT}/v1/replenishment/orders/${OTHER_PO}/approve" \
      -H "Authorization: Bearer ${SM_TOKEN}" \
      -H "X-Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")
    check "3c STORE_MANAGER role rejected with 403" "$WRONG_ROLE_STATUS" "403"
  fi

  # Test wrong status rejection (already APPROVED)
  WRONG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${API_ENDPOINT}/v1/replenishment/orders/${PENDING_PO_ID}/approve" \
    -H "Authorization: Bearer ${SC_PLANNER_TOKEN}" \
    -H "X-Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")
  check "3d Wrong status returns 409" "$WRONG_STATUS" "409"
}

# ─────────────────────────────────────────
# FLOW 4: ARS → Store Manager Dashboard
# ─────────────────────────────────────────
flow4() {
  echo "--- Flow 4: Store Manager Dashboard API ---"

  SM_TOKEN=$(python3 scripts/get-cognito-token.py \
    --username "store-manager-1" \
    --password "Test@12345!" \
    --pool-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-pool-id --query Parameter.Value --output text)" \
    --client-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-client-id --query Parameter.Value --output text)")

  # Call dashboard API
  HTTP_STATUS=$(curl -s -o /tmp/dashboard-response.json -w "%{http_code}" \
    "${API_ENDPOINT}/v1/dashboard/store-manager?dcId=DC-LONDON" \
    -H "Authorization: Bearer ${SM_TOKEN}")

  check "4.1 Dashboard API returns 200" "$HTTP_STATUS" "200"

  # Check dataFreshness present
  DATA_FRESHNESS=$(python3 -c "
import json
with open('/tmp/dashboard-response.json') as f:
  d = json.load(f)
print('present' if d.get('dataFreshness') else 'missing')")
  check "4.8 dataFreshness present in response" "$DATA_FRESHNESS" "present"

  # Check alert counts non-zero (seed data + flow 1 should have created alerts)
  ALERT_COUNT=$(python3 -c "
import json
with open('/tmp/dashboard-response.json') as f:
  d = json.load(f)
total = sum(d.get('summary', {}).get('lowStockAlerts', {}).values())
print(str(total))")
  check "4.6 Dashboard shows alert counts > 0" "$([ $ALERT_COUNT -gt 0 ] && echo 'yes' || echo 'no')" "yes"
}

# ─────────────────────────────────────────
# FLOW 8: Executive Dashboard
# ─────────────────────────────────────────
flow8() {
  echo "--- Flow 8: Executive Dashboard API ---"

  EXEC_TOKEN=$(python3 scripts/get-cognito-token.py \
    --username "executive-1" \
    --password "Test@12345!" \
    --pool-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-pool-id --query Parameter.Value --output text)" \
    --client-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-client-id --query Parameter.Value --output text)")

  HTTP_STATUS=$(curl -s -o /tmp/exec-dashboard.json -w "%{http_code}" \
    "${API_ENDPOINT}/v1/dashboard/executive" \
    -H "Authorization: Bearer ${EXEC_TOKEN}")

  check "8.1 Executive dashboard returns 200" "$HTTP_STATUS" "200"

  HISTORY_COUNT=$(python3 -c "
import json
with open('/tmp/exec-dashboard.json') as f:
  d = json.load(f)
print(len(d.get('kpis', {}).get('forecastAccuracy', {}).get('history', [])))")
  check "8.2 MAPE history has 30 data points" "$HISTORY_COUNT" "30"

  # EXECUTIVE cannot access SC Planner endpoint
  FORBIDDEN=$(curl -s -o /dev/null -w "%{http_code}" \
    "${API_ENDPOINT}/v1/dashboard/sc-planner" \
    -H "Authorization: Bearer ${EXEC_TOKEN}")
  check "8.5 EXECUTIVE cannot access SC Planner (403)" "$FORBIDDEN" "403"
}

# ─────────────────────────────────────────
# FLOW 9: Supplier Performance
# ─────────────────────────────────────────
flow9() {
  echo "--- Flow 9: Supplier Performance Scorecard ---"

  SCP_TOKEN=$(python3 scripts/get-cognito-token.py \
    --username "sc-planner-1" \
    --password "Test@12345!" \
    --pool-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-pool-id --query Parameter.Value --output text)" \
    --client-id "$(aws ssm get-parameter --name /smartretail/${ENV}/cognito/internal-client-id --query Parameter.Value --output text)")

  HTTP_STATUS=$(curl -s -o /tmp/supplier-perf.json -w "%{http_code}" \
    "${API_ENDPOINT}/v1/dashboard/supplier-performance" \
    -H "Authorization: Bearer ${SCP_TOKEN}")

  check "9.1 Supplier performance returns 200" "$HTTP_STATUS" "200"

  SUPPLIER_COUNT=$(python3 -c "
import json
with open('/tmp/supplier-perf.json') as f:
  d = json.load(f)
print(len(d.get('suppliers', [])))")
  check "9.1 5 suppliers in response" "$SUPPLIER_COUNT" "5"
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
echo "Results: ✅$PASS passed ❌$FAIL failed"
echo "─────────────────────────────────"

[ $FAIL -eq 0 ] && exit 0 || exit 1

