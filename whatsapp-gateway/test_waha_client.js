/**
 * test_waha_client.js — Comprehensive Test Suite for WAHA Client & Gateway Integration
 *
 * Covers:
 * 1. SlidingWindowCircuitBreaker:
 *    - Initial CLOSED state
 *    - Minimum calls guard (minCalls = 10)
 *    - 50% failure threshold tripping to OPEN
 *    - Fast-fail during OPEN with CircuitBreakerOpenError
 *    - Cooldown expiry transition to HALF_OPEN
 *    - Atomic CAS probe permit: allows exactly 1 probe in HALF_OPEN, rejects concurrent calls
 *    - Probe failure trips back to OPEN with fresh cooldown
 *    - Probe success closes breaker and clears ring buffer
 *    - Reset restores pristine CLOSED state
 *
 * 2. Full Jitter Exponential Backoff & Resilience:
 *    - Mathematical bounds verification: 0 <= delay < min(30000, 1500 * 2^attempt)
 *    - Transient error detection (429, 502, 503, 504, ECONNRESET, ETIMEDOUT)
 *    - Non-transient error classification (400, 401, 404)
 *    - Retry loop execution up to maxRetries on transient errors
 *
 * 3. Cold-Start Ping Probe:
 *    - Sequential multi-endpoint probing (/api/server/version, /api/sessions, /health, /ping, /api/version, /)
 *    - Circuit breaker bypass (ping failures do not trip breaker)
 *
 * 4. Session Lifecycle Management (Mock WAHA HTTP Server):
 *    - getSession: retrieves session, maps 404 to STOPPED
 *    - startSession: starts session, handles 409/422 fallback to /start
 *    - stopSession: stops session, supports logout flag
 *    - restartSession: restarts session, clean fallback
 *    - deleteSession: deletes session
 *    - ensureSessionWorking: polling loop to WORKING state
 *    - getQrImage: handles raw PNG, JSON base64, and gracefully catches HTTP 400
 *    - getMe: extracts phone from JID
 *    - sendText & sendFile: formats chatId and dispatches
 *
 * 5. Gateway Express Route Compatibility:
 *    - GET /api/status (WORKING -> CONNECTED, SCAN_QR_CODE -> SCAN_QR, STARTING -> CONNECTING, STOPPED -> DISCONNECTED)
 *    - GET /api/qr (Already connected vs scan QR image)
 *    - POST /api/disconnect (calls stopSession(..., true))
 *    - POST /api/clear-contact-session (zero files cleared, success)
 *    - GET /health (UP, waha reachable, session cafe_qr_orders)
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

// Lightweight test runner
let totalTests = 0;
let passedTests = 0;
let failedTests = 0;

async function runTest(name, fn) {
  totalTests++;
  try {
    process.stdout.write(`  ▶ ${name}... `);
    await fn();
    passedTests++;
    console.log('✅ PASS');
  } catch (err) {
    failedTests++;
    console.log(`❌ FAIL\n      Error: ${err.message}`);
    if (err.stack) {
      console.log(`      ${err.stack.split('\n').slice(1, 4).join('\n      ')}`);
    }
  }
}

/**
 * Creates a mock HTTP server to simulate WAHA Core responses.
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
 * Creates a temporary Express server on an ephemeral port.
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

async function runAllTests() {
  console.log('\n===============================================================');
  console.log('🧪 MILESTONE 2: WAHA CLIENT & GATEWAY INTEGRATION TEST SUITE');
  console.log('===============================================================\n');

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 1: SlidingWindowCircuitBreaker
  // ───────────────────────────────────────────────────────────────────────────
  console.log('📦 SUITE 1: SlidingWindowCircuitBreaker Transitions & CAS Mutex');

  await runTest('Starts in CLOSED state with empty ring buffer', () => {
    const breaker = new SlidingWindowCircuitBreaker({ windowSize: 30, minCalls: 10, failureThreshold: 0.5 });
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED);
    assert.strictEqual(breaker.allowRequest(), true);
    const stats = breaker.getStats();
    assert.strictEqual(stats.totalCalls, 0);
    assert.strictEqual(stats.failureCount, 0);
  });

  await runTest('Does not trip to OPEN before minimum calls threshold (minCalls = 10)', () => {
    const breaker = new SlidingWindowCircuitBreaker({ windowSize: 30, minCalls: 10, failureThreshold: 0.5 });
    // Record 8 failures in a row (calls = 8 < minCalls = 10)
    for (let i = 0; i < 8; i++) {
      breaker.recordFailure();
    }
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED);
    assert.strictEqual(breaker.allowRequest(), true);
    assert.strictEqual(breaker.getStats().totalCalls, 8);
  });

  await runTest('Trips to OPEN when calls >= minCalls and failure rate >= 50%', () => {
    const breaker = new SlidingWindowCircuitBreaker({ windowSize: 30, minCalls: 10, failureThreshold: 0.5 });
    // 5 successes, then 5 failures = 50% failure rate over 10 calls
    for (let i = 0; i < 5; i++) breaker.recordSuccess();
    for (let i = 0; i < 5; i++) breaker.recordFailure();

    assert.strictEqual(breaker.getState(), BreakerState.OPEN);
    assert.strictEqual(breaker.allowRequest(), false, 'Breaker should reject calls when OPEN');
  });

  await runTest('Rejects requests in OPEN state during cooldown', () => {
    const breaker = new SlidingWindowCircuitBreaker({ cooldownMs: 5000, minCalls: 2, failureThreshold: 0.5 });
    breaker.recordFailure();
    breaker.recordFailure(); // trips
    assert.strictEqual(breaker.getState(), BreakerState.OPEN);
    assert.strictEqual(breaker.allowRequest(), false);
    assert.strictEqual(breaker.allowRequest(), false);
  });

  await runTest('Transitions to HALF_OPEN after cooldown expires', async () => {
    const breaker = new SlidingWindowCircuitBreaker({ cooldownMs: 30, minCalls: 2, failureThreshold: 0.5 });
    breaker.recordFailure();
    breaker.recordFailure(); // trips
    assert.strictEqual(breaker.getState(), BreakerState.OPEN);

    await new Promise(r => setTimeout(r, 40));
    assert.strictEqual(breaker.getState(), BreakerState.HALF_OPEN);
  });

  await runTest('Atomic CAS probe permit: allows exactly 1 probe in HALF_OPEN, blocks concurrent', async () => {
    const breaker = new SlidingWindowCircuitBreaker({ cooldownMs: 20, minCalls: 2, failureThreshold: 0.5 });
    breaker.recordFailure();
    breaker.recordFailure();
    await new Promise(r => setTimeout(r, 30));

    // First caller acquires probe permit
    const permit1 = breaker.allowRequest();
    assert.strictEqual(permit1, true, 'First caller should acquire probe permit');
    assert.strictEqual(breaker.isProbing, true);

    // Concurrent callers while probe is inflight are rejected
    const permit2 = breaker.allowRequest();
    const permit3 = breaker.allowRequest();
    assert.strictEqual(permit2, false, 'Concurrent caller 2 should be rejected');
    assert.strictEqual(permit3, false, 'Concurrent caller 3 should be rejected');
  });

  await runTest('Probe failure in HALF_OPEN trips back to OPEN with fresh cooldown', async () => {
    const breaker = new SlidingWindowCircuitBreaker({ cooldownMs: 50, minCalls: 2, failureThreshold: 0.5 });
    breaker.recordFailure();
    breaker.recordFailure();
    await new Promise(r => setTimeout(r, 60));

    assert.strictEqual(breaker.allowRequest(), true); // acquires probe permit
    breaker.recordFailure(); // probe failed!

    assert.strictEqual(breaker.getState(), BreakerState.OPEN);
    assert.strictEqual(breaker.isProbing, false);
    assert.strictEqual(breaker.allowRequest(), false);
  });

  await runTest('Probe success in HALF_OPEN closes breaker and clears ring buffer', async () => {
    const breaker = new SlidingWindowCircuitBreaker({ cooldownMs: 20, minCalls: 2, failureThreshold: 0.5 });
    breaker.recordFailure();
    breaker.recordFailure();
    await new Promise(r => setTimeout(r, 30));

    assert.strictEqual(breaker.allowRequest(), true); // acquires probe permit
    breaker.recordSuccess(); // probe passed!

    assert.strictEqual(breaker.getState(), BreakerState.CLOSED);
    assert.strictEqual(breaker.isProbing, false);
    assert.strictEqual(breaker.getStats().totalCalls, 0, 'Buffer should be reset on recovery');
    assert.strictEqual(breaker.allowRequest(), true);
  });

  await runTest('Rolling ring buffer enforces maximum windowSize (discards oldest calls)', () => {
    const breaker = new SlidingWindowCircuitBreaker({ windowSize: 5, minCalls: 5, failureThreshold: 0.6 });
    // Push 4 failures then 1 success
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordSuccess(); // history length = 5, 4 failures / 5 = 80% >= 60% -> trips
    assert.strictEqual(breaker.getState(), BreakerState.OPEN);

    breaker.reset();
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED);
    // Push 5 successes
    for (let i = 0; i < 5; i++) breaker.recordSuccess();
    assert.strictEqual(breaker.getStats().totalCalls, 5);
    // Push 1 failure -> shifts oldest success, length remains 5
    breaker.recordFailure();
    assert.strictEqual(breaker.getStats().totalCalls, 5);
    assert.strictEqual(breaker.getStats().failureCount, 1);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 2: Decorrelated Full Jitter Backoff & Error Resilience
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📦 SUITE 2: Full Jitter Backoff & Transient Error Handling');

  await runTest('calculateJitter respects mathematical formula bounds [0, min(30000, 1500 * 2^attempt)]', () => {
    const client = new WahaClient();
    for (let attempt = 0; attempt < 10; attempt++) {
      const maxCap = Math.min(30000, 1500 * Math.pow(2, attempt));
      for (let sample = 0; sample < 100; sample++) {
        const delay = client.calculateJitter(attempt);
        assert.ok(delay >= 0, `Delay ${delay} must be >= 0`);
        assert.ok(delay <= maxCap, `Delay ${delay} must be <= maxCap ${maxCap} for attempt ${attempt}`);
      }
    }
  });

  await runTest('isTransient accurately classifies HTTP codes and error messages', () => {
    const client = new WahaClient();
    // Transient HTTP status codes
    assert.strictEqual(client.isTransient(null, 429), true);
    assert.strictEqual(client.isTransient(null, 502), true);
    assert.strictEqual(client.isTransient(null, 503), true);
    assert.strictEqual(client.isTransient(null, 504), true);

    // Non-transient HTTP status codes
    assert.strictEqual(client.isTransient(null, 200), false);
    assert.strictEqual(client.isTransient(null, 400), false);
    assert.strictEqual(client.isTransient(null, 401), false);
    assert.strictEqual(client.isTransient(null, 404), false);
    assert.strictEqual(client.isTransient(null, 500), false);

    // Transient network errors
    const errReset = new Error('read ECONNRESET');
    errReset.code = 'ECONNRESET';
    assert.strictEqual(client.isTransient(errReset, null), true);

    const errTimeout = new Error('request timed out');
    errTimeout.code = 'ETIMEDOUT';
    assert.strictEqual(client.isTransient(errTimeout, null), true);

    const errFetch = new Error('fetch failed');
    assert.strictEqual(client.isTransient(errFetch, null), true);

    // Non-transient errors
    const errValidation = new Error('Validation failed');
    assert.strictEqual(client.isTransient(errValidation, null), false);
  });

  await runTest('WahaClient retries transient errors (HTTP 503) up to maxRetries then throws', async () => {
    let callCount = 0;
    const mock = await createMockWahaServer({
      'GET /api/test-transient': (req, res) => {
        callCount++;
        res.writeHead(503, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'Service Unavailable' }));
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      maxRetries: 2,
      sleepFn: () => Promise.resolve() // Fast-forward sleep
    });

    let thrown = null;
    try {
      await client.request('GET', '/api/test-transient');
    } catch (e) {
      thrown = e;
    }

    await mock.close();
    assert.ok(thrown, 'Should throw after exhausting retries');
    assert.strictEqual(callCount, 3, 'Initial call + 2 retries = 3 calls total');
  });

  await runTest('WahaClient recovers when transient error succeeds on retry', async () => {
    let callCount = 0;
    const mock = await createMockWahaServer({
      'GET /api/test-recover': (req, res) => {
        callCount++;
        if (callCount < 2) {
          res.writeHead(502, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ message: 'Bad Gateway' }));
        } else {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ status: 'recovered' }));
        }
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      maxRetries: 3,
      sleepFn: () => Promise.resolve()
    });

    const res = await client.request('GET', '/api/test-recover');
    await mock.close();

    assert.strictEqual(res.status, 200);
    assert.deepStrictEqual(res.data, { status: 'recovered' });
    assert.strictEqual(callCount, 2, 'Should succeed on second call');
  });

  await runTest('WahaClient does not retry non-transient error (HTTP 400)', async () => {
    let callCount = 0;
    const mock = await createMockWahaServer({
      'POST /api/test-bad-request': (req, res) => {
        callCount++;
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 400, message: 'Invalid payload format' }));
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      maxRetries: 3,
      sleepFn: () => Promise.resolve()
    });

    let thrown = null;
    try {
      await client.request('POST', '/api/test-bad-request');
    } catch (e) {
      thrown = e;
    }

    await mock.close();
    assert.ok(thrown);
    assert.strictEqual(thrown.status, 400);
    assert.strictEqual(callCount, 1, 'Should fail immediately without retrying');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 3: Cold-Start Ping Wake-Up Probe
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📦 SUITE 3: Cold-Start Wake-Up Probe (ping)');

  await runTest('ping probes sequential endpoints and succeeds when one returns 200', async () => {
    let probed = [];
    const mock = await createMockWahaServer({
      'GET /api/server/version': (req, res) => {
        probed.push('/api/server/version');
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'Not found' }));
      },
      'GET /api/sessions': (req, res) => {
        probed.push('/api/sessions');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify([{ name: 'cafe_qr_orders', status: 'WORKING' }]));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const pingRes = await client.ping(5);
    await mock.close();

    assert.strictEqual(pingRes.ok, true);
    assert.strictEqual(pingRes.reachable, true);
    assert.strictEqual(pingRes.endpoint, '/api/sessions');
    assert.deepStrictEqual(probed, ['/api/server/version', '/api/sessions']);
  });

  await runTest('ping probe failures do NOT trip the circuit breaker', async () => {
    const mock = await createMockWahaServer({
      DEFAULT: (req, res) => {
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'Server down' }));
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      breakerOptions: { minCalls: 2, failureThreshold: 0.5 }
    });

    const pingRes = await client.ping(1);
    await mock.close();

    assert.strictEqual(pingRes.ok, false);
    assert.strictEqual(pingRes.reachable, false);
    const breaker = client.getCircuitBreaker('cafe_qr_orders');
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED, 'Breaker must stay CLOSED after ping failure');
    assert.strictEqual(breaker.getStats().totalCalls, 0, 'Ping calls must not be recorded in breaker history');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 4: Session Lifecycle Management
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📦 SUITE 4: Session Lifecycle Operations');

  await runTest('getSession retrieves active session and maps 404 to STOPPED', async () => {
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING', engine: 'NOWEB' }));
      },
      'GET /api/sessions/unknown_session': (req, res) => {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 404, message: 'Session not found' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const session = await client.getSession('cafe_qr_orders');
    assert.strictEqual(session.name, 'cafe_qr_orders');
    assert.strictEqual(session.status, 'WORKING');

    const stopped = await client.getSession('unknown_session');
    assert.strictEqual(stopped.name, 'unknown_session');
    assert.strictEqual(stopped.status, 'STOPPED');
    assert.strictEqual(stopped.notFound, true);

    await mock.close();
  });

  await runTest('startSession posts to /api/sessions and handles existing session fallback', async () => {
    let createCalled = false;
    let startCalled = false;

    const mock = await createMockWahaServer({
      'POST /api/sessions': (req, res, ctx) => {
        createCalled = true;
        if (ctx.body && ctx.body.name === 'existing_session') {
          res.writeHead(422, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ statusCode: 422, message: 'Session already exists' }));
        } else {
          res.writeHead(201, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ name: ctx.body.name, status: 'STARTING' }));
        }
      },
      'POST /api/sessions/existing_session/start': (req, res) => {
        startCalled = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'existing_session', status: 'STARTING' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const newSession = await client.startSession('fresh_session');
    assert.strictEqual(newSession.name, 'fresh_session');
    assert.strictEqual(createCalled, true);

    const existing = await client.startSession('existing_session');
    assert.strictEqual(existing.name, 'existing_session');
    assert.strictEqual(startCalled, true);

    await mock.close();
  });

  await runTest('stopSession issues /stop or /logout based on logout parameter', async () => {
    let stopped = false;
    let loggedOut = false;

    const mock = await createMockWahaServer({
      'POST /api/sessions/cafe_qr_orders/stop': (req, res) => {
        stopped = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, status: 'STOPPED' }));
      },
      'POST /api/sessions/cafe_qr_orders/logout': (req, res) => {
        loggedOut = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, status: 'STOPPED' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    await client.stopSession('cafe_qr_orders', false);
    assert.strictEqual(stopped, true);

    await client.stopSession('cafe_qr_orders', true);
    assert.strictEqual(loggedOut, true);

    await mock.close();
  });

  await runTest('restartSession dispatches restart endpoint', async () => {
    let restarted = false;
    const mock = await createMockWahaServer({
      'POST /api/sessions/cafe_qr_orders/restart': (req, res) => {
        restarted = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'STARTING' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const res = await client.restartSession('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(restarted, true);
    assert.strictEqual(res.status, 'STARTING');
  });

  await runTest('deleteSession issues DELETE /api/sessions/{session}', async () => {
    let deleted = false;
    const mock = await createMockWahaServer({
      'DELETE /api/sessions/cafe_qr_orders': (req, res) => {
        deleted = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const res = await client.deleteSession('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(deleted, true);
    assert.strictEqual(res.success, true);
  });

  await runTest('getQrImage formats binary PNG stream into base64 data URL', async () => {
    const pngHeader = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01]);
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(pngHeader);
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(qrResult.status, 'SCAN_QR');
    assert.ok(qrResult.qr.startsWith('data:image/png;base64,'));
    assert.strictEqual(qrResult.qr, `data:image/png;base64,${pngHeader.toString('base64')}`);
  });

  await runTest('getQrImage formats JSON base64 payload into data URL', async () => {
    const fakeBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ mimetype: 'image/png', data: fakeBase64 }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(qrResult.status, 'SCAN_QR');
    assert.strictEqual(qrResult.qr, `data:image/png;base64,${fakeBase64}`);
  });

  await runTest('getQrImage formats WAHA Core JSON { base64: ... } payload into data URL', async () => {
    const fakeBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ format: 'image/png', base64: fakeBase64 }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(qrResult.status, 'SCAN_QR');
    assert.strictEqual(qrResult.qr, `data:image/png;base64,${fakeBase64}`);
  });

  await runTest('getQrImage catches HTTP 400 gracefully and maps to CONNECTED when already paired', async () => {
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 400, message: 'Session is not in SCAN_QR_CODE status' }));
      },
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(qrResult.qr, null);
    assert.strictEqual(qrResult.status, 'CONNECTED');
    assert.strictEqual(qrResult.session.status, 'WORKING');
  });

  await runTest('ensureSessionWorking polls until status reaches WORKING', async () => {
    let pollCount = 0;
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        pollCount++;
        const status = pollCount < 2 ? 'STARTING' : 'WORKING';
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status }));
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      sleepFn: () => Promise.resolve() // instant sleep in test
    });

    const session = await client.ensureSessionWorking('cafe_qr_orders', 10000);
    await mock.close();

    assert.strictEqual(session.status, 'WORKING');
    assert.strictEqual(pollCount, 2);
  });

  await runTest('getMe retrieves me data and parses phone number from JID', async () => {
    const mock = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders/me': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: '919876543210:2@s.whatsapp.net', pushName: 'Cafe Mocha Official' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const me = await client.getMe('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(me.id, '919876543210:2@s.whatsapp.net');
    assert.strictEqual(me.phone, '919876543210');
    assert.strictEqual(me.pushName, 'Cafe Mocha Official');
  });

  await runTest('sendText and sendFile format payloads with recipient@c.us and session namespace', async () => {
    let textPayload = null;
    let filePayload = null;

    const mock = await createMockWahaServer({
      'POST /api/sendText': (req, res, ctx) => {
        textPayload = ctx.body;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'msg_text_123', status: 'sent' }));
      },
      'POST /api/sendFile': (req, res, ctx) => {
        filePayload = ctx.body;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'msg_file_456', status: 'sent' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    await client.sendText('919876543210', 'Test message text');
    assert.strictEqual(textPayload.chatId, '919876543210@c.us');
    assert.strictEqual(textPayload.text, 'Test message text');
    assert.strictEqual(textPayload.session, 'cafe_qr_orders');

    await client.sendFile('+91 98765-43210', 'JVBERi0xLjQK...', 'receipt.pdf', 'application/pdf', 'Your Receipt');
    assert.strictEqual(filePayload.chatId, '919876543210@c.us');
    assert.strictEqual(filePayload.file.filename, 'receipt.pdf');
    assert.strictEqual(filePayload.file.mimetype, 'application/pdf');
    assert.strictEqual(filePayload.file.data, 'JVBERi0xLjQK...');
    assert.strictEqual(filePayload.caption, 'Your Receipt');
    assert.strictEqual(filePayload.session, 'cafe_qr_orders');

    await mock.close();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 5: Gateway Express Server Integration Tests
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📦 SUITE 5: Gateway Server Integration (server.js)');

  await runTest('resolveWahaSession maps sessions properly to dedicated namespace', () => {
    assert.strictEqual(resolveWahaSession('cafeqr_official'), 'cafeqr_official');
    assert.strictEqual(resolveWahaSession('org_branch_1'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('client_tenant_2'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession('default'), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession(null), 'cafe_qr_orders');
    assert.strictEqual(resolveWahaSession(''), 'cafe_qr_orders');
  });

  await runTest('GET /api/status returns exact contract expected by WhatsAppGatewayCard.js', async () => {
    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'cafe_qr_orders',
          status: 'WORKING',
          me: { id: '919496958200@c.us', pushName: 'Cafe Mocha POS' }
        }));
      }
    });

    // Point WAHA_BASE_URL to our mock server
    process.env.WAHA_BASE_URL = mockWaha.baseUrl;
    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;

    const gateway = await createTestAppServer(app);

    // Test GET /api/status?sessionId=org_123
    const res = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/api/status?sessionId=org_123`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.sessionId, 'org_123');
    assert.strictEqual(res.body.status, 'CONNECTED');
    assert.strictEqual(res.body.hasQr, false);
    assert.strictEqual(res.body.user.phone, '919496958200');
    assert.strictEqual(res.body.user.name, 'Cafe Mocha POS');
  });

  await runTest('GET /api/status accurately maps SCAN_QR_CODE, STARTING, and STOPPED', async () => {
    let mockStatus = 'SCAN_QR_CODE';
    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: mockStatus }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    const queryStatus = () => new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/api/status?sessionId=org_123`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve(JSON.parse(data)));
      }).on('error', reject);
    });

    // 1. SCAN_QR_CODE -> SCAN_QR with hasQr: true
    mockStatus = 'SCAN_QR_CODE';
    const resScan = await queryStatus();
    assert.strictEqual(resScan.status, 'SCAN_QR');
    assert.strictEqual(resScan.hasQr, true);
    assert.strictEqual(resScan.user, null);

    // 2. STARTING -> CONNECTING with hasQr: true
    mockStatus = 'STARTING';
    const resStarting = await queryStatus();
    assert.strictEqual(resStarting.status, 'CONNECTING');
    assert.strictEqual(resStarting.hasQr, true);

    // 3. STOPPED -> auto-starts session, returns CONNECTING with hasQr: true
    mockStatus = 'STOPPED';
    const resStopped = await queryStatus();
    assert.strictEqual(resStopped.status, 'CONNECTING');
    assert.strictEqual(resStopped.hasQr, true);

    // 4. FAILED -> auto-recovers session, returns CONNECTING with hasQr: true
    mockStatus = 'FAILED';
    const resFailed = await queryStatus();
    assert.strictEqual(resFailed.status, 'CONNECTING');
    assert.strictEqual(resFailed.hasQr, true);

    await gateway.close();
    await mockWaha.close();
  });

  await runTest('GET /api/qr returns { success: true, status: "CONNECTED", message: "Already connected" } when paired', async () => {
    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'cafe_qr_orders',
          status: 'WORKING',
          me: { id: '919496958200@c.us', pushName: 'Cafe Mocha POS' }
        }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    const res = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/api/qr?sessionId=org_123`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.status, 'CONNECTED');
    assert.strictEqual(res.body.message, 'Already connected');
    assert.strictEqual(res.body.user.phone, '919496958200');
  });

  await runTest('GET /api/qr returns QR data URL when session is SCAN_QR_CODE', async () => {
    const pngFake = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x01, 0x02]);
    const mockWaha = await createMockWahaServer({
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'SCAN_QR_CODE' }));
      },
      'GET /api/sessions/cafe_qr_orders/auth/qr': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(pngFake);
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    const res = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/api/qr?sessionId=org_123`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.status, 'SCAN_QR');
    assert.ok(res.body.qr.startsWith('data:image/png;base64,'));
  });

  await runTest('POST /api/disconnect calls stopSession with logout=true', async () => {
    let logoutCalled = false;
    const mockWaha = await createMockWahaServer({
      'POST /api/sessions/cafe_qr_orders/logout': (req, res) => {
        logoutCalled = true;
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

    const res = await new Promise((resolve, reject) => {
      const postReq = http.request(`${gateway.baseUrl}/api/disconnect`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      }, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      });
      postReq.on('error', reject);
      postReq.write(JSON.stringify({ sessionId: 'org_123' }));
      postReq.end();
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.message, 'Disconnected');
    assert.strictEqual(logoutCalled, true);
  });

  await runTest('POST /api/clear-contact-session returns clearedFiles: 0 and contract message', async () => {
    const gateway = await createTestAppServer(app);

    const res = await new Promise((resolve, reject) => {
      const postReq = http.request(`${gateway.baseUrl}/api/clear-contact-session`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      }, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      });
      postReq.on('error', reject);
      postReq.write(JSON.stringify({ sessionId: 'org_123', phone: '9496958200' }));
      postReq.end();
    });

    await gateway.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.clearedFiles, 0);
    assert.ok(res.body.message.includes('WAHA Core'));
  });

  await runTest('GET /health returns status UP with waha health object and sessions inventory', async () => {
    const mockWaha = await createMockWahaServer({
      'GET /api/server/version': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ version: '2026.10.1', engine: 'NOWEB' }));
      },
      'GET /api/sessions/cafe_qr_orders': (req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          name: 'cafe_qr_orders',
          status: 'WORKING',
          me: { id: '919496958200@c.us' }
        }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;
    const gateway = await createTestAppServer(app);

    const res = await new Promise((resolve, reject) => {
      http.get(`${gateway.baseUrl}/health`, (resp) => {
        let data = '';
        resp.on('data', chunk => { data += chunk; });
        resp.on('end', () => resolve({ status: resp.statusCode, body: JSON.parse(data) }));
      }).on('error', reject);
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.status, 200);
    assert.strictEqual(res.body.status, 'UP');
    assert.strictEqual(res.body.totalSessions, 1);
    assert.strictEqual(res.body.waha.reachable, true);
    assert.strictEqual(res.body.waha.status, 'WORKING');
    assert.strictEqual(res.body.waha.session, 'cafe_qr_orders');
    assert.ok(typeof res.body.uptime === 'number');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUMMARY REPORT
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n===============================================================');
  console.log(`📊 TEST SUITE COMPLETE: ${passedTests}/${totalTests} Passed (${failedTests} Failed)`);
  console.log('===============================================================\n');

  if (failedTests > 0) {
    process.exit(1);
  }
}

// Execute tests if run directly
if (require.main === module) {
  runAllTests().catch((err) => {
    console.error('Fatal error running test suite:', err);
    process.exit(1);
  });
}

module.exports = {
  runAllTests
};
