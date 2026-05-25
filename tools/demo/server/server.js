'use strict';

const express = require('express');
const cors = require('cors');
const path = require('path');

const triggerRoutes = require('./routes/trigger');
const eventsRoutes = require('./routes/events');
const dbstateRoutes = require('./routes/dbstate');
const healthRoutes = require('./routes/health');

const app = express();
const PORT = 3099;

app.use(cors({ origin: ['http://localhost:5176', 'http://127.0.0.1:5176'] }));
app.use(express.json());

app.use('/api/trigger', triggerRoutes);
app.use('/api/events', eventsRoutes);
app.use('/api/dbstate', dbstateRoutes);
app.use('/api/health', healthRoutes);

app.get('/api/env', (req, res) => {
  const env = require('./lib/envConfig');
  res.json({ env: env.ENV, mfeUrls: env.get().mfe });
});

// Bind to localhost only — never expose to network
app.listen(PORT, '127.0.0.1', () => {
  const { ENV } = require('./lib/envConfig');
  console.log(`[demo-server] listening on http://127.0.0.1:${PORT} (SMARTRETAIL_ENV=${ENV})`);
});
