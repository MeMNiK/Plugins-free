'use strict';
// Minimal standalone example: node examples/simple-server.js
// Then POST to http://localhost:3000/give?player=Steve to queue a test order.

const http = require('http');
const { createFoolioBridge, FileOrderQueue } = require('..');

const SECRET = process.env.FOOLIO_SECRET || 'test-secret-please-change-me-123';
const queue = new FileOrderQueue(process.env.FOOLIO_QUEUE || './foolio-orders.json');

const bridge = createFoolioBridge({
  secret: SECRET,
  getPendingOrders: (server) => queue.pending(server),
  onResults: (server, results) => {
    for (const r of results) console.log(`[${server}] order ${r.id} -> ${r.status}`, r.errors);
    return queue.applyResults(server, results);
  },
});

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/api/foolio') return bridge.handler(req, res);
  if (url.pathname === '/give' && req.method === 'POST') {
    const player = url.searchParams.get('player') || 'Steve';
    const order = queue.add({
      player,
      title: 'VIP на 30 дней',
      commands: ['lp user {player} parent addtemp vip 30d', 'say {player} купил {title}!'],
    });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(order));
  }
  res.writeHead(404);
  res.end();
}).listen(process.env.PORT || 3000, () => console.log('listening on', process.env.PORT || 3000));
