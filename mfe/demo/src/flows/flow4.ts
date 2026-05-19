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
      title:     'ARS aggregates & Store Manager Dashboard',
      narrative: 'The Aggregation & Reporting Service runs four separate queries — sales, inventory, replenishment, forecasting — and merges the results in Java. No cross-schema SQL joins (architecture rule R1). The Store Manager logs in, selects DC-LONDON, and four KPI cards render instantly: active alerts, on-hand units, pending replenishment count, and forecast coverage days. The SKU-BEV-001 alert from Flow 1 should be visible on the dashboard.',
      laymansNote: 'The dashboard needs information from several different places — stock levels, pending orders, sales data, forecasts. Rather than one slow combined query, the system fetches each piece separately at the same time and combines them. Open the portal below to see what a Store Manager sees when they start their shift: four key numbers at a glance, including the low-stock alert we triggered in Flow 1.',
      activeNodes: ['ars', 'rds'],
      flowEdges:   [['ars', 'rds']],
      mfeReveal: {
        mfe:       'store-manager',
        localPort: 5173,
        path:      '/dashboard',
        label:     'Store Manager Dashboard',
      },
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
