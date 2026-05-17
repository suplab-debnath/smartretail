'use strict';

const ENV = process.env.SMARTRETAIL_ENV || 'local';

const LOCAL_CONFIG = {
  services: {
    sis: 'http://localhost:8080',
    ims: 'http://localhost:8081',
    re:  'http://localhost:8082',
    ars: 'http://localhost:8083',
    dfs: 'http://localhost:8084',
    sup: 'http://localhost:8085',
  },
  mfe: {
    storeManager: 'http://localhost:5173',
    scPlanner:    'http://localhost:5174',
    executive:    'http://localhost:5175',
  },
  db: {
    host:     'localhost',
    port:     5432,
    database: 'smartretail',
    user:     'smartretail_admin',
    password: 'local_dev_password',
  },
};

// In AWS mode the caller sets env vars before starting demo-server:
//   SIS_URL, IMS_URL, RE_URL, ARS_URL, DFS_URL, SUP_URL
//   MFE_SM_URL, MFE_SCP_URL, MFE_EXEC_URL
// db is not directly accessible in AWS mode; dbstate routes fall back to ARS API.
const AWS_CONFIG = {
  services: {
    sis: process.env.SIS_URL  || '',
    ims: process.env.IMS_URL  || '',
    re:  process.env.RE_URL   || '',
    ars: process.env.ARS_URL  || '',
    dfs: process.env.DFS_URL  || '',
    sup: process.env.SUP_URL  || '',
  },
  mfe: {
    storeManager: process.env.MFE_SM_URL  || '',
    scPlanner:    process.env.MFE_SCP_URL || '',
    executive:    process.env.MFE_EXEC_URL || '',
  },
  db: null, // no direct RDS access from demo laptop in AWS mode
};

function get() {
  return ENV === 'local' ? LOCAL_CONFIG : AWS_CONFIG;
}

module.exports = { ENV, get };
