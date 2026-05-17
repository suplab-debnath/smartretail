'use strict';

const express  = require('express');
const router   = express.Router();
const db       = require('../lib/dbClient');
const { ENV, get: getEnv } = require('../lib/envConfig');
const https    = require('https');
const http     = require('http');

// Thin wrapper — falls back to ARS API in AWS mode
async function arsGet(path) {
  const base = getEnv().services.ars;
  const url  = `${base}${path}`;
  return new Promise((resolve, reject) => {
    const client = url.startsWith('https') ? https : http;
    client.get(url, { headers: { 'X-Dev-Role': 'SC_PLANNER' } }, res => {
      let data = '';
      res.on('data', d => (data += d));
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch { resolve({}); }
      });
    }).on('error', reject);
  });
}

// GET /api/dbstate/pending-pos
router.get('/pending-pos', async (req, res) => {
  try {
    if (ENV === 'local') {
      const rows = await db.query(
        `SELECT id, sku_id, dc_id, workflow_status, total_value, version, created_at
           FROM replenishment.purchase_orders
          WHERE workflow_status = 'PENDING_APPROVAL'
          ORDER BY created_at DESC
          LIMIT 10`
      );
      return res.json({ rows });
    }
    // AWS fallback via ARS
    const data = await arsGet('/v1/replenishment/orders?status=PENDING_APPROVAL&size=10');
    res.json({ rows: data.content || data.orders || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/dbstate/stock-alerts
router.get('/stock-alerts', async (req, res) => {
  try {
    if (ENV === 'local') {
      const rows = await db.query(
        `SELECT id, sku_id, dc_id, alert_type, severity, status, created_at
           FROM inventory.stock_alerts
          WHERE status = 'ACTIVE'
          ORDER BY created_at DESC
          LIMIT 10`
      );
      return res.json({ rows });
    }
    const data = await arsGet('/v1/dashboard/store-manager?dcId=DC-LONDON&page=0&size=10');
    res.json({ rows: data.alerts || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/dbstate/inventory-position?skuId=SKU-BEV-001&dcId=DC-LONDON
router.get('/inventory-position', async (req, res) => {
  const { skuId = 'SKU-BEV-001', dcId = 'DC-LONDON' } = req.query;
  try {
    if (ENV === 'local') {
      const rows = await db.query(
        `SELECT sku_id, dc_id, on_hand, available_to_promise, reorder_point, updated_at
           FROM inventory.inventory_positions
          WHERE sku_id = $1 AND dc_id = $2`,
        [skuId, dcId]
      );
      return res.json({ rows });
    }
    const data = await arsGet(`/v1/dashboard/store-manager?dcId=${dcId}&page=0&size=1`);
    res.json({ rows: data.inventoryPositions || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/dbstate/latest-sale?txId=<uuid>
router.get('/latest-sale', async (req, res) => {
  const { txId } = req.query;
  try {
    if (ENV === 'local') {
      const rows = await db.query(
        `SELECT transaction_id, sku_id, dc_id, quantity_sold, channel, recorded_at
           FROM sales.sales_events
          ${txId ? 'WHERE transaction_id = $1::uuid' : 'ORDER BY recorded_at DESC LIMIT 5'}`,
        txId ? [txId] : []
      );
      return res.json({ rows });
    }
    res.json({ rows: [] }); // not available via ARS API
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// GET /api/dbstate/approved-pos
router.get('/approved-pos', async (req, res) => {
  try {
    if (ENV === 'local') {
      const rows = await db.query(
        `SELECT id, sku_id, dc_id, workflow_status, total_value, version, created_at
           FROM replenishment.purchase_orders
          WHERE workflow_status IN ('APPROVED', 'PENDING_APPROVAL')
          ORDER BY created_at DESC
          LIMIT 10`
      );
      return res.json({ rows });
    }
    const data = await arsGet('/v1/replenishment/orders?size=10');
    res.json({ rows: data.content || data.orders || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;
