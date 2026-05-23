'use strict';

const { spawn } = require('child_process');
const path = require('path');
const broadcaster = require('./sseBroadcaster');

// One active spawn per flowId — prevents double-triggering during demo
const activeProcesses = new Map();

// Repo root — scripts use relative paths from here
const REPO_ROOT = path.resolve(__dirname, '..', '..');

/**
 * Parse a raw stdout line from smoke-test.sh into a log level.
 * Lines starting with [PASS] are pass, [FAIL] are fail, otherwise info.
 */
function parseLevel(line) {
  if (line.includes('[PASS]')) return 'pass';
  if (line.includes('[FAIL]')) return 'fail';
  if (line.toLowerCase().includes('error') || line.toLowerCase().includes('fail')) return 'fail';
  if (line.toLowerCase().includes('warn')) return 'warn';
  return 'info';
}

/**
 * @param {object} opts
 * @param {string}   opts.flowId
 * @param {string}   opts.stepId
 * @param {string}   opts.cmd
 * @param {string[]} opts.args
 * @param {object}   [opts.env]
 * @returns {Promise<{exitCode: number}>}
 */
function run({ flowId, stepId, cmd, args, env = {} }) {
  if (activeProcesses.has(flowId)) {
    return Promise.reject(new Error(`Flow ${flowId} is already running`));
  }

  return new Promise((resolve, reject) => {
    const mergedEnv = {
      ...process.env,
      SMARTRETAIL_ENV: process.env.SMARTRETAIL_ENV || 'local',
      // TODO: remove this after all envs use python3
      PYTHON_CMD: process.env.PYTHON_CMD ||
        (process.platform === 'darwin'
          ? '/opt/homebrew/opt/python@3.13/bin/python3'
          : process.platform === 'win32'
            ? 'python'
            : 'python3'),
      ...env,
    };

    broadcaster.emit({
      flowId, stepId, service: 'demo-server', level: 'info',
      message: `▶ ${cmd} ${args.join(' ')}`
    });

    const child = spawn(cmd, args, { cwd: REPO_ROOT, env: mergedEnv, stdio: ['ignore', 'pipe', 'pipe'] });
    activeProcesses.set(flowId, child);

    const handleLine = (data, stream) => {
      const lines = data.toString().split('\n').filter(l => l.trim());
      for (const line of lines) {
        broadcaster.emit({
          flowId,
          stepId,
          service: inferService(line, flowId),
          level: parseLevel(line),
          message: line.trim(),
          raw: line,
        });
        if (stream === 'stderr') {
          process.stderr.write(`[${flowId}] ${line}\n`);
        }
      }
    };

    child.stdout.on('data', d => handleLine(d, 'stdout'));
    child.stderr.on('data', d => handleLine(d, 'stderr'));

    child.on('close', exitCode => {
      activeProcesses.delete(flowId);
      const level = exitCode === 0 ? 'pass' : 'fail';
      broadcaster.emit({
        flowId, stepId, service: 'demo-server', level,
        message: `Process exited with code ${exitCode}`
      });
      resolve({ exitCode: exitCode ?? 0 });
    });

    child.on('error', err => {
      activeProcesses.delete(flowId);
      broadcaster.emit({
        flowId, stepId, service: 'demo-server', level: 'fail',
        message: `Spawn error: ${err.message}`
      });
      reject(err);
    });
  });
}

function isRunning(flowId) {
  return activeProcesses.has(flowId);
}

function kill(flowId) {
  const child = activeProcesses.get(flowId);
  if (child) {
    child.kill('SIGTERM');
    activeProcesses.delete(flowId);
  }
}

// Heuristic: guess which service emitted a log line
function inferService(line, flowId) {
  const l = line.toLowerCase();
  if (l.includes('sis') || l.includes('sales')) return 'sis';
  if (l.includes('ims') || l.includes('inventory')) return 'ims';
  if (l.includes('replenishment') || l.includes(' re ') || l.includes('purchase_order')) return 're';
  if (l.includes('ars') || l.includes('aggregat')) return 'ars';
  if (l.includes('dfs') || l.includes('forecast')) return 'dfs';
  if (l.includes('supplier') || l.includes(' sup')) return 'sup';
  if (l.includes('kinesis')) return 'kinesis';
  if (l.includes('eventbridge')) return 'eventbridge';
  if (l.includes('lambda')) return 'lambda';
  return flowId || 'demo-server';
}

module.exports = { run, isRunning, kill };
