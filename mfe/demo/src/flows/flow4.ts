import type { FlowDef } from '../types'

const flow4: FlowDef = {
  id:            'flow4',
  chapterNumber: 4,
  title:         'The Store Manager Reacts',
  subtitle:      'ARS aggregates across schemas — no SQL joins — and surfaces KPIs to the Store Manager',
  colorClass:    'from-purple-900 to-purple-700',
  laymansIntro:  'A Store Manager needs a single dashboard that shows the health of their warehouse at a glance — stock alerts, units on hand, pending restock orders. This chapter shows how the system pulls that picture together from several different data sources without slowing everything down.',
  steps: [
    {
      id:        'aggregation',
      title:     'ARS runs parallel queries',
      narrative: 'The Aggregation & Reporting Service receives a request from the Store Manager dashboard. Per architecture rule R1, it runs four separate queries — sales, inventory, replenishment, forecasting — and merges the results in Java. No cross-schema SQL joins.',
      laymansNote: 'The dashboard needs information from several different places — stock levels, pending orders, sales data, forecasts. Rather than one slow combined query, the system fetches each piece separately at the same time and combines them. It\'s faster and keeps each data source independent.',
      activeNodes: ['ars', 'rds'],
      flowEdges:   [['ars', 'rds']],
      dbQueries: [
        {
          key:         'stock-alerts',
          label:       'Active alerts (DC-LONDON)',
          endpoint:    '/api/dbstate/stock-alerts',
          description: 'Products that have fallen below their safety stock level at the London warehouse. These will appear as warning cards on the Store Manager\'s dashboard.',
        },
        {
          key:         'pending-pos',
          label:       'Pending replenishment POs',
          endpoint:    '/api/dbstate/pending-pos',
          description: 'Restocking orders that are in progress — either waiting for approval or already approved and on their way. These show the Store Manager that action is being taken on the alerts.',
        },
      ],
    },
    {
      id:       'mfe-reveal',
      title:    'Open Store Manager dashboard',
      narrative: 'The Store Manager logs in and selects DC-LONDON. Four KPI cards render: active alerts, on-hand units, pending replenishment count, and forecast coverage days. The alert list shows the SKU-BEV-001 alert we triggered in Flow 1.',
      laymansNote: 'This is what a Store Manager sees when they start their shift. Four key numbers at a glance: how many products need attention, how much stock is on hand, how many reorders are in progress, and how many days of stock cover they have. The SKU-BEV-001 alert from Flow 1 should be visible here.',
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
      laymansNote: 'Checks that the dashboard only shows data for the correct warehouse, that alerts are being reported, and that the data timestamp confirms it\'s up to date.',
      trigger: {
        label:       'Verify Flow 4',
        endpoint:    '/api/trigger/flow4/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow4',
      },
      activeNodes: [],
      checklist: [
        { id: 'v4-1', text: 'ARS /dashboard/store-manager 200',       matchPattern: '200' },
        { id: 'v4-2', text: 'Dashboard API responding',               matchPattern: 'Dashboard API' },
        { id: 'v4-3', text: 'Alert count > 0',                        matchPattern: 'alert counts' },
        { id: 'v4-4', text: 'dataFreshness timestamp present',        matchPattern: 'dataFreshness' },
        { id: 'v4-5', text: 'No cross-schema SQL joins',              matchPattern: 'present' },
      ],
    },
  ],
}

export default flow4
