'use strict';
// Express integration. The bridge route must see the RAW body to check the signature.

const express = require('express');
const { createFoolioBridge } = require('foolio-bridge');

const app = express();

const bridge = createFoolioBridge({
  secret: process.env.FOOLIO_SECRET,
  allowedServers: ['survival'],
  // Return orders that are paid but not yet delivered to this server.
  getPendingOrders: async (server) => {
    // Example with any DB:
    // return db.orders.find({ status: 'paid', server });
    return [];
  },
  // Save the results. Return the ids you saved.
  onResults: async (server, results) => {
    // for (const r of results) await db.orders.update(r.id, { status: r.status, errors: r.errors });
    return results.map((r) => r.id);
  },
});

// Option A: register the route BEFORE express.json()
app.post('/api/foolio', bridge.handler);
app.use(express.json());

// Option B (if express.json() must be first): keep the raw body
// app.use(express.json({ verify: (req, res, buf) => { req.rawBody = buf; } }));
// app.post('/api/foolio', bridge.handler);

app.listen(3000);
