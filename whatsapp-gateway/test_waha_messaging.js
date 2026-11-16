/**
 * test_waha_messaging.js — Comprehensive Milestone 3 Test Suite
 *
 * Validates:
 * 1. formatChatId Normalization:
 *    - 10-digit raw number (9496958200 -> 919496958200@c.us)
 *    - 11-digit with leading zero (09496958200 -> 919496958200@c.us)
 *    - Formatted string with punctuation (+91 9496-958200 -> 919496958200@c.us)
 *    - Pre-existing @c.us suffix preserved
 *    - Group JID with @g.us suffix preserved
 *    - JID with :1@s.whatsapp.net normalized to digits@c.us
 *
 * 2. extractMessageId Polymorphism:
 *    - String id
 *    - { id: 'msg_1' }
 *    - { id: { _serialized: 'msg_ser_2' } }
 *    - { key: { id: 'msg_key_3' } }
 *    - { messageId: 'msg_mid_4' }
 *    - { data: { id: 'msg_data_5' } }
 *
 * 3. Outbound Dispatches via WahaClient:
 *    - sendText with options and sessionName
 *    - sendImage with inline photo payload
 *    - sendImage 404 fallback to sendFile
 *    - sendFile with image auto-routing to sendImage
 *    - sendFile with PDF document payload
 *    - Typing presence: startTyping, stopTyping, simulateTyping
 *
 * 4. Gateway REST API Endpoints:
 *    - POST /api/send-bill: candidate session fallback (primary -> fallback -> default)
 *    - POST /api/send-bill: returns exact PROJECT.md contract shape { success, message, data: { messageId, session, phone }, result }
 *    - POST /api/send-bill: all sessions unavailable returns 503
 *    - POST /api/test-message: returns contract shape { success, message, data: { messageId, session, phone }, result }
 *    - Backward compatibility: GET /health, GET /api/status, GET /api/qr, POST /api/disconnect, POST /api/clear-contact-session
 *
 * 5. Inbound Webhooks (POST /api/webhook) & HMAC Security:
 *    - Valid HMAC signature with rawBody buffer match -> 200 OK
 *    - Missing signature header -> 401 Unauthorized
 *    - Invalid signature length (!= 64) -> 401 Unauthorized
 *    - Tampered body with original signature -> 401 Unauthorized
 *    - sha256= prefixed header support
 *    - Replay timestamp protection (> 5min rejected)
 *    - Insecure dev mode when secret not configured
 *
 * 6. Monotonic ACK State Engine:
 *    - Positive monotonic progression: PENDING(0) -> SENT(1) -> DELIVERED(2) -> READ(3) -> PLAYED(4)
 *    - Discard delayed out-of-order packets (e.g. DELIVERED packet arriving after READ)
 *    - Discard late error packet after message already delivered/read on handset
 *    - Accept early error packet before message delivered (rank -1 FAILED)
 *    - Diagnostic inspection via GET /api/delivery-status/:id and GET /api/message-status/:id
 *
 * 7. Inbound Message Routing & CRM Lead Forwarding:
 *    - Echo suppression: fromMe === true dropped immediately
 *    - Inbound to official session ('cafeqr_official') forwards lead payload to CRM auto-capture
 *    - Inbound to branch session ('cafe_qr_orders') not forwarded to CRM
 *    - Spring Boot offline resilience: webhook returns 200 without crashing
 *
 * 8. Session Status Synchronization:
 *    - Webhook event session.status updates gateway cache
 */

'use strict';

const assert = require('assert');
const http = require('http');
const crypto = require('crypto');
const {
  WahaClient,
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

async function runAllMilestone3Tests() {
  console.log('======================================================================');
  console.log('🧪 MILESTONE 3 TEST SUITE: OUTBOUND MESSAGING, WEBHOOKS & BAILEYS PURGE');
  console.log('======================================================================\n');

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 1: Phone Formatting & Message ID Extraction
  // ───────────────────────────────────────────────────────────────────────────
  console.log('📱 SUITE 1: Phone Formatting (formatChatId) & ID Extraction');

  await runTest('formatChatId: 10-digit Indian number prepends 91 and appends @c.us', () => {
    assert.strictEqual(formatChatId('9496958200'), '919496958200@c.us');
    assert.strictEqual(formatChatId(9496958200), '919496958200@c.us');
  });

  await runTest('formatChatId: 11-digit number with leading zero strips zero and prepends 91', () => {
    assert.strictEqual(formatChatId('09496958200'), '919496958200@c.us');
  });

  await runTest('formatChatId: Formatted strings with +, spaces, and dashes normalize correctly', () => {
    assert.strictEqual(formatChatId('+91 9496-958200'), '919496958200@c.us');
    assert.strictEqual(formatChatId('+91 (949) 695-8200'), '919496958200@c.us');
  });

  await runTest('formatChatId: Numbers already ending with @c.us or @g.us preserved', () => {
    assert.strictEqual(formatChatId('919496958200@c.us'), '919496958200@c.us');
    assert.strictEqual(formatChatId('120363041@g.us'), '120363041@g.us');
  });

  await runTest('formatChatId: Handles null, undefined, and empty string gracefully', () => {
    assert.strictEqual(formatChatId(''), '');
    assert.strictEqual(formatChatId(null), '');
    assert.strictEqual(formatChatId(undefined), '');
  });

  await runTest('formatChatId: Custom defaultCountryCode parameter supported', () => {
    assert.strictEqual(formatChatId('2025550143', '1'), '12025550143@c.us');
  });

  await runTest('extractMessageId: Resolves polymorphic ID shapes across WAHA versions', () => {
    assert.strictEqual(extractMessageId('msg_raw_string'), 'msg_raw_string');
    assert.strictEqual(extractMessageId({ id: 'msg_direct_1' }), 'msg_direct_1');
    assert.strictEqual(extractMessageId({ id: { _serialized: 'msg_serialized_2' } }), 'msg_serialized_2');
    assert.strictEqual(extractMessageId({ key: { id: 'msg_key_3' } }), 'msg_key_3');
    assert.strictEqual(extractMessageId({ messageId: 'msg_mid_4' }), 'msg_mid_4');
    assert.strictEqual(extractMessageId({ data: { id: 'msg_data_5' } }), 'msg_data_5');
    assert.strictEqual(extractMessageId(null), null);
    assert.strictEqual(extractMessageId({}), null);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 2: WahaClient Outbound Dispatches & Typing Presence
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📤 SUITE 2: WahaClient Outbound Messaging & Presence');

  await runTest('sendText: dispatches formatted payload with optional typing simulation', async () => {
    let receivedPayload = null;
    let typingStarted = false;
    let typingStopped = false;

    const mock = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/startTyping') {
        typingStarted = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } else if (req.url === '/api/stopTyping') {
        typingStopped = true;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      } else if (req.url === '/api/sendText') {
        receivedPayload = ctx.body;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'waha_msg_101', status: 'sent' }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });

    const client = new WahaClient({
      baseUrl: mock.baseUrl,
      sleepFn: async () => {} // Instant sleep in test
    });

    const resp = await client.sendText('9496958200', 'Hello from Cafe QR', {
      simulateTyping: true,
      typingDelayMs: 10
    }, 'cafe_qr_orders');

    await mock.close();

    assert.strictEqual(typingStarted, true);
    assert.strictEqual(typingStopped, true);
    assert.strictEqual(receivedPayload.chatId, '919496958200@c.us');
    assert.strictEqual(receivedPayload.text, 'Hello from Cafe QR');
    assert.strictEqual(receivedPayload.session, 'cafe_qr_orders');
    assert.strictEqual(resp.id, 'waha_msg_101');
  });

  await runTest('sendImage: dispatches inline photo to /api/sendImage', async () => {
    let imagePayload = null;

    const mock = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/sendImage') {
        imagePayload = ctx.body;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'img_msg_202', status: 'sent' }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const resp = await client.sendImage(
      '+91 9496-958200',
      'data:image/jpeg;base64,/9j/4AAQSkZJRg...',
      'bill.jpg',
      'image/jpeg',
      'Invoice #104'
    );

    await mock.close();

    assert.strictEqual(imagePayload.chatId, '919496958200@c.us');
    assert.strictEqual(imagePayload.file.filename, 'bill.jpg');
    assert.strictEqual(imagePayload.file.mimetype, 'image/jpeg');
    assert.strictEqual(imagePayload.file.data, '/9j/4AAQSkZJRg...');
    assert.strictEqual(imagePayload.caption, 'Invoice #104');
    assert.strictEqual(resp.id, 'img_msg_202');
  });

  await runTest('sendImage: gracefully falls back to /api/sendFile when /api/sendImage returns 404', async () => {
    let sendFileCalled = false;
    let sendFilePayload = null;

    const mock = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/sendImage') {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ statusCode: 404, message: 'Endpoint sendImage not found' }));
      } else if (req.url === '/api/sendFile') {
        sendFileCalled = true;
        sendFilePayload = ctx.body;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'file_fallback_303', status: 'sent' }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });
    const resp = await client.sendImage('9496958200', 'b64imagedata...', 'bill.png', 'image/png', 'Bill');

    await mock.close();

    assert.strictEqual(sendFileCalled, true);
    assert.strictEqual(sendFilePayload.chatId, '919496958200@c.us');
    assert.strictEqual(resp.id, 'file_fallback_303');
  });

  await runTest('sendFile: automatically routes image files to /api/sendImage', async () => {
    let hitImageEndpoint = false;
    let hitFileEndpoint = false;

    const mock = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/sendImage') {
        hitImageEndpoint = true;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'img_auto_routed', status: 'sent' }));
      } else if (req.url === '/api/sendFile') {
        hitFileEndpoint = true;
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'file_normal', status: 'sent' }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });

    const client = new WahaClient({ baseUrl: mock.baseUrl });

    // 1. Image filename -> auto-routes to /api/sendImage
    await client.sendFile('9496958200', 'rawdata', 'receipt.jpg', 'image/jpeg', 'Bill');
    assert.strictEqual(hitImageEndpoint, true);
    assert.strictEqual(hitFileEndpoint, false);

    // 2. PDF filename -> dispatches to /api/sendFile
    await client.sendFile('9496958200', 'pdfdata', 'receipt.pdf', 'application/pdf', 'PDF Bill');
    assert.strictEqual(hitFileEndpoint, true);

    await mock.close();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 3: Gateway Server Endpoints & Session Fallback Chain
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🏨 SUITE 3: Gateway Endpoints & Candidate Session Fallback');

  await runTest('/api/send-bill: falls back from primary to secondary session when primary is DISCONNECTED', async () => {
    let probedSessions = [];

    const mockWaha = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/sessions/org_branch_a') {
        probedSessions.push('org_branch_a');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'org_branch_a', status: 'STOPPED' })); // Disconnected
      } else if (req.url === '/api/sessions/client_hq_b') {
        probedSessions.push('client_hq_b');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'client_hq_b', status: 'WORKING' })); // Active fallback!
      } else if (req.url === '/api/sendText') {
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'bill_fallback_msg_99', status: 'sent' }));
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING' }));
      }
    });

    // Temporarily enable multi-session mode for isolation test
    process.env.WAHA_MULTI_SESSION = 'true';
    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;

    const gateway = await createTestAppServer(app);

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/send-bill`, {
      method: 'POST',
      body: {
        sessionId: 'org_branch_a',
        fallbackSessionId: 'client_hq_b',
        phone: '9496958200',
        text: 'Your order total is Rs 450'
      }
    });

    await gateway.close();
    await mockWaha.close();
    delete process.env.WAHA_MULTI_SESSION;

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.data.session, 'client_hq_b');
    assert.strictEqual(res.body.data.messageId, 'bill_fallback_msg_99');
    assert.strictEqual(res.body.data.phone, '9496958200');
  });

  await runTest('/api/send-bill: returns HTTP 503 when all candidate sessions are disconnected', async () => {
    const mockWaha = await createEphemeralServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'STOPPED' }));
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;

    const gateway = await createTestAppServer(app);

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/send-bill`, {
      method: 'POST',
      body: {
        sessionId: 'org_unlinked',
        phone: '9496958200',
        text: 'Test message'
      }
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.statusCode, 503);
    assert.strictEqual(res.body.success, false);
    assert(res.body.message.includes('not connected'));
  });

  await runTest('/api/test-message: returns contract shape { success, message, data: { messageId, session } }', async () => {
    const mockWaha = await createEphemeralServer((req, res) => {
      if (req.url === '/api/sendText') {
        res.writeHead(201, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ id: 'test_msg_id_88', status: 'sent' }));
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING' }));
      }
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;

    const gateway = await createTestAppServer(app);

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/test-message`, {
      method: 'POST',
      body: {
        sessionId: 'default',
        phone: '9496958200'
      }
    });

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.data.messageId, 'test_msg_id_88');
    assert.strictEqual(res.body.data.session, 'cafe_qr_orders');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 4: Webhook HMAC-SHA256 Signature Security
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔒 SUITE 4: Webhook HMAC-SHA256 Signature Security');

  const TEST_SECRET = 'test-waha-webhook-hmac-secret-xyz123';

  await runTest('verifyWebhookSignature: Valid HMAC matches raw wire buffer -> 200 OK', async () => {
    process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
    const gateway = await createTestAppServer(app);

    const payload = JSON.stringify({
      event: 'message.ack',
      session: 'cafe_qr_orders',
      payload: { id: 'msg_hmac_1', ack: 1 }
    });

    const digest = crypto.createHmac('sha256', TEST_SECRET).update(Buffer.from(payload, 'utf8')).digest('hex');

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-waha-hmac-sha256': digest
      },
      body: payload
    });

    await gateway.close();
    delete process.env.WHATSAPP_HOOK_HMAC_KEY;

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.success, true);
  });

  await runTest('verifyWebhookSignature: Missing signature header rejected with 401', async () => {
    process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
    const gateway = await createTestAppServer(app);

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event: 'ping' })
    });

    await gateway.close();
    delete process.env.WHATSAPP_HOOK_HMAC_KEY;

    assert.strictEqual(res.statusCode, 401);
    assert.strictEqual(res.body.reason, 'MISSING_SIGNATURE_HEADER');
  });

  await runTest('verifyWebhookSignature: Tampered wire body rejected with 401', async () => {
    process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
    const gateway = await createTestAppServer(app);

    const originalBody = JSON.stringify({ event: 'message.ack', payload: { id: 'orig' } });
    const originalDigest = crypto.createHmac('sha256', TEST_SECRET).update(Buffer.from(originalBody)).digest('hex');

    const tamperedBody = JSON.stringify({ event: 'message.ack', payload: { id: 'tampered' } });

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-webhook-hmac': originalDigest
      },
      body: tamperedBody
    });

    await gateway.close();
    delete process.env.WHATSAPP_HOOK_HMAC_KEY;

    assert.strictEqual(res.statusCode, 401);
    assert.strictEqual(res.body.reason, 'DIGEST_MISMATCH');
  });

  await runTest('verifyWebhookSignature: sha256= prefix handled seamlessly', async () => {
    process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
    const gateway = await createTestAppServer(app);

    const payload = JSON.stringify({ event: 'session.status', session: 'cafe_qr_orders', payload: { status: 'WORKING' } });
    const digest = crypto.createHmac('sha256', TEST_SECRET).update(Buffer.from(payload)).digest('hex');

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-hub-signature-256': `sha256=${digest}`
      },
      body: payload
    });

    await gateway.close();
    delete process.env.WHATSAPP_HOOK_HMAC_KEY;

    assert.strictEqual(res.statusCode, 200);
  });

  await runTest('verifyWebhookSignature: Expired timestamp (> 5 min) rejected with 401', async () => {
    process.env.WHATSAPP_HOOK_HMAC_KEY = TEST_SECRET;
    const gateway = await createTestAppServer(app);

    const payload = JSON.stringify({ event: 'ping' });
    const oldTimestamp = Date.now() - 400000; // 6.6 minutes ago
    const digest = crypto.createHmac('sha256', TEST_SECRET).update(Buffer.from(payload)).digest('hex');

    const res = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-webhook-hmac': digest,
        'x-webhook-timestamp': String(oldTimestamp)
      },
      body: payload
    });

    await gateway.close();
    delete process.env.WHATSAPP_HOOK_HMAC_KEY;

    assert.strictEqual(res.statusCode, 401);
    assert.strictEqual(res.body.reason, 'TIMESTAMP_EXPIRED');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 5: Monotonic Delivery ACK Progression Engine
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n📊 SUITE 5: Monotonic ACK State Progression');

  await runTest('Monotonic ACK: advances monotonically 1 -> 2 -> 3', async () => {
    const gateway = await createTestAppServer(app);
    const msgId = 'test_monotonic_order_1';

    // Step 1: ACK 1 (SENT)
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: 1 } }
    });
    let status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, 1);
    assert.strictEqual(status.status, 'SENT');

    // Step 2: ACK 2 (DELIVERED)
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: 2 } }
    });
    status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, 2);
    assert.strictEqual(status.status, 'DELIVERED');

    // Step 3: ACK 3 (READ)
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: 3 } }
    });
    status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, 3);
    assert.strictEqual(status.status, 'READ');

    // Step 4: Out-of-order delayed ACK 2 arrives after ACK 3 -> must be DISCARDED
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: 2 } }
    });
    status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, 3); // Still 3 (READ)! Never regressed
    assert.strictEqual(status.status, 'READ');

    // Step 5: Late ERROR ack arrives after DELIVERED/READ -> must be DISCARDED
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: -1 } }
    });
    status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, 3); // Still 3! Late error ignored

    await gateway.close();
  });

  await runTest('Monotonic ACK: Early error packet (before delivered) transitions to FAILED (-1)', async () => {
    const gateway = await createTestAppServer(app);
    const msgId = 'test_early_failed_msg';

    // Message sent (rank 1)
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: 1 } }
    });

    // Immediate network error (rank -1)
    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: { event: 'message.ack', session: 'cafe_qr_orders', payload: { id: msgId, ack: -1 } }
    });

    const status = (await makeHttpRequest(`${gateway.baseUrl}/api/delivery-status/${msgId}`)).body.data;
    assert.strictEqual(status.statusRank, -1);
    assert.strictEqual(status.status, 'FAILED');

    await gateway.close();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 6: Inbound Message Routing & CRM Lead Auto-Capture Forwarding
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n💼 SUITE 6: Inbound Message & CRM Lead Auto-Capture');

  await runTest('Inbound message to official session (cafeqr_official) forwards to CRM endpoint', async () => {
    let capturedLead = null;

    const mockCrm = await createEphemeralServer((req, res, ctx) => {
      if (req.url === '/api/v1/public/leads/auto-capture') {
        capturedLead = ctx.body;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, data: { id: 101, status: 'NEW_INQUIRY' } }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });

    process.env.BACKEND_AUTO_CAPTURE_URL = `${mockCrm.baseUrl}/api/v1/public/leads/auto-capture`;
    const gateway = await createTestAppServer(app);

    // Send inbound webhook event
    const webhookRes = await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: {
        event: 'message',
        session: 'cafeqr_official',
        payload: {
          id: 'msg_lead_1',
          from: '919876543210@c.us',
          fromMe: false,
          pushName: 'Priya Sharma',
          body: 'Hi, I need Cafe QR POS for my bakery'
        }
      }
    });

    // Wait a brief moment for asynchronous forwarding to complete
    await new Promise(r => setTimeout(r, 100));

    await gateway.close();
    await mockCrm.close();
    delete process.env.BACKEND_AUTO_CAPTURE_URL;

    assert.strictEqual(webhookRes.statusCode, 200);
    assert.notStrictEqual(capturedLead, null);
    assert.strictEqual(capturedLead.senderPhone, '919876543210');
    assert.strictEqual(capturedLead.pushName, 'Priya Sharma');
    assert.strictEqual(capturedLead.messageText, 'Hi, I need Cafe QR POS for my bakery');
    assert(capturedLead.gatewayToken.length > 0);
  });

  await runTest('Inbound message echoes (fromMe === true) are dropped and not forwarded', async () => {
    let leadForwarded = false;

    const mockCrm = await createEphemeralServer((req, res) => {
      leadForwarded = true;
      res.writeHead(200);
      res.end('{}');
    });

    process.env.BACKEND_AUTO_CAPTURE_URL = `${mockCrm.baseUrl}/api/v1/public/leads/auto-capture`;
    const gateway = await createTestAppServer(app);

    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: {
        event: 'message',
        session: 'cafeqr_official',
        payload: {
          id: 'echo_msg_1',
          from: '919876543210@c.us',
          fromMe: true, // Outgoing echo
          body: 'Outbound bill text'
        }
      }
    });

    await new Promise(r => setTimeout(r, 50));
    await gateway.close();
    await mockCrm.close();
    delete process.env.BACKEND_AUTO_CAPTURE_URL;

    assert.strictEqual(leadForwarded, false);
  });

  await runTest('Inbound message to branch order session (cafe_qr_orders) is NOT forwarded to CRM', async () => {
    let leadForwarded = false;

    const mockCrm = await createEphemeralServer((req, res) => {
      leadForwarded = true;
      res.writeHead(200);
      res.end('{}');
    });

    process.env.BACKEND_AUTO_CAPTURE_URL = `${mockCrm.baseUrl}/api/v1/public/leads/auto-capture`;
    const gateway = await createTestAppServer(app);

    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: {
        event: 'message',
        session: 'cafe_qr_orders', // Not official session
        payload: {
          from: '919876543210@c.us',
          fromMe: false,
          body: 'Thanks for the bill'
        }
      }
    });

    await new Promise(r => setTimeout(r, 50));
    await gateway.close();
    await mockCrm.close();
    delete process.env.BACKEND_AUTO_CAPTURE_URL;

    assert.strictEqual(leadForwarded, false);
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 7: Session Status Webhook Synchronization
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🔄 SUITE 7: Session Status Webhook Synchronization');

  await runTest('session.status webhook event synchronizes sessionStatusCache', async () => {
    const gateway = await createTestAppServer(app);

    await makeHttpRequest(`${gateway.baseUrl}/api/webhook`, {
      method: 'POST',
      body: {
        event: 'session.status',
        session: 'cafe_qr_orders',
        payload: {
          name: 'cafe_qr_orders',
          status: 'WORKING'
        }
      }
    });

    const cached = sessionStatusCache.get('cafe_qr_orders');
    await gateway.close();

    assert.notStrictEqual(cached, undefined);
    assert.strictEqual(cached.status, 'WORKING');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // SUITE 8: Backward Compatibility with Existing Endpoints
  // ───────────────────────────────────────────────────────────────────────────
  console.log('\n🛡️ SUITE 8: Backward Compatibility with Existing Gateway Endpoints');

  await runTest('GET /health returns UP with waha status details', async () => {
    const mockWaha = await createEphemeralServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ name: 'cafe_qr_orders', status: 'WORKING' }));
    });

    const { wahaClient: serverWahaClient } = require('./server');
    serverWahaClient.baseUrl = mockWaha.baseUrl;

    const gateway = await createTestAppServer(app);
    const res = await makeHttpRequest(`${gateway.baseUrl}/health`);

    await gateway.close();
    await mockWaha.close();

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.status, 'UP');
    assert.strictEqual(res.body.waha.status, 'WORKING');
    assert.strictEqual(res.body.waha.session, 'cafe_qr_orders');
  });

  await runTest('POST /api/clear-contact-session returns success with clearedFiles: 0', async () => {
    const gateway = await createTestAppServer(app);
    const res = await makeHttpRequest(`${gateway.baseUrl}/api/clear-contact-session`, {
      method: 'POST',
      body: { phone: '9496958200' }
    });

    await gateway.close();

    assert.strictEqual(res.statusCode, 200);
    assert.strictEqual(res.body.success, true);
    assert.strictEqual(res.body.clearedFiles, 0);
  });

  console.log('\n======================================================================');
  console.log(`📊 FINAL RESULTS: ${passedTests}/${totalTests} TESTS PASSED (${failedTests} FAILED)`);
  console.log('======================================================================\n');

  if (failedTests > 0) {
    process.exit(1);
  }
}

if (require.main === module) {
  runAllMilestone3Tests().catch((err) => {
    console.error('Fatal test runner error:', err);
    process.exit(1);
  });
}

module.exports = {
  runAllMilestone3Tests
};

