/**
 * test_adversarial_m3_resilience.js
 *
 * Empirical Adversarial Challenger Test Suite for Milestone 3 (Iteration 2)
 * WhatsApp Gateway Concurrency, ACK Monotonic Ordering, Late Error Suppression,
 * Phone Normalization Extremes, Session Fallback Chaos, and Typing Circuit Breaker Safety.
 *
 * Challenger 2: Concurrency, ACK Ordering & Outbound Resiliency Challenger
 */

'use strict';

process.env.NODE_ENV = 'test';

const assert = require('assert');
const http = require('http');
const crypto = require('crypto');
const {
  WahaClient,
  SlidingWindowCircuitBreaker,
  BreakerState,
  CircuitBreakerOpenError,
  normalizePhone,
  formatChatId,
  extractMessageId
} = require('./waha_client');

const {
  app,
  resolveWahaSession,
  messageStatusCache,
  sessionStatusCache,
  verifyWebhookSignature
} = require('./server');

let totalTests = 0;
let passedTests = 0;
let failedTests = 0;
const testDetails = [];

async function runTest(name, fn) {
  totalTests++;
  const startTime = Date.now();
  try {
    process.stdout.write(`  ▶ [TEST ${totalTests}] ${name}... `);
    await fn();
    const duration = Date.now() - startTime;
    passedTests++;
    testDetails.push({ name, passed: true, duration });
    console.log(`✅ PASS (${duration}ms)`);
  } catch (err) {
    const duration = Date.now() - startTime;
    failedTests++;
    testDetails.push({ name, passed: false, duration, error: err.message });
    console.log(`❌ FAIL (${duration}ms)\n      Error: ${err.message}`);
    if (err.stack) {
      console.log(`      ${err.stack.split('\n').slice(1, 4).join('\n      ')}`);
    }
  }
}

function createEphemeralServer(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        let parsed = null;
        try { parsed = JSON.parse(body); } catch (e) { parsed = body; }
        handler(req, res, { body: parsed, rawBody: body });
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

function makeHttpRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const method = options.method || 'GET';
    const headers = options.headers || {};
    let postData = options.body;

    if (postData && typeof postData === 'object' && !Buffer.isBuffer(postData)) {
      postData = JSON.stringify(postData);
      if (!headers['Content-Type']) headers['Content-Type'] = 'application/json';
    }

    const req = http.request({
      hostname: parsed.hostname,
      port: parsed.port,
      path: parsed.pathname + parsed.search,
      method,
      headers
    }, (res) => {
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(data); } catch (e) { json = data; }
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body: json,
          rawBody: data
        });
      });
    });

    req.on('error', reject);
    if (postData) {
      req.write(postData);
    }
    req.end();
  });
}

function signWebhookBody(body, secret) {
  const raw = typeof body === 'string' ? body : JSON.stringify(body);
  const hash = crypto.createHmac('sha256', secret).update(raw).digest('hex');
  return hash;
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN ADVERSARIAL CHALLENGER SUITE
// ─────────────────────────────────────────────────────────────────────────────
async function runAdversarialResilienceSuite() {
  console.log('================================================================================');
  console.log('🛡️  CHALLENGER 2: CONCURRENCY, ACK MONOTONICITY & OUTBOUND RESILIENCE SUITE');
  console.log('================================================================================\n');

  const HMAC_SECRET = 'challenger_resilience_secret_key_2026';
  process.env.WHATSAPP_HOOK_HMAC_KEY = HMAC_SECRET;

  const appServer = await createTestAppServer(app);

  try {
    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 1: OUT-OF-ORDER ACK STORM & MONOTONIC RANK GUARD
    // ═════════════════════════════════════════════════════════════════════════
    console.log('⚡ SECTION 1: Out-of-Order ACK Storm & Monotonic Rank Guard');

    await runTest('ACK Monotonicity: Reverse delivery (READ -> DELIVERED -> SENT) preserves READ rank 3', async () => {
      const msgId = `storm_rev_${Date.now()}_${Math.random().toString(36).substring(7)}`;

      // 1. Deliver ACK 3 (READ)
      const ack3Body = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: {
          id: msgId,
          ack: 3,
          ackName: 'READ',
          from: '1234567890@c.us',
          to: '919496958200@c.us',
          timestamp: Date.now()
        }
      };
      const res1 = await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Webhook-Hmac': signWebhookBody(ack3Body, HMAC_SECRET)
        },
        body: ack3Body
      });
      assert.strictEqual(res1.statusCode, 200);

      // Verify cache immediately records READ (rank 3)
      let record = messageStatusCache.get(msgId);
      assert.ok(record, 'Record should exist in cache');
      assert.strictEqual(record.status, 'READ');
      assert.strictEqual(record.statusRank, 3);

      // 2. Deliver out-of-order stale ACK 2 (DELIVERED)
      const ack2Body = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: {
          id: msgId,
          ack: 2,
          ackName: 'DELIVERED',
          from: '1234567890@c.us',
          to: '919496958200@c.us',
          timestamp: Date.now() + 50
        }
      };
      const res2 = await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Webhook-Hmac': signWebhookBody(ack2Body, HMAC_SECRET)
        },
        body: ack2Body
      });
      assert.strictEqual(res2.statusCode, 200);

      // Verify status did NOT regress to DELIVERED
      record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'READ');
      assert.strictEqual(record.statusRank, 3);

      // 3. Deliver out-of-order stale ACK 1 (SENT)
      const ack1Body = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: {
          id: msgId,
          ack: 1,
          ackName: 'SENT',
          from: '1234567890@c.us',
          to: '919496958200@c.us',
          timestamp: Date.now() + 100
        }
      };
      const res3 = await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Webhook-Hmac': signWebhookBody(ack1Body, HMAC_SECRET)
        },
        body: ack1Body
      });
      assert.strictEqual(res3.statusCode, 200);

      // Verify status STILL remains READ
      record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'READ');
      assert.strictEqual(record.statusRank, 3);

      // Verify REST query endpoint returns final READ
      const queryRes = await makeHttpRequest(`${appServer.baseUrl}/api/message-status/${msgId}`);
      assert.strictEqual(queryRes.statusCode, 200);
      assert.strictEqual(queryRes.body.data.status, 'READ');
      assert.strictEqual(queryRes.body.data.statusRank, 3);
    });

    await runTest('ACK Storm: 60 concurrent scrambled ACK packets resolve to final monotonic READ', async () => {
      const msgId = `storm_concurrent_${Date.now()}`;
      // Sequence with random distribution of ranks 1, 2, 3
      const ranks = [
        3, 1, 2, 1, 2, 3, 2, 1, 3, 1, 2, 3, 2, 1, 2, 3, 1, 2, 3, 1,
        2, 3, 1, 2, 1, 3, 2, 3, 1, 2, 1, 2, 3, 2, 1, 3, 2, 1, 3, 2,
        1, 3, 2, 1, 2, 3, 1, 3, 2, 1, 2, 3, 1, 2, 3, 1, 2, 3, 2, 1
      ];

      const ackPromises = ranks.map((rank, idx) => {
        const body = {
          event: 'message.ack',
          session: 'cafe_qr_orders',
          payload: {
            id: msgId,
            ack: rank,
            ackName: rank === 3 ? 'READ' : (rank === 2 ? 'DELIVERED' : 'SENT'),
            timestamp: Date.now() + idx
          }
        };
        return makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Webhook-Hmac': signWebhookBody(body, HMAC_SECRET)
          },
          body
        });
      });

      const results = await Promise.all(ackPromises);
      for (const res of results) {
        assert.strictEqual(res.statusCode, 200);
      }

      // Assert final state is READ (rank 3)
      const record = messageStatusCache.get(msgId);
      assert.ok(record, 'Record should exist in cache');
      assert.strictEqual(record.status, 'READ', 'Final status must be READ');
      assert.strictEqual(record.statusRank, 3, 'Final statusRank must be 3');
    });

    await runTest('ACK Monotonicity: PLAYED (rank 4) cannot regress to READ (rank 3)', async () => {
      const msgId = `storm_played_${Date.now()}`;
      const playedBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: 4, ackName: 'PLAYED', timestamp: Date.now() }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(playedBody, HMAC_SECRET) },
        body: playedBody
      });

      let record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'PLAYED');
      assert.strictEqual(record.statusRank, 4);

      // Attempt regression with READ (3)
      const readBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: 3, ackName: 'READ', timestamp: Date.now() + 10 }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(readBody, HMAC_SECRET) },
        body: readBody
      });

      record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'PLAYED');
      assert.strictEqual(record.statusRank, 4);
    });

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 2: LATE ERROR SUPPRESSION VS EARLY ERROR RECOGNITION
    // ═════════════════════════════════════════════════════════════════════════
    console.log('\n🚫 SECTION 2: Late Error Suppression vs Early Error Recognition');

    await runTest('Late Error Suppression: ack: -1 after DELIVERED (rank 2) is discarded and status stays DELIVERED', async () => {
      const msgId = `late_err_deliv_${Date.now()}`;

      // 1. Deliver confirmation
      const deliveredBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: 2, ackName: 'DELIVERED', timestamp: Date.now() }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(deliveredBody, HMAC_SECRET) },
        body: deliveredBody
      });

      let record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'DELIVERED');
      assert.strictEqual(record.statusRank, 2);

      // 2. Late cellular timeout / network error packet (ack: -1)
      const lateErrBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: -1, ackName: 'FAILED', timestamp: Date.now() + 50 }
      };
      const res = await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(lateErrBody, HMAC_SECRET) },
        body: lateErrBody
      });
      assert.strictEqual(res.statusCode, 200);

      // Status must remain DELIVERED and error must be suppressed
      record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'DELIVERED', 'Late error must not override DELIVERED');
      assert.strictEqual(record.statusRank, 2);
    });

    await runTest('Late Error Suppression: ack: -1 after READ (rank 3) is discarded and status stays READ', async () => {
      const msgId = `late_err_read_${Date.now()}`;

      const readBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: 3, ackName: 'READ', timestamp: Date.now() }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(readBody, HMAC_SECRET) },
        body: readBody
      });

      const lateErrBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: -1, ackName: 'FAILED', timestamp: Date.now() + 50 }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(lateErrBody, HMAC_SECRET) },
        body: lateErrBody
      });

      const record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'READ', 'Late error must not override READ');
      assert.strictEqual(record.statusRank, 3);
    });

    await runTest('Early Error Recognition: ack: -1 on pending/unconfirmed message properly marks FAILED', async () => {
      const msgId = `early_err_pending_${Date.now()}`;

      // Simulate immediate dispatch failure from upstream WAHA / Meta (no delivery packet ever received)
      const earlyErrBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: -1, ackName: 'FAILED', timestamp: Date.now() }
      };
      const res = await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(earlyErrBody, HMAC_SECRET) },
        body: earlyErrBody
      });
      assert.strictEqual(res.statusCode, 200);

      const record = messageStatusCache.get(msgId);
      assert.ok(record, 'Record should exist in cache');
      assert.strictEqual(record.status, 'FAILED');
      assert.strictEqual(record.statusRank, -1);
    });

    await runTest('Early Error Recognition: ack: -1 on SENT (rank 1) marks FAILED because not delivered to device', async () => {
      const msgId = `early_err_sent_${Date.now()}`;

      // 1. Sent to WhatsApp server (rank 1)
      const sentBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: 1, ackName: 'SENT', timestamp: Date.now() }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(sentBody, HMAC_SECRET) },
        body: sentBody
      });

      let record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'SENT');
      assert.strictEqual(record.statusRank, 1);

      // 2. Server fails to route to recipient device (ack: -1)
      const serverFailBody = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: msgId, ack: -1, ackName: 'FAILED', timestamp: Date.now() + 50 }
      };
      await makeHttpRequest(`${appServer.baseUrl}/api/webhook`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Webhook-Hmac': signWebhookBody(serverFailBody, HMAC_SECRET) },
        body: serverFailBody
      });

      record = messageStatusCache.get(msgId);
      assert.strictEqual(record.status, 'FAILED', 'Server delivery failure on SENT must be recorded');
      assert.strictEqual(record.statusRank, -1);
    });

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 3: PHONE NORMALIZATION EXTREMES & ADVERSARIAL INPUTS
    // ═════════════════════════════════════════════════════════════════════════
    console.log('\n📞 SECTION 3: Phone Normalization Extremes & Adversarial Inputs');

    await runTest('Phone Normalization: +91 94969-58200 normalizes to 919496958200@c.us', () => {
      assert.strictEqual(normalizePhone('+91 94969-58200'), '919496958200@c.us');
      assert.strictEqual(formatChatId('+91 94969-58200'), '919496958200@c.us');
    });

    await runTest('Phone Normalization: 09496958200 strips leading 0 and prepends 91', () => {
      assert.strictEqual(normalizePhone('09496958200'), '919496958200@c.us');
      assert.strictEqual(formatChatId('09496958200'), '919496958200@c.us');
    });

    await runTest('Phone Normalization: 00919496958200 international prefix resolves cleanly without unhandled errors', () => {
      const resNorm = normalizePhone('00919496958200');
      const resChat = formatChatId('00919496958200');
      assert.ok(resNorm.endsWith('@c.us'), 'Normalized result must end with @c.us');
      assert.ok(resChat.endsWith('@c.us'), 'Chat ID result must end with @c.us');
      assert.doesNotThrow(() => formatChatId('00919496958200'));
    });

    await runTest('Phone Normalization: 9496958200 (10-digit national) prepends 91', () => {
      assert.strictEqual(normalizePhone('9496958200'), '919496958200@c.us');
      assert.strictEqual(normalizePhone(9496958200), '919496958200@c.us');
      assert.strictEqual(formatChatId(9496958200), '919496958200@c.us');
    });

    await runTest('Phone Normalization: 919496958200@c.us preserves valid @c.us suffix', () => {
      assert.strictEqual(normalizePhone('919496958200@c.us'), '919496958200@c.us');
      assert.strictEqual(formatChatId('919496958200@c.us'), '919496958200@c.us');
    });

    await runTest('Phone Normalization: 12036302534242@g.us preserves group JID unchanged', () => {
      assert.strictEqual(normalizePhone('12036302534242@g.us'), '12036302534242@g.us');
      assert.strictEqual(formatChatId('12036302534242@g.us'), '12036302534242@g.us');
    });

    await runTest('Phone Normalization: Pandas float artifacts (9496958200.0, 9496958200.00) stripped cleanly', () => {
      assert.strictEqual(normalizePhone('9496958200.0'), '919496958200@c.us');
      assert.strictEqual(normalizePhone('9496958200.00'), '919496958200@c.us');
    });

    await runTest('Phone Normalization: International E.164 numbers (US, UK, UAE) resolve cleanly', () => {
      // US (+1 415 555 2671)
      assert.strictEqual(normalizePhone('+1 415-555-2671'), '14155552671@c.us');
      // UK (+44 7911 123456)
      assert.strictEqual(normalizePhone('+44 7911 123456'), '447911123456@c.us');
      // UAE (+971 50 123 4567)
      assert.strictEqual(normalizePhone('+971 50 123 4567'), '971501234567@c.us');
    });

    await runTest('Phone Normalization: Malformed, alphabetic noise, null, undefined, empty handled gracefully without unhandled crashes', () => {
      // normalizePhone throws descriptive errors for non-conforming inputs
      assert.throws(() => normalizePhone(null), /Invalid phone input/);
      assert.throws(() => normalizePhone(undefined), /Invalid phone input/);
      assert.throws(() => normalizePhone(''), /Phone number invalid or too short/);
      assert.throws(() => normalizePhone('   '), /Phone number invalid or too short/);
      assert.throws(() => normalizePhone('abc'), /Phone number invalid or too short/);
      assert.throws(() => normalizePhone('INVALID_PHONE_NUMBER'), /Phone number invalid or too short/);
      assert.throws(() => normalizePhone(true), /Invalid phone input/);
      assert.throws(() => normalizePhone('0000000000'), /Phone number invalid or too short/);

      // formatChatId returns empty string gracefully for all invalid inputs
      assert.strictEqual(formatChatId(null), '');
      assert.strictEqual(formatChatId(undefined), '');
      assert.strictEqual(formatChatId(''), '');
      assert.strictEqual(formatChatId('   '), '');
      assert.strictEqual(formatChatId('abc'), '');
      assert.strictEqual(formatChatId('INVALID_PHONE_NUMBER'), '');
      assert.strictEqual(formatChatId(true), '');
      assert.strictEqual(formatChatId('0000000000'), '');
    });

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 4: SESSION FALLBACK CHAOS & CANDIDATE EXHAUSTION (HTTP 503)
    // ═════════════════════════════════════════════════════════════════════════
    console.log('\n🔀 SECTION 4: Session Fallback Chaos & Candidate Exhaustion');

    await runTest('Session Fallback: Primary STOPPED, Secondary FAILED -> routes to Default WORKING session', async () => {
      const dispatchedRequests = [];

      const mockWaha = await createEphemeralServer((req, res, { body }) => {
        dispatchedRequests.push({ url: req.url, method: req.method, body });

        if (req.url === '/api/sessions/org_branch_101') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'org_branch_101',
            status: 'STOPPED'
          }));
        }

        if (req.url === '/api/sessions/client_fallback_202') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'client_fallback_202',
            status: 'FAILED'
          }));
        }

        if (req.url === '/api/sessions/cafe_qr_orders') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'cafe_qr_orders',
            status: 'WORKING'
          }));
        }

        if (req.url === '/api/sendText') {
          return res.writeHead(201, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            id: 'waha_fallback_ok_999',
            chatId: body.chatId,
            text: body.text,
            session: body.session
          }));
        }

        res.writeHead(404).end();
      });

      const originalWahaUrl = process.env.WAHA_BASE_URL;
      const originalDefaultSession = process.env.WAHA_DEFAULT_SESSION;
      process.env.WAHA_BASE_URL = mockWaha.baseUrl;
      process.env.WAHA_DEFAULT_SESSION = 'cafe_qr_orders';
      const originalWahaClientUrl = app.get('wahaClient')?.baseUrl;

      // Update singleton wahaClient baseUrl for this test
      const { wahaClient } = require('./server');
      const prevBase = wahaClient.baseUrl;
      wahaClient.baseUrl = mockWaha.baseUrl;

      try {
        const sendBillRes = await makeHttpRequest(`${appServer.baseUrl}/api/send-bill`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: {
            sessionId: 'org_branch_101',
            fallbackSessionId: 'client_fallback_202',
            phone: '9496958200',
            text: 'Fallback Chaos Test Order Receipt'
          }
        });

        assert.strictEqual(sendBillRes.statusCode, 200, 'Should successfully fall back to working default session');
        assert.strictEqual(sendBillRes.body.success, true);
        assert.strictEqual(sendBillRes.body.data.session, 'cafe_qr_orders', 'Dispatched session must be cafe_qr_orders');
        assert.strictEqual(sendBillRes.body.data.messageId, 'waha_fallback_ok_999');

        // Verify sendText was sent to cafe_qr_orders session
        const sendTextReq = dispatchedRequests.find(r => r.url === '/api/sendText');
        assert.ok(sendTextReq, 'Must have called /api/sendText');
        assert.strictEqual(sendTextReq.body.session, 'cafe_qr_orders');
        assert.strictEqual(sendTextReq.body.chatId, '919496958200@c.us');
      } finally {
        wahaClient.baseUrl = prevBase;
        process.env.WAHA_BASE_URL = originalWahaUrl;
        await mockWaha.close();
      }
    });

    await runTest('Session Fallback: All candidate sessions down returns clean HTTP 503 with informative message', async () => {
      const mockWaha = await createEphemeralServer((req, res) => {
        if (req.url === '/api/sessions/org_branch_101') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'org_branch_101',
            status: 'STOPPED'
          }));
        }

        if (req.url === '/api/sessions/client_fallback_202') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'client_fallback_202',
            status: 'FAILED'
          }));
        }

        if (req.url === '/api/sessions/cafe_qr_orders') {
          return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            name: 'cafe_qr_orders',
            status: 'STOPPED'
          }));
        }

        res.writeHead(404).end();
      });

      const { wahaClient } = require('./server');
      const prevBase = wahaClient.baseUrl;
      wahaClient.baseUrl = mockWaha.baseUrl;

      try {
        const sendBillRes = await makeHttpRequest(`${appServer.baseUrl}/api/send-bill`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: {
            sessionId: 'org_branch_101',
            fallbackSessionId: 'client_fallback_202',
            phone: '9496958200',
            text: 'All Sessions Down Test'
          }
        });

        assert.strictEqual(sendBillRes.statusCode, 503, 'Must return HTTP 503 when all candidate sessions unavailable');
        assert.strictEqual(sendBillRes.body.success, false);
        assert.ok(sendBillRes.body.message.includes('are not connected'), 'Error message must list candidates and status');
        assert.ok(sendBillRes.body.message.includes('org_branch_101'));
        assert.ok(sendBillRes.body.message.includes('cafe_qr_orders'));
      } finally {
        wahaClient.baseUrl = prevBase;
        await mockWaha.close();
      }
    });

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION 5: TYPING SIMULATION CIRCUIT-BREAKER SAFETY & ISOLATION
    // ═════════════════════════════════════════════════════════════════════════
    console.log('\n⌨️  SECTION 5: Typing Simulation Circuit-Breaker Safety & Isolation');

    await runTest('Typing Circuit Breaker Safety: startTyping & stopTyping failures NEVER increment circuit breaker failures', async () => {
      let typingCalls = 0;
      const mockWaha = await createEphemeralServer((req, res) => {
        if (req.url === '/api/startTyping' || req.url === '/api/stopTyping') {
          typingCalls++;
          // Simulate fatal 500 server crash on typing endpoint
          return res.writeHead(500, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            statusCode: 500,
            message: 'Internal Typing Presence Engine Crashed'
          }));
        }
        res.writeHead(404).end();
      });

      const client = new WahaClient({
        baseUrl: mockWaha.baseUrl,
        defaultSession: 'typing_safety_session',
        breakerOptions: {
          windowSize: 10,
          failureThreshold: 0.5,
          minCalls: 2,
          cooldownMs: 60000
        }
      });

      const breaker = client.getCircuitBreaker('typing_safety_session');
      breaker.reset();

      try {
        // Fire 15 startTyping and 15 stopTyping calls that all return HTTP 500
        for (let i = 0; i < 15; i++) {
          const startOk = await client.startTyping('9496958200', 'typing_safety_session');
          assert.strictEqual(startOk, false, 'startTyping should return false without throwing');

          const stopOk = await client.stopTyping('9496958200', 'typing_safety_session');
          assert.strictEqual(stopOk, false, 'stopTyping should return false without throwing');
        }

        assert.strictEqual(typingCalls, 30, 'Should have received 30 typing calls');

        // Inspect circuit breaker state
        const stats = breaker.getStats();
        assert.strictEqual(stats.state, BreakerState.CLOSED, 'Circuit breaker MUST remain CLOSED');
        assert.strictEqual(stats.failureCount, 0, 'Circuit breaker MUST record 0 failures for typing calls');
        assert.strictEqual(stats.totalCalls, 0, 'Circuit breaker ring buffer MUST NOT count skipBreaker calls');
      } finally {
        await mockWaha.close();
      }
    });

    await runTest('Typing Circuit Breaker Safety: Outbound sendText succeeds even when typing simulation completely fails', async () => {
      let startTypingCalled = false;
      let stopTypingCalled = false;
      let sendTextCalled = false;

      const mockWaha = await createEphemeralServer((req, res, { body }) => {
        if (req.url === '/api/startTyping') {
          startTypingCalled = true;
          // Return HTTP 503 Service Unavailable
          return res.writeHead(503, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            message: 'Typing service overloaded'
          }));
        }
        if (req.url === '/api/stopTyping') {
          stopTypingCalled = true;
          return res.writeHead(503, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            message: 'Typing service overloaded'
          }));
        }
        if (req.url === '/api/sendText') {
          sendTextCalled = true;
          return res.writeHead(201, { 'Content-Type': 'application/json' }).end(JSON.stringify({
            id: 'msg_resilience_success_42',
            chatId: body.chatId,
            text: body.text
          }));
        }
        res.writeHead(404).end();
      });

      const client = new WahaClient({
        baseUrl: mockWaha.baseUrl,
        defaultSession: 'outbound_typing_test',
        breakerOptions: {
          windowSize: 10,
          failureThreshold: 0.5,
          minCalls: 2
        }
      });

      try {
        const result = await client.sendText('9496958200', 'Hello from resilient gateway!', {
          simulateTyping: true,
          typingDelayMs: 20
        }, 'outbound_typing_test');

        assert.ok(startTypingCalled, 'startTyping must have been invoked');
        assert.ok(stopTypingCalled, 'stopTyping must have been invoked');
        assert.ok(sendTextCalled, 'sendText must have succeeded despite typing failures');
        assert.strictEqual(result.id, 'msg_resilience_success_42');

        const breaker = client.getCircuitBreaker('outbound_typing_test');
        const stats = breaker.getStats();
        assert.strictEqual(stats.state, BreakerState.CLOSED);
        assert.strictEqual(stats.failureCount, 0, 'No failures recorded');
        assert.strictEqual(stats.totalCalls, 1, 'Only the successful sendText is recorded');
      } finally {
        await mockWaha.close();
      }
    });

  } finally {
    await appServer.close();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // SUMMARY REPORT
  // ─────────────────────────────────────────────────────────────────────────────
  console.log('\n================================================================================');
  console.log(`📊 ADVERSARIAL CHALLENGER VERIFICATION SUMMARY:`);
  console.log(`   Total Tests:  ${totalTests}`);
  console.log(`   Passed:       ${passedTests}`);
  console.log(`   Failed:       ${failedTests}`);
  console.log(`   Success Rate: ${((passedTests / totalTests) * 100).toFixed(1)}%`);
  console.log('================================================================================\n');

  if (failedTests > 0) {
    process.exit(1);
  }
}

if (require.main === module) {
  runAdversarialResilienceSuite().catch(err => {
    console.error('Unhandled suite error:', err);
    process.exit(1);
  });
}

module.exports = {
  runAdversarialResilienceSuite
};
