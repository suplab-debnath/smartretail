'use strict';

const { get: getEnv, ENV } = require('./envConfig');

/**
 * Maps trigger keys (flow.step) → { cmd, args, env }
 * Callers: routes/trigger.js
 */
function resolve(flowId, stepId, body = {}) {
  const cfg = getEnv();
  const key = `${flowId}.${stepId}`;

  switch (key) {
    // ── Flow 1 ─────────────────────────────────────────────────────────────
    case 'flow1.pos-event':
      // Both local and AWS modes publish via Firehose.
      // Local: boto3 → LocalStack at localhost:4566 → SIS FirehoseBatchFilter
      // AWS: boto3 → real Firehose → API GW → SIS FirehoseBatchFilter
      return {
        cmd: process.env.PYTHON_CMD ||
          (process.platform === 'darwin'
            ? '/opt/homebrew/opt/python@3.13/bin/python3'
            : process.platform === 'win32'
              ? 'python'
              : 'python3'),
        args: [
          'scripts/shared/publish-pos-event.py',
          '--transaction-id', body.transactionId || randomUuid(),
          '--sku-id', body.skuId || 'SKU-BEV-001',
          '--dc-id', body.dcId || 'DC-LONDON',
          '--store-id', body.storeId || 'STORE-001',
          '--quantity', String(body.quantity || 30),
          '--unit-price', '8.50',
          '--channel', 'POS',
          '--env', ENV,
        ],
      };

    case 'flow1.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow1'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    // ── Flow 2 ─────────────────────────────────────────────────────────────
    case 'flow2.direct':
      return {
        cmd: process.env.PYTHON_CMD ||
          (process.platform === 'darwin'
            ? '/opt/homebrew/opt/python@3.13/bin/python3'
            : process.platform === 'win32'
              ? 'python'
              : 'python3'),
        args: [
          'scripts/shared/publish-pos-event.py',
          '--flow2-direct',
          '--sku-id', body.skuId || 'SKU-BEV-003',
          '--dc-id', body.dcId || 'DC-LONDON',
          '--env', ENV,
        ],
      };

    case 'flow2.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow2'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    // ── Flow 3 ─────────────────────────────────────────────────────────────
    // Flow 3 smoke: in local mode use direct REST with X-Dev-Role header instead
    // of Cognito SSM, so we call the smoke script with LOCAL env only.
    case 'flow3.create-pending-po':
      return {
        cmd: process.env.PYTHON_CMD ||
          (process.platform === 'darwin'
            ? '/opt/homebrew/opt/python@3.13/bin/python3'
            : process.platform === 'win32'
              ? 'python'
              : 'python3'),
        args: [
          'scripts/shared/publish-pos-event.py',
          '--flow2-direct',
          '--sku-id', 'SKU-BEV-003',
          '--dc-id', 'DC-LONDON',
          '--env', ENV,
        ],
      };

    case 'flow3.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow3'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    // ── Flow 4 ─────────────────────────────────────────────────────────────
    case 'flow4.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow4'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    // ── Flow 8 ─────────────────────────────────────────────────────────────
    case 'flow8.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow8'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    // ── Flow 9 ─────────────────────────────────────────────────────────────
    case 'flow9.smoke':
      return {
        cmd: 'bash', args: ['scripts/shared/smoke-test.sh', 'flow9'],
        env: { SMARTRETAIL_ENV: ENV }
      };

    default:
      return null;
  }
}

function randomUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

module.exports = { resolve };
