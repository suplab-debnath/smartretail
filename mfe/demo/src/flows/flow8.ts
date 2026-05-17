import type { FlowDef } from '../types'

const flow8: FlowDef = {
  id:            'flow8',
  chapterNumber: 5,
  title:         'Leadership Reviews Performance',
  subtitle:      '30 days of seed data rendered across 9 KPI surfaces in the Executive Dashboard',
  colorClass:    'from-rose-900 to-rose-700',
  steps: [
    {
      id:        'seed-data',
      title:     '30 days, 5 suppliers, 3 DCs',
      narrative: 'V7__seed_data.sql populated 30–90 days of realistic transactions: 5 suppliers, 3 distribution centres, multiple SKU categories. The Executive Dashboard draws from forecasting.forecast_runs, inventory.stock_alerts, replenishment.purchase_orders, and the supplier schema.',
      activeNodes: ['rds'],
    },
    {
      id:       'mfe-reveal',
      title:    'Open Executive Dashboard',
      narrative: 'The CFO logs in with the EXECUTIVE role. Nine KPI surfaces render in seconds. Notice the MAPE trend improving from 0.1187 → 0.0823 over the seed period — the forecasting model is converging.',
      mfeReveal: {
        mfe:       'executive',
        localPort: 5175,
        path:      '/dashboard',
        label:     'Executive Dashboard',
      },
      activeNodes: ['ars', 'dfs'],
      checklist: [
        { id: 'c8-1', text: 'Fulfilment Rate card renders',          matchPattern: 'fulfilment' },
        { id: 'c8-2', text: 'MAPE trend LineChart renders',          matchPattern: 'mape' },
        { id: 'c8-3', text: 'Stockout incidents BarChart renders',   matchPattern: 'stockout' },
        { id: 'c8-4', text: 'Supplier comparison table renders',     matchPattern: 'supplier' },
        { id: 'c8-5', text: 'OTD % metric present',                  matchPattern: 'onTimeDelivery' },
      ],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'Confirms all 9 KPI fields are non-null, EXECUTIVE role is required (SC_PLANNER returns 403), and dataFreshness is within 5 minutes.',
      trigger: {
        label:       'Verify Flow 8',
        endpoint:    '/api/trigger/flow8/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow8',
      },
      activeNodes: [],
      checklist: [
        { id: 'v8-1', text: 'MAPE history has 30 data points',      matchPattern: 'data points' },
        { id: 'v8-2', text: 'EXECUTIVE role required (403)',         matchPattern: 'EXECUTIVE' },
        { id: 'v8-3', text: 'Executive dashboard returns 200',       matchPattern: '200' },
        { id: 'v8-4', text: 'MAPE history present',                  matchPattern: 'mape' },
      ],
    },
  ],
}

export default flow8
