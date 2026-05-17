'use strict';

const express = require('express');
const router  = express.Router();
const http    = require('http');
const https   = require('https');
const { get: getEnv } = require('../lib/envConfig');

const SERVICE_KEYS = ['sis', 'ims', 're', 'ars', 'dfs', 'sup'];

function ping(url) {
  return new Promise(resolve => {
    const target = `${url}/actuator/health`;
    const client = target.startsWith('https') ? https : http;
    const req = client.get(target, { timeout: 2000 }, res => {
      resolve(res.statusCode === 200 ? 'ok' : 'down');
    });
    req.on('error', () => resolve('down'));
    req.on('timeout', () => { req.destroy(); resolve('down'); });
  });
}

// GET /api/health
router.get('/', async (req, res) => {
  const cfg = getEnv();
  const results = await Promise.all(
    SERVICE_KEYS.map(k => ping(cfg.services[k]).then(status => [k, status]))
  );
  res.json(Object.fromEntries(results));
});

module.exports = router;
