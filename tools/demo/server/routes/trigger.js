'use strict';

const express = require('express');
const router  = express.Router();
const runner  = require('../lib/scriptRunner');
const config  = require('../lib/flowConfig');

// POST /api/trigger/:flowId/:stepId
// Body: optional JSON params forwarded to flowConfig.resolve()
router.post('/:flowId/:stepId', async (req, res) => {
  const { flowId, stepId } = req.params;
  const body = req.body || {};

  if (runner.isRunning(flowId)) {
    return res.status(409).json({ error: `Flow ${flowId} is already running` });
  }

  const scriptDef = config.resolve(flowId, stepId, body);
  if (!scriptDef) {
    return res.status(404).json({ error: `Unknown trigger: ${flowId}.${stepId}` });
  }

  // Respond immediately — the script output streams via SSE
  res.json({ ok: true, flowId, stepId });

  try {
    const { exitCode } = await runner.run({ flowId, stepId, ...scriptDef });
    if (exitCode !== 0) {
      console.error(`[trigger] ${flowId}.${stepId} exited ${exitCode}`);
    }
  } catch (err) {
    console.error(`[trigger] ${flowId}.${stepId} error:`, err.message);
  }
});

// DELETE /api/trigger/:flowId — kill a running flow
router.delete('/:flowId', (req, res) => {
  runner.kill(req.params.flowId);
  res.json({ ok: true });
});

module.exports = router;
