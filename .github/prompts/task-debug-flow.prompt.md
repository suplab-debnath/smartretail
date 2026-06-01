---
mode: 'agent'
description: 'Task: Diagnose a failing flow assertion -- read service logs, query DB, trace the event chain'
tools: ['codebase', 'runCommand', 'terminalLastCommand', 'workspaceDetails']
---

Diagnose a failing SmartRetail flow assertion.

## Failure details
- **Flow number:** ${input:flowNumber}
  (1=POS->SIS->IMS, 2=alert->RE, 3=SC Planner approve/reject, 4=ARS dashboard)
- **Failing assertion:** ${input:assertion}
  (e.g. `1.8 IMS inventory_positions not updated`, `2.3 PO not created`)
- **Transaction ID used in test:** ${input:transactionId}
  (UUID used in the POS event, if applicable)

## Diagnostic steps to execute

### Step 1: Verify infrastructure is healthy
```bash
docker-compose ps   # both postgres and localstack must be healthy
awslocal sqs list-queues   # queues must exist
```

### Step 2: Check SIS processed the event (Flow 1+)
```sql
SELECT * FROM sales.sales_events
WHERE transaction_id = '{transactionId}';

SELECT * FROM sales.idempotency_keys
ORDER BY received_at DESC LIMIT 5;
```

### Step 3: Check EventBridge / SQS delivery (Flow 1+)
```bash
awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/smartretail-ims-sales-local \
  --attribute-names ApproximateNumberOfMessages
```

### Step 4: Check IMS processed the event (Flow 1+)
```sql
SELECT sku_id, dc_id, on_hand_quantity, reserved_quantity, updated_at
FROM inventory.inventory_positions
WHERE sku_id = '...' AND dc_id = '...';

SELECT * FROM inventory.stock_alerts
WHERE status = 'ACTIVE' ORDER BY raised_at DESC LIMIT 5;
```

### Step 5: Check RE received alert and created PO (Flow 2+)
```sql
SELECT * FROM replenishment.purchase_orders
ORDER BY created_at DESC LIMIT 5;
```

### Step 6: Read service logs for errors
```bash
docker-compose logs --tail=50 --since=5m {service_name}
```

## Your task
Run the diagnostic steps above for flow ${input:flowNumber}, failing assertion: ${input:assertion}. Report the root cause with the specific log line or DB state that proves it, and propose the fix.
