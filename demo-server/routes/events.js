'use strict';

const express     = require('express');
const router      = express.Router();
const broadcaster = require('../lib/sseBroadcaster');

// GET /api/events/stream — SSE endpoint
router.get('/stream', (req, res) => {
  res.setHeader('Content-Type',  'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection',    'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no'); // disable nginx buffering if behind proxy
  res.flushHeaders();

  // Send a keep-alive comment every 20 s to prevent proxy timeouts
  const keepAlive = setInterval(() => {
    try { res.write(': keep-alive\n\n'); } catch { /* client gone */ }
  }, 20_000);

  broadcaster.addClient(res);

  req.on('close', () => {
    clearInterval(keepAlive);
    broadcaster.removeClient(res);
  });
});

module.exports = router;
