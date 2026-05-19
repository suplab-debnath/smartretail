import type { FlowDef } from '../types'

const flow9: FlowDef = {
  id: 'flow9',
  chapterNumber: 6,
  title: 'The Planner Optimizes',
  subtitle: 'SC Planner Console — 8 surfaces, one write path, full visibility',
  colorClass: 'from-teal-900 to-teal-700',
  laymansIntro: 'Where a Store Manager sees one warehouse, a Supply Chain Planner sees everything at once. This chapter shows their control room: eight different views covering every exception, risk, and decision across the entire supply chain — plus the ability to act on what they see.',
  steps: [
    {
      id: 'overview',
      title: 'Seven surfaces, one console',
      narrative: 'The SC Planner Console gives the supply chain planner complete operational visibility: exception queue, inventory overview by DC, demand forecast, stockout risk, approval workflows, supplier order tracking, and replenishment trigger.',
      laymansNote: 'Think of this as the supply chain planner\'s control room. Instead of toggling between seven different tools, everything is in one place: what\'s broken, what\'s low, what\'s been ordered, what\'s been approved, and what\'s on its way.',
      activeNodes: ['ars', 'dfs', 'sup'],
    },
    {
      id: 'mfe-reveal',
      title: 'Open SC Planner Console',
      narrative: 'Log in as SC_PLANNER. The console opens on the Exception Queue tab showing active stock alerts by severity. Walk through each tab — each one calls a different backend endpoint with no cross-schema joins.',
      laymansNote: 'Walk through each tab to get a feel for the breadth of this tool. The first tab shows the most urgent problems (exceptions). Others show warehouse-by-warehouse inventory, predicted demand, supplier shipment status, and pending approvals — all in one login.',
      mfeReveal: {
        mfe: 'sc-planner',
        localPort: 5174,
        path: '/dashboard',
        label: 'SC Planner Console',
      },
      activeNodes: ['ars', 'dfs', 'sup'],
      checklist: [
        { id: 'c9-1', text: 'Exception Queue tab: alerts by severity', matchPattern: 'severity' },
        { id: 'c9-2', text: 'Inventory Overview tab: ATP by DC', matchPattern: 'available_to_promise' },
        { id: 'c9-3', text: 'Demand Forecast tab: P10/P50/P90 bands', matchPattern: 'p50' },
        { id: 'c9-4', text: 'Stockout Risk tab: risk indicators', matchPattern: 'stockout' },
        { id: 'c9-5', text: 'Approval Workflows tab: pending POs', matchPattern: 'PENDING_APPROVAL' },
        { id: 'c9-6', text: 'Supplier Order Tracking tab: shipment statuses', matchPattern: 'shipment' },
      ],
    },
    {
      id: 'replenishment-trigger',
      title: 'Manual replenishment trigger',
      narrative: 'The planner decides to trigger a manual replenishment for SKU-DAIRY-002 before the automated cycle runs. They click "Trigger Replenishment" in the MFE — this POSTs to RE and creates a DRAFT PO. The PO immediately appears in the approval queue.',
      laymansNote: 'Sometimes the planner knows something the system doesn\'t — a big promotion coming up, a supplier running late. This lets them order stock manually before the system\'s automated cycle would normally catch it. The order starts as a DRAFT and then goes through the same approval process as any other.',
      activeNodes: ['re', 'rds'],
      flowEdges: [['re', 'rds']],
      dbQueries: [
        {
          key: 'draft-pos',
          label: 'Draft POs created',
          endpoint: '/api/dbstate/approved-pos',
          changeKey: 'workflow_status',
          description: 'Restocking orders the planner has manually triggered. A new row with status DRAFT should appear after clicking the trigger. It will then need to go through the same approval process as the automated orders from Flow 2.',
        },
      ],
      checklist: [
        { id: 'c9-7', text: 'DRAFT PO created in RDS', matchPattern: 'DRAFT' },
        { id: 'c9-8', text: 'Replenishment trigger 201 Created', matchPattern: '201' },
      ],
    },
    {
      id: 'forecast-adjustment',
      title: 'Forecast adjustment',
      narrative: 'A promotional event is planned for next week. The planner applies a +15% uplift to the forecast for SKU-BEV-001 at DC-LONDON. The chart re-renders with the adjusted P50 band — the increased demand signal will flow into the next RE cycle.',
      laymansNote: 'If a sale or marketing campaign is planned, demand will be higher than normal. The planner can manually tell the system to expect more sales next week. The chart updates immediately, and the higher demand signal will feed into the next automated reorder cycle — so extra stock arrives before it runs out.',
      activeNodes: ['dfs', 'rds'],
      checklist: [
        { id: 'c9-9', text: 'Forecast adjustment saved', matchPattern: 'adjustment' },
        { id: 'c9-10', text: 'P50 band reflects +15% uplift', matchPattern: 'uplift' },
      ],
    },
    {
      id: 'verify',
      title: 'Run smoke verification',
      narrative: 'Final verification: all 8 surfaces render, SC_PLANNER role enforced, DRAFT PO creation write path confirmed.',
      laymansNote: 'Final check: every tab in the console responds correctly, only authorised planners can access it, and the manual order trigger successfully creates a record in the database.',
      trigger: {
        label: 'Verify Flow 9',
        endpoint: '/api/trigger/flow9/smoke',
        body: {},
        description: 'Runs smoke-test.sh flow9',
      },
      activeNodes: [],
      checklist: [
        { id: 'v9-1', text: 'SC Planner dashboard returns 200', matchPattern: '200' },
        { id: 'v9-2', text: 'SC Planner endpoint responded', matchPattern: 'SC Planner' },
        { id: 'v9-3', text: '5 suppliers in response', matchPattern: 'suppliers' },
        { id: 'v9-4', text: 'Supplier performance returns 200', matchPattern: 'supplier performance' },
      ],
    },
  ],
}

export default flow9
