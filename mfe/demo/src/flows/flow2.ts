import type { FlowDef } from '../types'

const flow2: FlowDef = {
  id:            'flow2',
  chapterNumber: 2,
  title:         'The System Responds Automatically',
  subtitle:      'Replenishment Engine evaluates the alert and decides: auto-approve or escalate',
  colorClass:    'from-amber-900 to-amber-700',
  steps: [
    {
      id:        'alert-received',
      title:     'Alert reaches RE',
      narrative: 'The InventoryAlertEvent from Flow 1 has landed in the RE SQS FIFO queue. The Replenishment Engine polls the queue and picks it up. It looks up the SKU\'s replenishment rule — threshold, supplier, lead time.',
      activeNodes: ['sqs', 're'],
      flowEdges:   [['eventbridge', 'sqs'], ['sqs', 're']],
    },
    {
      id:    'threshold-check',
      title: 'Inject alert and watch RE decide',
      narrative: 'We inject an InventoryAlertEvent directly into RE\'s SQS queue for SKU-BEV-003 (high-value item, totalValue > auto-approve threshold). RE will create a PENDING_APPROVAL PO. SKU-BEV-001 from Flow 1 was auto-approved — compare both outcomes.',
      trigger: {
        label:       'Inject RE Alert (SKU-BEV-003)',
        endpoint:    '/api/trigger/flow2/direct',
        body:        { skuId: 'SKU-BEV-003', dcId: 'DC-LONDON' },
        description: 'publish-pos-event.py --flow2-direct',
      },
      activeNodes: ['re', 'rds'],
      flowEdges:   [['sqs', 're'], ['re', 'rds']],
      dbQueries: [
        {
          key:       'purchase-orders',
          label:     'Purchase orders (all statuses)',
          endpoint:  '/api/dbstate/approved-pos',
          changeKey: 'workflow_status',
        },
      ],
      checklist: [
        { id: 'c2-1', text: 'RE: rule lookup succeeded',               matchPattern: 'rule' },
        { id: 'c2-2', text: 'PO created (APPROVED — auto)',            matchPattern: 'APPROVED' },
        { id: 'c2-3', text: 'PO created (PENDING_APPROVAL — manual)',  matchPattern: 'PENDING_APPROVAL' },
        { id: 'c2-4', text: 'EventBridge: PurchaseOrderCreated',       matchPattern: 'PurchaseOrderCreated' },
      ],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'Confirm both scenarios: auto-approve for low-value SKUs and manual approval for high-value ones.',
      trigger: {
        label:       'Verify Flow 2',
        endpoint:    '/api/trigger/flow2/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow2',
      },
      activeNodes: [],
      checklist: [
        { id: 'v2-1', text: 'Scenario 2a: APPROVED PO in RDS',         matchPattern: "'APPROVED'" },
        { id: 'v2-2', text: 'Scenario 2b: PENDING_APPROVAL PO in RDS', matchPattern: 'PENDING_APPROVAL' },
        { id: 'v2-3', text: 'Optimistic lock version = 0',             matchPattern: 'version' },
      ],
    },
  ],
}

export default flow2
