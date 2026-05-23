'use strict';

// Fan-out SSE broadcaster — one process stdout → all connected SSE clients.
// Usage:
//   broadcaster.addClient(res)   — register an express response as SSE client
//   broadcaster.emit(event)      — send to all connected clients
//   broadcaster.removeClient(res)

const clients = new Set();

function addClient(res) {
  clients.add(res);
}

function removeClient(res) {
  clients.delete(res);
}

/**
 * @param {object} event
 * @param {string} event.flowId
 * @param {string} event.stepId
 * @param {string} event.service
 * @param {'pass'|'fail'|'warn'|'info'|'event'} event.level
 * @param {string} event.message
 * @param {string} [event.raw]
 */
function emit(event) {
  const payload = JSON.stringify({
    id:      Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    ts:      new Date().toISOString(),
    flowId:  event.flowId  || '',
    stepId:  event.stepId  || '',
    service: event.service || 'demo-server',
    level:   event.level   || 'info',
    message: event.message || '',
    raw:     event.raw     || event.message || '',
  });

  const dead = [];
  for (const res of clients) {
    try {
      res.write(`data: ${payload}\n\n`);
    } catch {
      dead.push(res);
    }
  }
  dead.forEach(removeClient);
}

module.exports = { addClient, removeClient, emit };
