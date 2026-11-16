/**
 * test_adversarial_m3_security.js
 * 
 * Milestone 3 (Iteration 2) Adversarial Security & HMAC Stress Test Suite.
 * EMPIRICAL CHALLENGER 1: Adversarial Security & HMAC Stress Tester.
 * 
 * Comprehensive Test Coverage:
 * 1. HMAC Forgery & Tampering (bit-flips, length mismatches, bad secrets, headers)
 * 2. Unicode & Wire Invariance (Indic scripts, Rupee symbol, emojis, JSON whitespace)
 * 3. Replay Attack & Timestamp Windows (past, future, timestamp-bound digests, malformed)
 * 4. Timing-Attack Resistance (timingSafeEqual verification, length guards, constant-time)
 * 5. Malformed Webhook Payloads & Fuzzing (nulls, missing fields, invalid acks, 1MB payload, syntax error)
 */

'use strict';

const assert = require('assert');
const http = require('http');
const crypto = require('crypto');
const {
  app,
  verifyWebhookSignature,
  messageStatusCache
} = require('./server');

const TEST_SECRET = 'adversarial-hmac-test-key-2026-xyz!#$';
process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
process.env.NODE_ENV = 'test';

let totalTests = 0;
let passedTests = 0;
let failedTests = 0;
const testResults = [];

async function runTest(category, name, fn) {
  totalTests++;
  const label = `[${category}] ${name}`;
  try {
    process.stdout.write(`  ▶ ${label}... `);
    await fn();
    passedTests++;
    testResults.push({ category, name, status: 'PASS' });
    console.log('✅ PASS');
  } catch (err) {
    failedTests++;
    testResults.push({ category, name, status: 'FAIL', error: err.message });
    console.log(`❌ FAIL\n      Error: ${err.message}`);
    if (err.stack) {
      console.log(`      ${err.stack.split('\n').slice(1, 4).join('\n      ')}`);
    }
  }
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

function sendRawHttpRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const method = options.method || 'POST';
    const headers = options.headers || {};
    let postData = options.body;

    if (postData && typeof postData === 'string' && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    } else if (Buffer.isBuffer(postData) && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    }

    if (postData) {
      headers['Content-Length'] = Buffer.isBuffer(postData) ? postData.length : Buffer.byteLength(postData, 'utf8');
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

function computeHmac(secret, buffer) {
  return crypto.createHmac('sha256', secret).update(buffer).digest('hex');
}

async function runAdversarialSecuritySuite() {
  console.log('\n================================================================');
  console.log('⚡ STARTING ADVERSARIAL SECURITY & HMAC STRESS TEST SUITE ⚡');
  console.log('================================================================\n');

  const appServer = await createTestAppServer(app);
  const webhookUrl = `${appServer.baseUrl}/api/webhook`;

  try {
    // ══════════════════════════════════════════════════════════════════════════
    // SUITE 1: HMAC FORGERY & TAMPERING STRESS TESTING
    // ══════════════════════════════════════════════════════════════════════════
    console.log('--- Suite 1: HMAC Forgery & Tampering ---');

    await runTest('HMAC', 'Valid baseline HMAC digest receives 200 OK', async () => {
      const rawWireBody = JSON.stringify({
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: 'msg_hmac_valid_1', ack: 1, ackName: 'SENT' }
      });
      const validSig = computeHmac(TEST_SECRET, Buffer.from(rawWireBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': validSig,
          'Content-Type': 'application/json'
        },
        body: rawWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('HMAC', 'Single bit-flip in body buffer is rejected (401 DIGEST_MISMATCH)', async () => {
      const originalObj = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: { id: 'msg_hmac_tamper_1', ack: 1 }
      };
      const originalRaw = Buffer.from(JSON.stringify(originalObj), 'utf8');
      const validSig = computeHmac(TEST_SECRET, originalRaw);

      // Flip 1 bit in the raw buffer (change first byte)
      const tamperedBuf = Buffer.from(originalRaw);
      tamperedBuf[10] = tamperedBuf[10] ^ 0x01; // flip 1 bit

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': validSig,
          'Content-Type': 'application/json'
        },
        body: tamperedBuf
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.success, false);
      assert.strictEqual(res.body.reason, 'DIGEST_MISMATCH');
    });

    await runTest('HMAC', 'Signature length mismatch (< 64 chars) is rejected (401 INVALID_DIGEST_LENGTH)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_len_short' } });
      const truncatedSig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8')).slice(0, 63); // 63 chars

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': truncatedSig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'INVALID_DIGEST_LENGTH');
    });

    await runTest('HMAC', 'Signature length mismatch (> 64 chars) is rejected (401 INVALID_DIGEST_LENGTH)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_len_long' } });
      const extendedSig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8')) + 'ab'; // 66 chars

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': extendedSig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'INVALID_DIGEST_LENGTH');
    });

    await runTest('HMAC', 'Empty signature header value is rejected (401 INVALID_DIGEST_LENGTH / MISSING)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_empty_sig' } });

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': '',
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.success, false);
    });

    await runTest('HMAC', 'Wrong HMAC secret signature is rejected (401 DIGEST_MISMATCH)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_wrong_secret' } });
      const badSecretSig = computeHmac('attacker-known-secret-bad-key', Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': badSecretSig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'DIGEST_MISMATCH');
    });

    await runTest('HMAC', 'Missing signature header is rejected (401 MISSING_SIGNATURE_HEADER)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_missing_header' } });

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'MISSING_SIGNATURE_HEADER');
    });

    await runTest('HMAC', 'Signature with 64 invalid non-hex characters is rejected (401 DIGEST_MISMATCH)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_non_hex' } });
      const nonHexSig = 'z'.repeat(64);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': nonHexSig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'DIGEST_MISMATCH');
    });

    await runTest('HMAC', 'Alternative header x-waha-hmac-sha256 is accepted (200 OK)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_alt_header_1', ack: 1 } });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-waha-hmac-sha256': sig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('HMAC', 'Alternative header x-hub-signature-256 with sha256= prefix is accepted (200 OK)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_alt_header_2', ack: 1 } });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-hub-signature-256': `sha256=${sig}`,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('HMAC', 'Secret precedence: WHATSAPP_HOOK_HMAC_KEY over WAHA_WEBHOOK_SECRET', async () => {
      // Test unit-level verification
      const bodyBuf = Buffer.from('{"test":"precedence"}', 'utf8');
      const sig1 = computeHmac('primary-secret', bodyBuf);
      const sig2 = computeHmac('fallback-secret', bodyBuf);

      const saved1 = process.env.WHATSAPP_HOOK_HMAC_KEY;
      const saved2 = process.env.WAHA_WEBHOOK_SECRET;

      process.env.WHATSAPP_HOOK_HMAC_KEY = 'primary-secret';
      process.env.WAHA_WEBHOOK_SECRET = 'fallback-secret';

      const reqMock1 = {
        headers: { 'x-webhook-hmac': sig1 },
        rawBody: bodyBuf
      };
      const reqMock2 = {
        headers: { 'x-webhook-hmac': sig2 },
        rawBody: bodyBuf
      };

      const res1 = verifyWebhookSignature(reqMock1);
      const res2 = verifyWebhookSignature(reqMock2);

      assert.strictEqual(res1.valid, true, 'Primary secret should validate');
      assert.strictEqual(res2.valid, false, 'Fallback secret must not take precedence when primary is present');

      // Restore
      process.env.WHATSAPP_HOOK_HMAC_KEY = saved1;
      process.env.WAHA_WEBHOOK_SECRET = saved2;
    });

    // ══════════════════════════════════════════════════════════════════════════
    // SUITE 2: UNICODE & WIRE INVARIANCE TESTING
    // ══════════════════════════════════════════════════════════════════════════
    console.log('\n--- Suite 2: Unicode & Wire Invariance ---');

    await runTest('Unicode', 'Multi-byte Indic scripts (Hindi, Kannada, Malayalam, Tamil) validate rawBody cleanly', async () => {
      const indicPayload = {
        event: 'message',
        session: 'cafe_qr_orders',
        payload: {
          id: 'msg_indic_1',
          hindi: 'नमस्ते ক্যাফে অর্ডার',
          kannada: 'ಕೆಫೆ ಕ್ಯೂಆರ್ ಆರ್ಡರ್ ವಿವರಗಳು',
          malayalam: 'കഫേ ഓർഡർ വിജയകരമായി പൂർത്തിയായി',
          tamil: 'காபி க்யூஆர் கட்டணம் செலுத்தப்பட்டது'
        }
      };
      const rawWireBody = Buffer.from(JSON.stringify(indicPayload), 'utf8');
      const sig = computeHmac(TEST_SECRET, rawWireBody);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json; charset=utf-8'
        },
        body: rawWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Unicode', 'Indian Rupee symbol (₹ U+20B9, 3-byte UTF-8) maintains buffer invariance', async () => {
      const rupeePayload = {
        event: 'message',
        session: 'cafe_qr_orders',
        payload: {
          id: 'msg_rupee_bill_1',
          billAmount: '₹ 450.75',
          description: 'South Indian Filter Coffee + Dosa Combo: ₹120.00'
        }
      };
      const rawWireBody = Buffer.from(JSON.stringify(rupeePayload), 'utf8');
      const sig = computeHmac(TEST_SECRET, rawWireBody);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Unicode', 'Multi-byte Emojis (🧾, ☕, 🍕, 🔥, surrogate pairs) validate cleanly', async () => {
      const emojiPayload = {
        event: 'message',
        session: 'cafe_qr_orders',
        payload: {
          id: 'msg_emoji_1',
          header: '🧾 *Cafe QR Bill* ☕',
          items: '🍕 Margherita Pizza x2 🔥 Hot & Fresh',
          footer: '🙏 Thank you for dining with us! ✨🎉'
        }
      };
      const rawWireBody = Buffer.from(JSON.stringify(emojiPayload), 'utf8');
      const sig = computeHmac(TEST_SECRET, rawWireBody);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Unicode', 'Whitespace & JSON formatting invariance (pretty-printed 4-space JSON)', async () => {
      const payloadObj = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: {
          id: 'msg_pretty_json_1',
          ack: 2,
          ackName: 'DELIVERED',
          notes: 'Testing rawBody capture with extensive formatting'
        }
      };
      // Pretty printed with spaces, tabs, and newlines
      const prettyWireBody = Buffer.from(JSON.stringify(payloadObj, null, 4) + '\n\n   \n', 'utf8');
      const sig = computeHmac(TEST_SECRET, prettyWireBody);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: prettyWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Unicode', 'Compact minified JSON with escaped slashes and unicode escapes', async () => {
      const rawWireBody = Buffer.from('{"event":"message.ack","session":"cafe_qr_orders","payload":{"id":"msg\\/minified\\/1","ack":1,"text":"\\u20B9 100"}}', 'utf8');
      const sig = computeHmac(TEST_SECRET, rawWireBody);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawWireBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Unicode', 'Deserialization/Re-serialization Trap: Confirms rawBody preserves exact wire bytes', async () => {
      // If server mistakenly used JSON.stringify(req.body), this formatted payload would fail!
      const formattedJson = '{\n  "event": "message.ack",\n  "session": "cafe_qr_orders",\n  "payload": {\n    "id": "msg_trap_test",\n    "ack": 1\n  }\n}';
      const wireBuf = Buffer.from(formattedJson, 'utf8');
      const wireSig = computeHmac(TEST_SECRET, wireBuf);

      // Verify re-serialization produces a DIFFERENT hash
      const reSerializedBuf = Buffer.from(JSON.stringify(JSON.parse(formattedJson)), 'utf8');
      const reSerializedSig = computeHmac(TEST_SECRET, reSerializedBuf);
      assert.notStrictEqual(wireSig, reSerializedSig, 'Wire digest and re-serialized digest must differ');

      // The gateway MUST accept the wireSig because it captures req.rawBody
      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': wireSig,
          'Content-Type': 'application/json'
        },
        body: wireBuf
      });

      assert.strictEqual(res.statusCode, 200, 'Gateway correctly verified against rawBody wire buffer');
    });

    // ══════════════════════════════════════════════════════════════════════════
    // SUITE 3: REPLAY ATTACK & TIMESTAMP WINDOW TESTING
    // ══════════════════════════════════════════════════════════════════════════
    console.log('\n--- Suite 3: Replay Attack Testing ---');

    await runTest('Replay', 'Timestamp > 5 minutes in past is rejected (401 TIMESTAMP_EXPIRED)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_replay_past' } });
      const expiredTimestamp = Date.now() - (5 * 60 * 1000 + 5000); // 5m 5s ago
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'x-webhook-timestamp': String(expiredTimestamp),
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'TIMESTAMP_EXPIRED');
    });

    await runTest('Replay', 'Timestamp > 5 minutes in future is rejected (401 TIMESTAMP_EXPIRED)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_replay_future' } });
      const futureTimestamp = Date.now() + (5 * 60 * 1000 + 5000); // 5m 5s in future
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'x-webhook-timestamp': String(futureTimestamp),
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 401);
      assert.strictEqual(res.body.reason, 'TIMESTAMP_EXPIRED');
    });

    await runTest('Replay', 'Timestamp within valid window (60s in past) is accepted (200 OK)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_replay_valid_past', ack: 1 } });
      const validPastTimestamp = Date.now() - 60000; // 1 min ago
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'x-webhook-timestamp': String(validPastTimestamp),
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Replay', 'Timestamp within valid drift (15s in future) is accepted (200 OK)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_replay_valid_future', ack: 1 } });
      const validFutureTimestamp = Date.now() + 15000; // 15s clock drift
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'x-timestamp': String(validFutureTimestamp), // using alternate x-timestamp
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Replay', 'Timestamp-bound HMAC variant (${timestamp}.${rawBody}) is accepted (200 OK)', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_bound_hmac', ack: 1 } });
      const nowTs = String(Date.now() - 2000);
      const boundPayload = `${nowTs}.${rawBody}`;
      const boundSig = computeHmac(TEST_SECRET, Buffer.from(boundPayload, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': boundSig,
          'x-webhook-timestamp': nowTs,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Replay', 'Non-numeric timestamp header does not crash server and falls back safely', async () => {
      const rawBody = JSON.stringify({ event: 'message.ack', payload: { id: 'msg_nan_ts', ack: 1 } });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'x-webhook-timestamp': 'not-a-valid-unix-epoch-ms',
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    // ══════════════════════════════════════════════════════════════════════════
    // SUITE 4: TIMING-ATTACK RESISTANCE & SAFE COMPARISON
    // ══════════════════════════════════════════════════════════════════════════
    console.log('\n--- Suite 4: Timing-Attack Resistance ---');

    await runTest('Timing', 'Direct inspection: verifyWebhookSignature uses crypto.timingSafeEqual', async () => {
      const serverCode = require('fs').readFileSync(require.resolve('./server'), 'utf8');
      assert(serverCode.includes('crypto.timingSafeEqual'), 'server.js must use crypto.timingSafeEqual');
      assert(serverCode.includes('receivedBuf.length === computedBuf.length'), 'Must guard length before timingSafeEqual');
    });

    await runTest('Timing', 'Unequal buffer lengths never throw TypeError and reject cleanly', async () => {
      // In Node.js, calling crypto.timingSafeEqual on unequal length buffers throws TypeError.
      // verifyWebhookSignature must cleanly return valid: false without throwing!
      const mockReqDifferentLength = {
        headers: {
          'x-webhook-hmac': 'a'.repeat(32) // 32 chars != 64 chars
        },
        rawBody: Buffer.from('{"test":1}')
      };

      assert.doesNotThrow(() => {
        const result = verifyWebhookSignature(mockReqDifferentLength);
        assert.strictEqual(result.valid, false);
      }, 'Unequal length must not throw TypeError');
    });

    await runTest('Timing', 'Constant-time verification benchmark (first-byte vs last-byte differential)', async () => {
      const bodyBuf = Buffer.from('{"event":"benchmark","data":"constant_time_testing"}', 'utf8');
      const correctDigest = computeHmac(TEST_SECRET, bodyBuf);

      // Create two invalid digests:
      // A: Differs at byte 0
      const char0 = correctDigest[0] === 'a' ? 'b' : 'a';
      const digestDiffAtStart = char0 + correctDigest.slice(1);

      // B: Differs at byte 63 (last byte)
      const char63 = correctDigest[63] === 'a' ? 'b' : 'a';
      const digestDiffAtEnd = correctDigest.slice(0, 63) + char63;

      const reqStart = { headers: { 'x-webhook-hmac': digestDiffAtStart }, rawBody: bodyBuf };
      const reqEnd = { headers: { 'x-webhook-hmac': digestDiffAtEnd }, rawBody: bodyBuf };

      // Warmup JIT
      for (let i = 0; i < 200; i++) {
        verifyWebhookSignature(reqStart);
        verifyWebhookSignature(reqEnd);
      }

      const iterations = 5000;

      const t0 = process.hrtime.bigint();
      for (let i = 0; i < iterations; i++) {
        verifyWebhookSignature(reqStart);
      }
      const t1 = process.hrtime.bigint();

      for (let i = 0; i < iterations; i++) {
        verifyWebhookSignature(reqEnd);
      }
      const t2 = process.hrtime.bigint();

      const timeStartNs = Number(t1 - t0);
      const timeEndNs = Number(t2 - t1);

      // Both should take comparable time within normal CPU jitter ratio (< 2.5x variance)
      const ratio = timeStartNs > timeEndNs ? timeStartNs / timeEndNs : timeEndNs / timeStartNs;
      assert(ratio < 2.5, `Execution times should be closely aligned without early-exit leakage (ratio: ${ratio.toFixed(2)})`);
    });

    // ══════════════════════════════════════════════════════════════════════════
    // SUITE 5: MALFORMED WEBHOOK PAYLOADS & FUZZING RESILIENCE
    // ══════════════════════════════════════════════════════════════════════════
    console.log('\n--- Suite 5: Malformed Webhook Payloads & Fuzzing ---');

    await runTest('Malformed', 'Empty JSON body ({}) with valid signature returns 200 OK without crash', async () => {
      const rawBody = '{}';
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Malformed', 'Missing event field ({ payload: { ... } }) returns 200 OK without crash', async () => {
      const rawBody = JSON.stringify({
        session: 'cafe_qr_orders',
        payload: { id: 'msg_no_event_type', text: 'hello' }
      });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
    });

    await runTest('Malformed', 'Missing payload object ({ event: "message.ack" }) returns 200 OK without crash', async () => {
      const rawBody = JSON.stringify({
        event: 'message.ack',
        session: 'cafe_qr_orders'
      });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
    });

    await runTest('Malformed', 'Explicit null payload field ({ event: "message.ack", payload: null }) handled safely', async () => {
      const rawBody = JSON.stringify({
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: null
      });
      const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: rawBody
      });

      assert.strictEqual(res.statusCode, 200);
    });

    await runTest('Malformed', 'Invalid / unmapped ack values (null, "GARBAGE", 999, {}) handled gracefully', async () => {
      const cases = [
        { id: 'msg_bad_ack_1', ack: null },
        { id: 'msg_bad_ack_2', ack: 'GARBAGE_UNKNOWN_ACK' },
        { id: 'msg_bad_ack_3', ack: 999 },
        { id: 'msg_bad_ack_4', ack: {} }
      ];

      for (const item of cases) {
        const rawBody = JSON.stringify({
          event: 'message.ack',
          session: 'cafe_qr_orders',
          payload: item
        });
        const sig = computeHmac(TEST_SECRET, Buffer.from(rawBody, 'utf8'));

        const res = await sendRawHttpRequest(webhookUrl, {
          method: 'POST',
          headers: {
            'x-webhook-hmac': sig,
            'Content-Type': 'application/json'
          },
          body: rawBody
        });

        assert.strictEqual(res.statusCode, 200);
      }
    });

    await runTest('Malformed', 'Giant webhook payload (1 MB buffer with deep arrays and unicode)', async () => {
      const repeatedItems = [];
      for (let i = 0; i < 6000; i++) {
        repeatedItems.push({
          index: i,
          token: `token_${i}_${'x'.repeat(150)}`,
          unicodeText: '🧾 Digital Bill #12345 ₹ 999.00 ☕'
        });
      }
      const giantObj = {
        event: 'message.ack',
        session: 'cafe_qr_orders',
        payload: {
          id: 'msg_giant_payload',
          ack: 1,
          auditLog: repeatedItems
        }
      };
      const giantWireBuffer = Buffer.from(JSON.stringify(giantObj), 'utf8');
      assert(giantWireBuffer.length >= 1024 * 1024, `Buffer should be >= 1MB (actual: ${giantWireBuffer.length} bytes)`);

      const sig = computeHmac(TEST_SECRET, giantWireBuffer);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: giantWireBuffer
      });

      assert.strictEqual(res.statusCode, 200);
      assert.strictEqual(res.body.success, true);
    });

    await runTest('Malformed', 'Syntactically invalid JSON wire body returns 400 without crashing gateway', async () => {
      const invalidJson = '{"event": "message.ack", "payload": { UNCLOSED_BRACKET: ';
      const wireBuf = Buffer.from(invalidJson, 'utf8');
      const sig = computeHmac(TEST_SECRET, wireBuf);

      const res = await sendRawHttpRequest(webhookUrl, {
        method: 'POST',
        headers: {
          'x-webhook-hmac': sig,
          'Content-Type': 'application/json'
        },
        body: wireBuf
      });

      // Express body parser returns 400 Bad Request
      assert.strictEqual(res.statusCode, 400, 'Malformed JSON should be rejected with 400 Bad Request');

      // Crucial test: gateway process is still alive and responds to subsequent health check
      const healthRes = await sendRawHttpRequest(`${appServer.baseUrl}/health`, { method: 'GET' });
      assert.strictEqual(healthRes.statusCode, 200, 'Gateway should remain fully operational after malformed JSON');
      assert.strictEqual(healthRes.body.status, 'UP');
    });

  } finally {
    await appServer.close();
    console.log('\n[AppServer] Closed ephemeral test server.');
  }

  // ══════════════════════════════════════════════════════════════════════════
  // SUMMARY REPORT
  // ══════════════════════════════════════════════════════════════════════════
  console.log('\n================================================================');
  console.log(`TOTAL ADVERSARIAL TESTS: ${totalTests}`);
  console.log(`PASSED: ${passedTests}`);
  console.log(`FAILED: ${failedTests}`);
  console.log('================================================================\n');

  if (failedTests > 0) {
    console.error(`❌ STRESS TEST FAILED with ${failedTests} failure(s).`);
    process.exit(1);
  } else {
    console.log('✅ ALL ADVERSARIAL SECURITY & HMAC TESTS PASSED EMPIRICALLY!');
    process.exit(0);
  }
}

if (require.main === module) {
  runAdversarialSecuritySuite().catch(err => {
    console.error('Unhandled fatal error in test suite:', err);
    process.exit(1);
  });
}

module.exports = {
  runAdversarialSecuritySuite,
  testResults
};
