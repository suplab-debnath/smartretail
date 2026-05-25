'use strict';

const { Pool } = require('pg');
const { ENV, get: getEnv } = require('./envConfig');

let pool = null;

function getPool() {
  if (ENV !== 'local') return null;
  if (!pool) {
    const { db } = getEnv();
    pool = new Pool(db);
    pool.on('error', err => {
      console.error('[dbClient] unexpected error on idle client', err.message);
    });
  }
  return pool;
}

/** Run a parameterized read-only query. Returns rows array. */
async function query(sql, params = []) {
  const p = getPool();
  if (!p) throw new Error('Direct DB access not available in AWS mode');
  const result = await p.query(sql, params);
  return result.rows;
}

module.exports = { query };
