import type { FlowDef } from '../types'

const flow3: FlowDef = {
  id:            'flow3',
  chapterNumber: 3,
  title:         'The Planner Decides',
  subtitle:      'SC Planner approves or rejects the pending PO through the live MFE',
  colorClass:    'from-green-900 to-green-700',
  steps: [
    {
      id:        'pending-po',
      title:     'Locate the pending PO',
      narrative: 'Flow 2 left us a PO in PENDING_APPROVAL. If none exist, click "Create Test PO" to inject one. The Replenishment Engine is waiting for a human decision.',
      dbQueries: [
        {
          key:       'pending-pos',
          label:     'Pending approval POs',
          endpoint:  '/api/dbstate/pending-pos',
          changeKey: 'id',
        },
      ],
      trigger: {
        label:       'Create Test PENDING_APPROVAL PO',
        endpoint:    '/api/trigger/flow3/create-pending-po',
        body:        {},
        description: 'Injects a high-value alert into RE SQS, producing a PENDING_APPROVAL PO',
      },
    },
    {
      id:       'mfe-reveal',
      title:    'Open SC Planner console',
      narrative: 'The Supply Chain Planner opens the SC Planner Console and navigates to the Approval Workflows tab. The pending PO is right there — SKU, DC, supplier, total value, and the current version for optimistic locking.',
      mfeReveal: {
        mfe:       'sc-planner',
        localPort: 5174,
        path:      '/dashboard',
        label:     'SC Planner Console',
      },
      activeNodes: ['ars', 're'],
    },
    {
      id:        'approve',
      title:     'Approve the PO (live)',
      narrative: 'Click Approve in the SC Planner MFE above. Watch what happens: RE validates the JWT role (SC_PLANNER), checks status = PENDING_APPROVAL, runs the optimistic lock UPDATE with WHERE version = :v, transitions to APPROVED, and fires PurchaseOrderApproved to EventBridge.',
      activeNodes: ['re', 'rds', 'eventbridge'],
      flowEdges:   [['re', 'rds'], ['re', 'eventbridge']],
      dbQueries: [
        {
          key:       'approved-pos',
          label:     'PO status after approval',
          endpoint:  '/api/dbstate/approved-pos',
          changeKey: 'workflow_status',
        },
      ],
      checklist: [
        { id: 'c3-1', text: 'JWT role SC_PLANNER validated',           matchPattern: 'SC_PLANNER' },
        { id: 'c3-2', text: 'Status check: PENDING_APPROVAL → passed', matchPattern: 'PENDING_APPROVAL' },
        { id: 'c3-3', text: 'Optimistic lock version incremented',     matchPattern: 'version' },
        { id: 'c3-4', text: 'PO status = APPROVED in RDS',            matchPattern: 'APPROVED' },
        { id: 'c3-5', text: 'EventBridge: PurchaseOrderApproved',      matchPattern: 'PurchaseOrderApproved' },
      ],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'Smoke test covers approve, reject, role-based 403, and 409 on wrong-status transitions.',
      trigger: {
        label:       'Verify Flow 3',
        endpoint:    '/api/trigger/flow3/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow3',
      },
      activeNodes: [],
      checklist: [
        { id: 'v3-1', text: 'Approve → APPROVED',                     matchPattern: 'APPROVED' },
        { id: 'v3-2', text: 'Reject → REJECTED',                      matchPattern: 'REJECTED' },
        { id: 'v3-3', text: 'Wrong role → 403',                        matchPattern: '403' },
        { id: 'v3-4', text: 'Approve DRAFT → 409',                     matchPattern: '409' },
      ],
    },
  ],
}

export default flow3
