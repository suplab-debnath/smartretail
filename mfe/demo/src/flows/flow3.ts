import type { FlowDef } from '../types'

const flow3: FlowDef = {
  id:            'flow3',
  chapterNumber: 3,
  title:         'The Planner Decides',
  subtitle:      'SC Planner approves or rejects the pending PO through the live MFE',
  colorClass:    'from-green-900 to-green-700',
  laymansIntro:  'A high-value restocking order is waiting in a queue. Only an authorised Supply Chain Planner can approve or reject it — and the system has several safeguards to prevent mistakes, such as two people accidentally approving the same order at the same time.',
  steps: [
    {
      id:        'pending-po',
      title:     'Locate the pending PO',
      narrative: 'Flow 2 left us a PO in PENDING_APPROVAL. If none exist, click "Create Test PO" to inject one. The Replenishment Engine is waiting for a human decision.',
      laymansNote: 'An expensive restocking order is sitting in a queue, waiting for someone to say yes or no. This is deliberate — the system won\'t commit large amounts of money without authorisation.',
      dbQueries: [
        {
          key:         'pending-pos',
          label:       'Pending approval POs',
          endpoint:    '/api/dbstate/pending-pos',
          changeKey:   'po_id',
          description: 'These are the orders currently awaiting a human decision. Each row shows what product needs restocking, how many units, which supplier would fulfil it, and the total cost.',
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
      narrative: 'The Supply Chain Planner opens the SC Planner Console and navigates to the **Approval Workflows** tab. Find the row for **SKU-BEV-003 / DC-LONDON** with status `PENDING_APPROVAL` and click **Approve**. The pending PO shows the SKU, DC, supplier, total value, and the current version for optimistic locking. Once approved, advance to the next step.',
      laymansNote: 'This is the actual screen a Supply Chain Planner uses every day. Everything they need to make the decision is right there: what product, how many units, which supplier, what it costs. Look for SKU-BEV-003 in the Approval Workflows tab and click Approve — then come back to the next step to confirm the status change.',
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
      narrative: 'After approving SKU-BEV-003 in the Approval Workflows tab of the SC Planner Console above, click the button below to confirm the status change. RE validates the JWT role (SC_PLANNER), checks status = PENDING_APPROVAL, runs the optimistic lock UPDATE with WHERE version = :v, transitions to APPROVED, and fires PurchaseOrderApproved to EventBridge.',
      laymansNote: 'When the planner clicks Approve in the MFE, several safety checks run silently: Is this person allowed to approve? Is the order still in the right state? Has anyone else already approved it? Only when all checks pass does the system confirm the order. Click the button below after approving to see the status flip to APPROVED.',
      activeNodes: ['re', 'rds', 'eventbridge'],
      flowEdges:   [['re', 'rds'], ['re', 'eventbridge']],
      dbQueries: [
        {
          key:         'approved-pos',
          label:       'PO status after approval',
          endpoint:    '/api/dbstate/approved-pos',
          changeKey:   'workflow_status',
          description: 'The restocking order\'s current status. The "workflow_status" column should change from PENDING_APPROVAL to APPROVED. Also notice the "version" number ticking up — this prevents two people from approving the same order simultaneously.',
          actionLabel: 'I\'ve approved in the SC Planner — re-check status',
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
      laymansNote: 'The automated check confirms that approvals work, rejections work, that someone without the right job title can\'t approve orders, and that the system refuses to approve an order that\'s already been dealt with.',
      trigger: {
        label:       'Verify Flow 3',
        endpoint:    '/api/trigger/flow3/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow3',
      },
      activeNodes: [],
      checklist: [
        { id: 'v3-1', text: 'Approve returns 200',                     matchPattern: '200' },
        { id: 'v3-2', text: 'RDS status = APPROVED',                   matchPattern: 'APPROVED' },
        { id: 'v3-3', text: 'Wrong role → 403',                        matchPattern: '403' },
        { id: 'v3-4', text: 'Approve wrong-status → 409',              matchPattern: '409' },
      ],
    },
  ],
}

export default flow3
