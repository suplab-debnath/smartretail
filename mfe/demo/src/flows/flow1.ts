import type { FlowDef } from '../types'

const flow1: FlowDef = {
  id:            'flow1',
  chapterNumber: 1,
  title:         'A Customer Buys Something',
  subtitle:      'POS event travels from the till to inventory in real time',
  colorClass:    'from-blue-900 to-blue-700',
  steps: [
    {
      id:        'scene',
      title:     'Set the scene',
      narrative: 'A shopper at DC-LONDON picks up 30 units of SKU-BEV-001 (a beverage case). The POS terminal fires a transaction. That single event is about to travel through Kinesis, a Lambda adapter, SIS, EventBridge, and IMS — all in under 20 seconds.',
      activeNodes: ['kinesis'],
      flowEdges:   [],
    },
    {
      id:    'pos-event',
      title: 'Fire POS transaction',
      narrative: 'We publish a POS event directly to SIS (bypassing Kinesis in local mode — the Lambda consumer isn\'t running locally). In AWS mode this hits Kinesis first, Lambda decodes the stream record, then calls SIS.',
      trigger: {
        label:       'Fire POS Transaction',
        endpoint:    '/api/trigger/flow1/pos-event',
        body:        { skuId: 'SKU-BEV-001', dcId: 'DC-LONDON', quantity: 30 },
        description: 'Publishes a sales transaction via publish-pos-event.py',
      },
      activeNodes: ['kinesis', 'lambda', 'sis'],
      flowEdges:   [['kinesis', 'lambda'], ['lambda', 'sis']],
      dbQueries: [
        {
          key:       'inventory-before',
          label:     'Inventory position (SKU-BEV-001 @ DC-LONDON)',
          endpoint:  '/api/dbstate/inventory-position',
          params:    { skuId: 'SKU-BEV-001', dcId: 'DC-LONDON' },
          changeKey: 'on_hand',
        },
      ],
    },
    {
      id:    'stock-alert',
      title: 'Stock alert fires',
      narrative: 'SIS writes the sale to RDS and publishes a SalesTransactionEvent to EventBridge. IMS picks it up, decrements inventory, detects on_hand has dropped below reorder_point=100, and creates a stock alert.',
      activeNodes: ['sis', 'eventbridge', 'ims', 'rds'],
      flowEdges:   [['sis', 'eventbridge'], ['eventbridge', 'ims'], ['ims', 'rds']],
      dbQueries: [
        {
          key:       'stock-alerts',
          label:     'Active stock alerts',
          endpoint:  '/api/dbstate/stock-alerts',
          changeKey: 'id',
        },
        {
          key:       'inventory-after',
          label:     'Inventory position (updated)',
          endpoint:  '/api/dbstate/inventory-position',
          params:    { skuId: 'SKU-BEV-001', dcId: 'DC-LONDON' },
          changeKey: 'on_hand',
        },
      ],
      checklist: [
        { id: 'c1-1', text: 'SIS: sales_events row created',    matchPattern: 'sales_events' },
        { id: 'c1-2', text: 'IMS: inventory_positions updated', matchPattern: 'inventory_positions' },
        { id: 'c1-3', text: 'IMS: stock_alerts row created',    matchPattern: 'stock_alerts' },
        { id: 'c1-4', text: 'EventBridge: InventoryAlertEvent', matchPattern: 'InventoryAlertEvent' },
      ],
    },
    {
      id:    'verify',
      title: 'Run smoke verification',
      narrative: 'The smoke test re-runs the same flow end-to-end and checks every evidence point from the spec. Watch the checklist light up.',
      trigger: {
        label:       'Verify Flow 1',
        endpoint:    '/api/trigger/flow1/smoke',
        body:        {},
        description: 'Runs smoke-test.sh flow1',
      },
      activeNodes: [],
      checklist: [
        { id: 'v1-1', text: 'Kinesis record published',               matchPattern: 'kinesis' },
        { id: 'v1-2', text: 'SIS 200 OK',                             matchPattern: '200' },
        { id: 'v1-3', text: 'sales_events row in RDS',                matchPattern: 'sales_events' },
        { id: 'v1-4', text: 'IMS inventory decremented',              matchPattern: 'inventory_positions' },
        { id: 'v1-5', text: 'Stock alert ACTIVE',                     matchPattern: 'ACTIVE' },
        { id: 'v1-6', text: 'Idempotency: duplicate returns 409',     matchPattern: '409' },
      ],
    },
  ],
}

export default flow1
