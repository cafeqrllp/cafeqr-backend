/**
 * test_adversarial_m2.js — Empirical Challenger Adversarial Stress Test Suite
 * Milestone 2: Dedicated WAHA Client & Session Lifecycle Management
 *
 * Adversarial Probes:
 * 1. High Concurrency Probe Stampede:
 *    - Force circuit breaker into HALF_OPEN.
 *    - Fire 50 concurrent requests simultaneously.
 *    - Verify exactly 1 request acquires probe permit (`isProbing`).
 *    - Verify remaining 49 requests fast-fail with `CircuitBreakerOpenError`.
 *    - Verify downstream mock server receives exactly 1 request.
 *
 * 2. Intermittent Failure Pattern vs Naive Consecutive Counters:
 *    - 15 failures and 5 successes interleaved across 20 calls (75% failure rate).
 *    - Verify that SlidingWindowCircuitBreaker trips to OPEN (failure rate >= 50%).
 *    - Verify that a naive consecutive counter would NOT have tripped.
 *
 * 3. Cold-Start Timeout Resilience:
 *    - Simulate a 30s slow response from mock WAHA during ping().
 *    - Verify ping() handles the timeout gracefully and does NOT throw unhandled rejection.
 *    - Verify ping() failures do NOT record to or trip the circuit breaker.
 *
 * 4. Broken / Corrupted QR Payload:
 *    - Non-image text payload.
 *    - Malformed JSON payload.
 *    - HTTP 400 ("Session is not in SCAN_QR_CODE status") when session is WORKING.
 *    - Verify getQrImage() recovers gracefully without throwing unhandled exceptions.
 *
 * 5. Multi-Session Breaker Isolation:
 *    - Trip circuit breaker on session 'cafe_qr_orders'.
 *    - Verify session 'cafeqr_official' circuit breaker remains CLOSED and operational.
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

let totalTests = 0;
let passedTests = 0;
let failedTests = 0;

async function runAdversarialTest(name, fn) {
  totalTests++;
  try {
    process.stdout.write(`  ⚡ [ADVERSARIAL] ${name}... `);
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

function createEphemeralMockServer(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        handler(req, res, body);
      });
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

async function runAllAdversarialProbes() {
  console.log('\n======================================================================');
  console.log('🛡️  EMPIRICAL CHALLENGER: ADVERSARIAL STRESS TEST SUITE (MILESTONE 2)');
  console.log('======================================================================\n');

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 1: High Concurrency Probe Stampede in HALF_OPEN
  // ───────────────────────────────────────────────────────────────────────────
  console.log('🔥 PROBE 1: High Concurrency Probe Stampede in HALF_OPEN (50 Concurrent Calls)');

  await runAdversarialTest('50 concurrent requests in HALF_OPEN: exactly 1 probe executes, 49 fast-fail', async () => {
    let downstreamCalls = 0;
    let inFlightProbe = false;

    // Slow downstream mock server to ensure requests are concurrent
    const mock = await createEphemeralMockServer((req, res) => {
      downstreamCalls++;
      inFlightProbe = true;
      setTimeout(() => {
        inFlightProbe = false;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'probe_success' }));
      }, 50);
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      defaultSession: 'stampede_test',
      breakerOptions: {
        windowSize: 10,
        minCalls: 2,
        failureThreshold: 0.5,
        cooldownMs: 20
      }
    });

    const breaker = client.getCircuitBreaker('stampede_test');

    // Force breaker into OPEN state
    breaker.recordFailure();
    breaker.recordFailure();
    assert.strictEqual(breaker.getState(), BreakerState.OPEN);

    // Wait for cooldown to expire into HALF_OPEN
    await new Promise(r => setTimeout(r, 30));
    assert.strictEqual(breaker.getState(), BreakerState.HALF_OPEN);

    // Fire 50 concurrent requests simultaneously
    const CONCURRENCY = 50;
    const promises = [];
    for (let i = 0; i < CONCURRENCY; i++) {
      promises.push(
        client.request('GET', '/api/probe')
          .then(res => ({ success: true, res }))
          .catch(err => ({ success: false, err }))
      );
    }

    const results = await Promise.all(promises);
    await mock.close();

    const successCount = results.filter(r => r.success).length;
    const fastFailCount = results.filter(r => !r.success && r.err instanceof CircuitBreakerOpenError).length;
    const otherErrors = results.filter(r => !r.success && !(r.err instanceof CircuitBreakerOpenError));

    assert.strictEqual(downstreamCalls, 1, `Downstream mock server MUST receive exactly 1 request (received ${downstreamCalls})`);
    assert.strictEqual(successCount, 1, `Exactly 1 request must succeed as the designated probe permit (got ${successCount})`);
    assert.strictEqual(fastFailCount, 49, `Exactly 49 requests must fast-fail with CircuitBreakerOpenError (got ${fastFailCount})`);
    assert.strictEqual(otherErrors.length, 0, `No unexpected errors should occur (got ${otherErrors.length})`);
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED, 'Breaker must transition to CLOSED following probe success');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 2: Intermittent Failure Pattern vs Naive Consecutive Counters
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔥 PROBE 2: Intermittent Failure Pattern vs Naive Consecutive Counters');

  await runAdversarialTest('15 failures + 5 successes in 20 calls (75% failure rate) trips Sliding Window to OPEN', () => {
    const breaker = new SlidingWindowCircuitBreaker({
      windowSize: 20,
      minCalls: 10,
      failureThreshold: 0.5
    });

    // Pattern: 3 failures, 1 success, 3 failures, 1 success, 3 failures, 1 success, 3 failures, 1 success, 3 failures, 1 success
    // Total calls: 20. Failures: 15. Successes: 5. Failure rate: 75%.
    // In a naive consecutive counter with threshold 5, max consecutive failures is 3, so naive counter never trips!
    let naiveConsecutiveCounter = 0;
    let naiveTripped = false;

    const pattern = [
      false, false, false, true,
      false, false, false, true,
      false, false, false, true,
      false, false, false, true,
      false, false, false, true
    ];

    for (const success of pattern) {
      if (success) {
        breaker.recordSuccess();
        naiveConsecutiveCounter = 0;
      } else {
        breaker.recordFailure();
        naiveConsecutiveCounter++;
        if (naiveConsecutiveCounter >= 5) {
          naiveTripped = true;
        }
      }
    }

    assert.strictEqual(naiveTripped, false, 'Naive consecutive counter fails to detect 75% failure rate due to interleaved successes');
    assert.strictEqual(breaker.getState(), BreakerState.OPEN, 'SlidingWindowCircuitBreaker correctly trips to OPEN on 75% failure rate');
    assert.strictEqual(breaker.allowRequest(), false, 'Breaker must block requests when OPEN');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 3: Cold-Start Timeout Resilience & Ping Breaker Isolation
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔥 PROBE 3: Cold-Start Timeout Resilience & Breaker Bypass (ping)');

  await runAdversarialTest('Simulated 30s slow mock WAHA during ping() does NOT trip circuit breaker', async () => {
    // Mock server that deliberately hangs and never responds, simulating a frozen container
    const mock = await createEphemeralMockServer((req, res) => {
      // Intentionally do not respond
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      defaultSession: 'timeout_test',
      breakerOptions: {
        minCalls: 2,
        failureThreshold: 0.5
      }
    });

    const breaker = client.getCircuitBreaker('timeout_test');
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED);

    // Call ping with a short 1s timeout to simulate timeout exhaustion
    const pingResult = await client.ping(1);
    await mock.close();

    assert.strictEqual(pingResult.ok, false);
    assert.strictEqual(pingResult.reachable, false);
    assert.strictEqual(pingResult.status, 'UNREACHABLE');

    // Verify circuit breaker state was NOT affected
    assert.strictEqual(breaker.getState(), BreakerState.CLOSED, 'Circuit breaker MUST remain CLOSED');
    assert.strictEqual(breaker.getStats().totalCalls, 0, 'Ping attempts must NOT be recorded in circuit breaker ring buffer');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 4: Corrupted / Broken QR Payload & Error Recovery
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔥 PROBE 4: Broken / Corrupted QR Payload & Graceful Recovery');

  await runAdversarialTest('Non-image text response from mock WAHA returns valid envelope without throwing', async () => {
    const mock = await createEphemeralMockServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('<html><head><title>Cloudflare Error</title></head><body>Bad Gateway</body></html>');
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('corrupt_qr_test');
    await mock.close();

    assert.ok(qrResult);
    assert.strictEqual(typeof qrResult.qr, 'string');
    assert.ok(qrResult.qr.startsWith('data:image/png;base64,'));
  });

  await runAdversarialTest('Malformed JSON response from mock WAHA does not throw JSON parse crash', async () => {
    const mock = await createEphemeralMockServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{ "malformed": true, "unclosed_string: "abc');
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('malformed_json_test');
    await mock.close();

    assert.ok(qrResult);
    assert.ok(qrResult.qr.startsWith('data:image/png;base64,'));
  });

  await runAdversarialTest('HTTP 400 from mock WAHA when already WORKING gracefully maps to CONNECTED', async () => {
    const mock = await createEphemeralMockServer((req, res) => {
      if (req.url.includes('/auth/qr')) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 400, message: 'Session is not in SCAN_QR_CODE status' }));
      } else if (req.url.includes('/api/sessions/')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING' }));
      } else {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ message: 'Not found' }));
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const qrResult = await client.getQrImage('cafe_qr_orders');
    await mock.close();

    assert.strictEqual(qrResult.qr, null);
    assert.strictEqual(qrResult.status, 'CONNECTED');
    assert.strictEqual(qrResult.session.status, 'WORKING');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 5: Multi-Session Breaker Isolation
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔥 PROBE 5: Multi-Session Circuit Breaker Isolation');

  await runAdversarialTest('Session A tripping to OPEN does not affect Session B (independent breakers)', () => {
    const client = new WahaClient({
      breakerOptions: { minCalls: 2, failureThreshold: 0.5 }
    });

    const breakerA = client.getCircuitBreaker('session_orders');
    const breakerB = client.getCircuitBreaker('session_official');

    // Trip Session A
    breakerA.recordFailure();
    breakerA.recordFailure();

    assert.strictEqual(breakerA.getState(), BreakerState.OPEN);
    assert.strictEqual(breakerA.allowRequest(), false);

    // Session B must be completely unaffected
    assert.strictEqual(breakerB.getState(), BreakerState.CLOSED);
    assert.strictEqual(breakerB.allowRequest(), true);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // PROBE 6: Jitter Bounds and Distribution
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔥 PROBE 6: Jitter Mathematical Invariants');

  await runAdversarialTest('Jitter never exceeds 30,000ms cap even on attempt 20', () => {
    const client = new WahaClient();
    for (let sample = 0; sample < 1000; sample++) {
      const delay = client.calculateJitter(20);
      assert.ok(delay >= 0 && delay <= 30000, `Delay ${delay} exceeded 30000ms cap`);
    }
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUMMARY REPORT
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n======================================================================');
  console.log(`📊 ADVERSARIAL STRESS SUITE RESULT: ${passedTests}/${totalTests} Passed (${failedTests} Failed)`);
  console.log('======================================================================\n');

  return { totalTests, passedTests, failedTests };
}

if (require.main === module) {
  runAllAdversarialProbes().catch(err => {
    console.error('Fatal adversarial runner error:', err);
    process.exit(1);
  });
}

module.exports = {
  runAllAdversarialProbes
};
