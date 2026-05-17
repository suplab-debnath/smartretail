import type { FlowDef } from '../types'

const flow9: FlowDef = {
  id:            'flow9',
  chapterNumber: 6,
  title:         'The Planner Optimizes',
  subtitle:      'SC Planner Console — 8 surfaces, one write path, full visibility',
  colorClass:    'from-teal-900 to-teal-700',
  steps: [
    {
      id:        'overview',
      title:     'Eight surfaces, one console',
      narrative: 'The SC Planner Console gives the supply chain planner complete operational visibility: exception queue, inventory overview by DC, demand forecast (P10/P50/P90 bands), stockout risk, approval workflows, supplier order tracking, replenishment trigger, and forecast adjustment controls.',
      activeNodes: ['ars', 'dfs', 'sup'],
    },
    {
      id:       'mfe-reveal',
      title:    'Open SC Planner Console',
      narrative: 'Log in as SC_PLANNER. The console opens on the Exception Queue tab showing active stock alerts by severity. Walk through each tab — each one calls a different backend endpoint with no cross-schema joins.',
      mfeReveal: {
        mfe:       'sc-planner',
        localPort: 5174,
        path:      '/dashboard',
        label:     'SC Planner Console',
      },
      activeNodes: ['ars', 'dfs', 'sup'],
      checklist: [
        { id: 'c9-1', text: 'Exception Queue tab: alerts by severity',         matchPattern: 'severity' },
        { id: 'c9-2', text: 'Inventory Overview tab: ATP by DC',               matchPattern: 'available_to_promise' },
        { id: 'c9-3', text: 'Demand Forecast tab: P10/P50/P90 bands',         matchPattern: 'p50' },
        { id: 'c9-4', text: 'Stockout Risk tab: risk indicators',              matchPattern: 'stockout' },
        { id: 'c9-5', text: 'Approval Workflows tab: pending POs',            matchPattern: 'PENDING_APPROVAL' },
        { id: 'c9-6', text: 'Supplier Order Tracking tab: shipment statuses', matchPattern: 'shipment' },
      ],
    },
    {
      id:        'replenishment-trigger',
      title:     'Manual replenishment trigger',
      narrative: 'The planner decides to trigger a manual replenishment for SKU-DAIRY-002 before the automated cycle runs. They click "Trigger Replenishment" in the MFE — this POSTs to RE and creates a DRAFT PO. The PO immediately appears in the approval queue.',
      activeNodes: ['re', 'rds'],
      flowEdges:   [['re', 'rds']],
      dbQueries: [
        {
          key:       'draft-pos',
          label:     'Draft POs created',
          endpoint:  '/api/dbstate/approved-pos',
          changeKey: 'workflow_status',
        },
      ],
      checklist: [
        { id: 'c9-7', text: 'DRAFT PO created in RDS',              matchPattern: 'DRAFT' },
        { id: 'c9-8', text: 'Replenishment trigger 201 Created',    matchPattern: '201' },
      ],
    },
    {
      id:        'forecast-adjustment',
      title:     'Forecast adjustment',
      narrative: 'A promotional event is planned for next week. The planner applies a +15% uplift to the forecast for SKU-BEV-001 at DC-LONDON. The chart re-renders with the adjusted P50 band — the increased demand signal will flow into the next RE cycle.',
      activeNodes: ['dfs', 'rds'],
      checklist: [
        { id: 'c9-9',  text: 'Forecast adjustment saved',           matchPattern: 'adjustment' },
        { id: 'c9-10', text: 'P50 band reflects +15% uplift',       matchPattern: 'uplift' },
      ],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'Final verification: all 8 surfaces render, SC_PLANNER role enforced, DRAFT PO creation write path confirmed.',
      trigger: {
        label:       'Verify Flow 9',
        endpoint:    '/api/trigger/flow9/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow9',
      },
      activeNodes: [],
      checklist: [
        { id: 'v9-1', text: 'SC Planner dashboard returns 200',     matchPattern: '200' },
        { id: 'v9-2', text: 'SC Planner endpoint responded',        matchPattern: 'SC Planner' },
        { id: 'v9-3', text: '5 suppliers in response',              matchPattern: 'suppliers' },
        { id: 'v9-4', text: 'Supplier performance returns 200',     matchPattern: 'supplier performance' },
      ],
    },
  ],
}

export default flow9
