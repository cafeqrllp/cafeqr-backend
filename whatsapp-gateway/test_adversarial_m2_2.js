/**
 * test_adversarial_m2_2.js — Adversarial Stress Test Suite for Milestone 2
 *
 * Authored by: Challenger 2 (Empirical Challenger)
 * Roles: critic, specialist
 *
 * Probes:
 * 1. Dedicated session isolation & circuit breaker cross-session isolation
 * 2. Gateway endpoint contract fuzzing & error resilience (GET /api/status, GET /api/qr, POST /api/disconnect, GET /health)
 * 3. Decorrelated Full Jitter mathematical bounds and randomness across 1,000 iterations
 */

'use strict';

const assert = require('assert');
const http = require('http');
const {
  WahaClient,
  SlidingWindowCircuitBreaker,
  BreakerState,
  CircuitBreakerOpenError
} = require('./waha_client');
const { app, resolveWahaSession } = require('./server');

let testCount = 0;
let passedCount = 0;
let failedCount = 0;

async function runAdversarialTest(name, fn) {
  testCount++;
  try {
    process.stdout.write(`  ⚡ [ADVERSARIAL] ${name}... `);
    await fn();
    passedCount++;
    console.log('✅ PASS');
  } catch (err) {
    failedCount++;
    console.log(`❌ FAIL\n      Error: ${err.message}`);
    if (err.stack) {
      console.log(`      ${err.stack.split('\n').slice(1, 4).join('\n      ')}`);
    }
  }
}

/**
 * Creates ephemeral mock WAHA server.
 */
function createMockWahaServer(routes) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const path = url.pathname;
      const method = req.method;
      const key = `${method} ${path}`;

      const handler = routes[key] || routes[`ANY ${path}`] || routes['DEFAULT'];
      if (handler) {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
          let parsedBody = null;
          try { parsedBody = JSON.parse(body); } catch (e) { parsedBody = body; }
          handler(req, res, { query: Object.fromEntries(url.searchParams), body: parsedBody });
        });
      } else {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 404, message: `Route not mocked: ${key}` }));
      }
    });

    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({
        server,
        port,
        baseUrl: `http://127.0.0.1:${port}`,
        close: () => new Promise(cb => server.close(cb))
      });
    });
  });
}

/**
 * Creates ephemeral Express test server.
 */
function createTestAppServer(expressApp) {
  return new Promise((resolve) => {
    const server = expressApp.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({
        server,
        port,
        baseUrl: `http://127.0.0.1:${port}`,
        close: () => new Promise(cb => server.close(cb))
      });
    });
  });
}

async function executeAdversarialSuite() {
  console.log('\n===============================================================');
  console.log('🔥 CHALLENGER 2: ADVERSARIAL STRESS & FUZZING TEST SUITE');
  console.log('===============================================================\n');

  // ───────────────────────────────────────────────────────────────────────────
  // PART 1: Dedicated Session Isolation & Circuit Breaker Independence
  // ───────────────────────────────────────────────────────────────────────────
  console.log('🛡️ PART 1: Multi-Tenant Session Isolation & Circuit Breaker Independence');

  await runAdversarialTest('resolveWahaSession adversarial fuzzing with injection and edge cases', () => {
    // 1. Standard cases
    assert.strictEqual(resolveWahaSession('cafeqr_official'), 'cafeqr_official');
    assert.strictEqual(resolveWahaSession('  cafeqr_official  '), 'cafeqr_official');
    assert.strictEqual(resolveWahaSession('client_123'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('org_abc'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('default'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession(''), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('   '), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession(null), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession(undefined), 'cafe_qr_orders');

    // 2. Adversarial / Injection cases
    assert.strictEqual(resolveWahaSession('; DROP TABLE sessions; --'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('../../etc/passwd'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('{"json":"injection"}'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('<script>alert(1)</script>'), 'cafe_qr_orders');

    // 3. Multi-session mode toggle verification
    const origMulti = process.env.WAHA_MULTI_SESSION;
    try {
      process.env.WAHA_MULTI_SESSION = 'true';
      assert.strictEqual(resolveWahaSession('branch-42'), 'branch-42');
      assert.strictEqual(resolveWahaSession('org:101/pos'), 'org_101_pos'); // sanitizes non-alphanumeric
      assert.strictEqual(resolveWahaSession('cafeqr_official'), 'cafeqr_official'); // official preserved
    } finally {
      process.env.WAHA_MULTI_SESSION = origMulti;
    }
  });

  await runAdversarialTest('Circuit Breaker isolation: Tripping session A does NOT affect session B', async () => {
    const client = new WahaClient({
      breakerOptions: {
        minCalls: 4,
        failureThreshold: 0.5,
        cooldownMs: 60000
      }
    });

    const ordersBreaker = client.getCircuitBreaker('cafe_qr_orders');
    const officialBreaker = client.getCircuitBreaker('cafeqr_official');
    const tenantBreaker = client.getCircuitBreaker('tenant_branch_99');

    // Trip cafe_qr_orders by recording 4 failures
    for (let i = 0; i < 4; i++) {
      ordersBreaker.recordFailure();
    }

    // Assert ordersBreaker is OPEN and rejects calls
    assert.strictEqual(ordersBreaker.getState(), BreakerState.OPEN);
    assert.strictEqual(ordersBreaker.allowRequest(), false);

    // Assert officialBreaker and tenantBreaker are still CLOSED and allow requests
    assert.strictEqual(officialBreaker.getState(), BreakerState.CLOSED);
    assert.strictEqual(officialBreaker.allowRequest(), true);

    assert.strictEqual(tenantBreaker.getState(), BreakerState.CLOSED);
    assert.strictEqual(tenantBreaker.allowRequest(), true);

    // Record success on officialBreaker
    officialBreaker.recordSuccess();
    assert.strictEqual(officialBreaker.getState(), BreakerState.CLOSED);

    // ordersBreaker must remain strictly OPEN
    assert.strictEqual(ordersBreaker.getState(), BreakerState.OPEN);
    assert.strictEqual(ordersBreaker.allowRequest(), false);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PART 2: Gateway Endpoint Contract Fuzzing
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🛡️ PART 2: Gateway Endpoint Contract Fuzzing & Error Resilience');

  await runAdversarialTest('GET /api/status fuzzing: handles WAHA 500 error gracefully without crashing', async () => {
    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 500, message: 'Internal Server Error: DB down' }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    // Fuzz with weird and nested query parameters
    const queryUrls = [
      `${gateway.baseUrl}/api/status?sessionId=`,
      `${gateway.baseUrl}/api/status?sessionId=org_weird&unrelated=123`,
      `${gateway.baseUrl}/api/status?sessionId=;DROP%20TABLE`,
      `${gateway.baseUrl}/api/status`
    ];

    for (const url of queryUrls) {
      const res = await new Promise((resolve, reject) => {
        http.get(url, (resp) => {
          let data = '';
          resp.on('data', chunk => { data += chunk; });
          resp.on('end', () => resolve({ statusCode: resp.statusCode, body: JSON.parse(data) }));
        }).on('error', reject);
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
      assert.strictEqual(res.body.status, 'DISCONNECTED');
      assert.strictEqual(res.body.hasQr, false);
      assert.strictEqual(res.body.user, null);
    }

    await gateway.close();
    await mockWaha.close();
  });

  await runAdversarialTest('GET /api/qr across all 4 WAHA session states (WORKING, SCAN_QR_CODE, STARTING, STOPPED)', async () => {
    let currentWahaState = 'STOPPED';
    let qrRequested = false;
    let startRequested = false;

    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'cafe_qr_orders',
          status: currentWahaState,
          me: currentWahaState === 'WORKING' ? { id: '919496958200@c.us', pushName: 'Cafe Branch' } : null
        }));
      },
      'POST /api/sessions': (req, res) => {
        startRequested = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'STARTING' }));
      },
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        qrRequested = true;
        if (currentWahaState === 'SCAN_QR_CODE') {
          res.writeHead(200, { 'Content-Type': 'image/png' });
          res.end(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x01]));
        } else {
          // WAHA returns 400 when not in SCAN_QR_CODE
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ statusCode: 400, message: 'Session is not in SCAN_QR_CODE status' }));
        }
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    const callGetQr = () => new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/api/qr?sessionId=cafe_qr_orders`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ statusCode: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    // 1. WORKING State
    currentWahaState = 'WORKING';
    qrRequested = false;
    const resWorking = await callGetQr();
    assert.strictEqual(resWorking.statusCode, 200);
    assert.strictEqual(resWorking.body.status, 'CONNECTED');
    assert.strictEqual(resWorking.body.message, 'Already connected');
    assert.strictEqual(qrRequested, false, 'Should not request /auth/qr when WORKING');

    // 2. SCAN_QR_CODE State
    currentWahaState = 'SCAN_QR_CODE';
    qrRequested = false;
    const resScan = await callGetQr();
    assert.strictEqual(resScan.statusCode, 200);
    assert.strictEqual(resScan.body.status, 'SCAN_QR');
    assert.ok(resScan.body.qr.startsWith('data:image/png;base64,'));
    assert.strictEqual(qrRequested, true);

    // 3. STARTING State (WAHA returns 400 on /auth/qr)
    currentWahaState = 'STARTING';
    const resStarting = await callGetQr();
    assert.strictEqual(resStarting.statusCode, 200);
    assert.strictEqual(resStarting.body.status, 'CONNECTING');
    assert.strictEqual(resStarting.body.qr, null);

    // 4. STOPPED State (Auto-starts session)
    currentWahaState = 'STOPPED';
    startRequested = false;
    const resStopped = await callGetQr();
    assert.strictEqual(resStopped.statusCode, 200);
    assert.strictEqual(startRequested, true, 'Auto-start should be triggered when STOPPED');

    await gateway.close();
    await mockWaha.close();
  });

  await runAdversarialTest('POST /api/disconnect repeated calls do not leak sockets or crash Express', async () => {
    let disconnectCount = 0;
    const mockWaha = await createMockWahaServer({
      'POST /api/sessions/cafe_qr_orders/logout': (req, res) => {
        disconnectCount++;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, status: 'STOPPED' }));
      },
      'POST /api/sessions': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, status: 'STARTING' }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    // Dispatch 5 rapid sequential disconnect requests
    for (let i = 0; i < 5; i++) {
      const res = await new Promise((resolve, reject) => {
        const postReq = http.request(`${gateway.baseUrl}/api/disconnect`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' }
        }, (resp) => {
          let data = '';
          resp.on('data', chunk => { data += chunk; });
          resp.on('end', () => resolve({ statusCode: resp.statusCode, body: JSON.parse(data) }));
        });
        postReq.on('error', reject);
        postReq.write(JSON.stringify({ sessionId: 'cafe_qr_orders' }));
        postReq.end();
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
      assert.strictEqual(res.body.message, 'Disconnected');
    }

    assert.strictEqual(disconnectCount, 5);

    await gateway.close();
    await mockWaha.close();
  });

  await runAdversarialTest('GET /health contract verification when WAHA is DOWN vs UP', async () => {
    // Phase A: WAHA is DOWN (simulated with 500 error on ping and getSession)
    const mockDown = await createMockWahaServer({
      DEFAULT: (req, res) => {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'Service unavailable' }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockDown.baseUrl;
    const gateway = await createTestAppServer(app);

    const resDown = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/health`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ statusCode: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    assert.strictEqual(resDown.statusCode, 200);
    assert.strictEqual(resDown.body.status, 'UP', 'Gateway process itself is UP');
    assert.strictEqual(resDown.body.waha.reachable, false, 'WAHA reachable should be false');
    assert.strictEqual(resDown.body.sessions[0].status, 'DISCONNECTED');

    await mockDown.close();

    // Phase B: WAHA is UP
    const mockUp = await createMockWahaServer({
      'GET /api/server/version': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ version: '2026.10.1', engine: 'NOWEB' }));
      },
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'cafe_qr_orders',
          status: 'WORKING',
          me: { id: '919876543210@c.us' }
        }));
      }
    });

    serverWahaClient.baseUrl = mockUp.baseUrl;

    const resUp = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/health`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ statusCode: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    assert.strictEqual(resUp.statusCode, 200);
    assert.strictEqual(resUp.body.status, 'UP');
    assert.strictEqual(resUp.body.waha.reachable, true);
    assert.strictEqual(resUp.body.waha.status, 'WORKING');
    assert.strictEqual(resUp.body.sessions[0].status, 'CONNECTED');
    assert.strictEqual(resUp.body.sessions[0].phone, '919876543210');

    await gateway.close();
    await mockUp.close();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PART 3: Decorrelated Full Jitter 1,000-Iteration Statistical Rigor
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🛡️ PART 3: Decorrelated Full Jitter 1,000-Iteration Statistical Rigor');

  await runAdversarialTest('Decorrelated Full Jitter bounds and uniform randomness across 1,000 iterations', () => {
    const client = new WahaClient();
    const ITERATIONS = 1000;

    for (let attempt = 0; attempt <= 6; attempt++) {
      const cap = Math.min(30000, 1500 * Math.pow(2, attempt));
      const samples = [];

      for (let i = 0; i < ITERATIONS; i++) {
        const delay = client.calculateJitter(attempt);
        // Absolute bound checks
        assert.ok(delay >= 0, `Sample ${delay} must be >= 0`);
        assert.ok(delay <= cap, `Sample ${delay} must be <= cap ${cap} for attempt ${attempt}`);
        assert.ok(Number.isInteger(delay), `Sample ${delay} must be an integer`);
        samples.push(delay);
      }

      // Statistical distribution checks
      const sum = samples.reduce((a, b) => a + b, 0);
      const mean = sum / ITERATIONS;
      const min = Math.min(...samples);
      const max = Math.max(...samples);

      // Expected uniform mean is cap / 2. Allow reasonable statistical tolerance (+- 10% of cap)
      const expectedMean = cap / 2;
      const tolerance = cap * 0.12;

      assert.ok(
        Math.abs(mean - expectedMean) < tolerance,
        `Mean ${mean} for attempt ${attempt} outside tolerance around ${expectedMean} (tolerance ${tolerance})`
      );

      // Spread checks: min should be close to 0, max close to cap
      assert.ok(min < cap * 0.05, `Min ${min} should be close to 0 (cap=${cap})`);
      assert.ok(max > cap * 0.95, `Max ${max} should be close to cap (cap=${cap})`);

      // Variance check: ensures samples are not all identical
      const variance = samples.reduce((acc, val) => acc + Math.pow(val - mean, 2), 0) / ITERATIONS;
      const stdDev = Math.sqrt(variance);
      assert.ok(stdDev > (cap * 0.2), `Standard deviation ${stdDev} indicates non-random distribution`);
    }
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUMMARY REPORT
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n===============================================================');
  console.log(`📊 ADVERSARIAL SUITE COMPLETE: ${passedCount}/${testCount} Passed (${failedCount} Failed)`);
  console.log('===============================================================\n');

  if (failedCount > 0) {
    process.exit(1);
  }
}

if (require.main === module) {
  executeAdversarialSuite().catch(err => {
    console.error('Fatal error in adversarial suite:', err);
    process.exit(1);
  });
}

module.exports = { executeAdversarialSuite };
