import type { FlowDef } from '../types'

const flow4: FlowDef = {
  id:            'flow4',
  chapterNumber: 4,
  title:         'The Store Manager Reacts',
  subtitle:      'ARS aggregates across schemas — no SQL joins — and surfaces KPIs to the Store Manager',
  colorClass:    'from-purple-900 to-purple-700',
  steps: [
    {
      id:        'aggregation',
      title:     'ARS runs parallel queries',
      narrative: 'The Aggregation & Reporting Service receives a request from the Store Manager dashboard. Per architecture rule R1, it runs four separate queries — sales, inventory, replenishment, forecasting — and merges the results in Java. No cross-schema SQL joins.',
      activeNodes: ['ars', 'rds'],
      flowEdges:   [['ars', 'rds']],
      dbQueries: [
        {
          key:      'stock-alerts',
          label:    'Active alerts (DC-LONDON)',
          endpoint: '/api/dbstate/stock-alerts',
        },
        {
          key:      'pending-pos',
          label:    'Pending replenishment POs',
          endpoint: '/api/dbstate/pending-pos',
        },
      ],
    },
    {
      id:       'mfe-reveal',
      title:    'Open Store Manager dashboard',
      narrative: 'The Store Manager logs in and selects DC-LONDON. Four KPI cards render: active alerts, on-hand units, pending replenishment count, and forecast coverage days. The alert list shows the SKU-BEV-001 alert we triggered in Flow 1.',
      mfeReveal: {
        mfe:       'store-manager',
        localPort: 5173,
        path:      '/dashboard',
        label:     'Store Manager Dashboard',
      },
      activeNodes: ['ars'],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'Confirms dcId enforcement, non-zero alert count, on_hand values, and dataFreshness timestamp in the response.',
      trigger: {
        label:       'Verify Flow 4',
        endpoint:    '/api/trigger/flow4/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow4',
      },
      activeNodes: [],
      checklist: [
        { id: 'v4-1', text: 'ARS /dashboard/store-manager 200',       matchPattern: '200' },
        { id: 'v4-2', text: 'dcId=DC-LONDON enforced',                matchPattern: 'DC-LONDON' },
        { id: 'v4-3', text: 'alertCount > 0',                         matchPattern: 'alertCount' },
        { id: 'v4-4', text: 'dataFreshness timestamp present',        matchPattern: 'dataFreshness' },
        { id: 'v4-5', text: 'No cross-schema SQL joins in query log', matchPattern: 'parallel' },
      ],
    },
  ],
}

export default flow4
