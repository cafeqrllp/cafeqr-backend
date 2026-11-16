/**
 * server.js — Cafe QR WhatsApp Gateway Adapter
 *
 * Exposes POS-compatible REST endpoints for session lifecycle, QR code pairing,
 * and outbound digital receipt delivery.
 *
 * Refactored in Milestone 2 to delegate all session management, circuit breaker
 * resilience, and WhatsApp protocol communication to WahaClient (`waha_client.js`),
 * targeting WAHA Core 2026.10.1 (NOWEB engine).
 *
 * Preserves 100% backward compatibility with:
 * - TestCafeQRFrontend/components/settings/WhatsAppGatewayCard.js
 * - Spring Boot WhatsAppService / WhatsAppController
 */

'use strict';

const express = require('express');
const cors = require('cors');
const pino = require('pino');
const crypto = require('crypto');
const { WahaClient, formatChatId, extractMessageId } = require('./waha_client');

const PORT = process.env.PORT || 3005;
const WAHA_BASE_URL = process.env.WAHA_BASE_URL || 'http://waha:3000';
const WAHA_API_KEY = process.env.WAHA_API_KEY || process.env.WHATSAPP_API_KEY || 'cafeqr-waha-secret-2026';
const WAHA_DEFAULT_SESSION = process.env.WAHA_DEFAULT_SESSION || 'cafe_qr_orders';
const WAHA_TIMEOUT_MS = parseInt(process.env.WAHA_TIMEOUT_MS || '15000', 10);

const WAHA_WEBHOOK_SECRET = process.env.WHATSAPP_HOOK_HMAC_KEY || 
                            process.env.WAHA_WEBHOOK_SECRET || 
                            process.env.WAHA_HMAC_SECRET || '';

const BACKEND_AUTO_CAPTURE_URL = process.env.BACKEND_AUTO_CAPTURE_URL || 
                                 'http://127.0.0.1:8080/api/v1/public/leads/auto-capture';

const FOUNDER_SECRET_KEY = process.env.FOUNDER_SECRET_KEY || 'cafeqr-founder-2024-secret';

const logger = pino({ level: process.env.LOG_LEVEL || 'info' });

// In-Memory status cache with TTL and maximum key count
class InMemoryCache {
  constructor(maxSize = 20000, ttlMs = 86400000) {
    this.maxSize = maxSize;
    this.ttlMs = ttlMs;
    this.map = new Map();
  }
  set(key, value) {
    if (this.map.size >= this.maxSize) {
      const firstKey = this.map.keys().next().value;
      this.map.delete(firstKey);
    }
    this.map.set(key, { value, expiresAt: Date.now() + this.ttlMs });
  }
  get(key) {
    const item = this.map.get(key);
    if (!item) return undefined;
    if (Date.now() > item.expiresAt) {
      this.map.delete(key);
      return undefined;
    }
    return item.value;
  }
  has(key) {
    return this.get(key) !== undefined;
  }
  delete(key) {
    return this.map.delete(key);
  }
  clear() {
    this.map.clear();
  }
}

const messageStatusCache = new InMemoryCache(20000, 86400000); // 24h retention
const sessionStatusCache = new Map();

// Monotonic Rank Mapping Table
const STATUS_RANK_MAP = Object.freeze({
  ERROR: -1,
  FAILED: -1,
  PENDING: 0,
  ENQUEUED: 0,
  SERVER: 1,
  SENT: 1,
  DELIVERY: 2,
  DEVICE: 2,
  DELIVERED: 2,
  READ: 3,
  PLAYED: 4
});

function mapAckToRank(ack, ackName) {
  if (typeof ack === 'number') {
    if (ack === -1) return -1;
    if (ack >= 0 && ack <= 4) return ack;
  }
  if (ackName && typeof ackName === 'string') {
    const upper = ackName.toUpperCase();
    if (STATUS_RANK_MAP[upper] !== undefined) {
      return STATUS_RANK_MAP[upper];
    }
  }
  return 0;
}

function mapRankToName(rank) {
  switch (rank) {
    case -1: return 'FAILED';
    case 0: return 'PENDING';
    case 1: return 'SENT';
    case 2: return 'DELIVERED';
    case 3: return 'READ';
    case 4: return 'PLAYED';
    default: return 'UNKNOWN';
  }
}

/**
 * Validates HMAC-SHA256 signature using rawBody
 *
 * @param {express.Request} req
 * @returns {{ valid: boolean, reason?: string }}
 */
function verifyWebhookSignature(req) {
  const secret = process.env.WHATSAPP_HOOK_HMAC_KEY || 
                 process.env.WAHA_WEBHOOK_SECRET || 
                 process.env.WAHA_HMAC_SECRET || '';

  if (!secret) {
    logger.warn('[WhatsApp Gateway Webhook] No HMAC key configured (WHATSAPP_HOOK_HMAC_KEY); skipping signature check in dev mode');
    return { valid: true };
  }

  const rawSig = req.headers['x-waha-hmac-sha256'] || 
                 req.headers['x-webhook-hmac'] || 
                 req.headers['x-hub-signature-256'];

  if (!rawSig) {
    return { valid: false, reason: 'MISSING_SIGNATURE_HEADER' };
  }

  const receivedDigest = String(rawSig).startsWith('sha256=') ? String(rawSig).slice(7) : String(rawSig);
  if (receivedDigest.length !== 64) {
    return { valid: false, reason: 'INVALID_DIGEST_LENGTH' };
  }

  // Check optional timestamp replay prevention (5 minute window)
  const rawTimestamp = req.headers['x-webhook-timestamp'] || req.headers['x-timestamp'];
  if (rawTimestamp) {
    const eventTime = parseInt(rawTimestamp, 10);
    if (!isNaN(eventTime) && Math.abs(Date.now() - eventTime) > 300000) {
      return { valid: false, reason: 'TIMESTAMP_EXPIRED' };
    }
  }

  const rawBuffer = req.rawBody || Buffer.from(JSON.stringify(req.body || {}), 'utf8');

  // Direct HMAC digest calculation
  const computedDigest = crypto.createHmac('sha256', secret)
    .update(rawBuffer)
    .digest('hex');

  const receivedBuf = Buffer.from(receivedDigest, 'utf8');
  const computedBuf = Buffer.from(computedDigest, 'utf8');

  if (receivedBuf.length === computedBuf.length && crypto.timingSafeEqual(receivedBuf, computedBuf)) {
    return { valid: true };
  }

  // Check timestamp-bound variant if timestamp was sent
  if (rawTimestamp) {
    const boundDigest = crypto.createHmac('sha256', secret)
      .update(`${rawTimestamp}.`)
      .update(rawBuffer)
      .digest('hex');
    const boundBuf = Buffer.from(boundDigest, 'utf8');
    if (receivedBuf.length === boundBuf.length && crypto.timingSafeEqual(receivedBuf, boundBuf)) {
      return { valid: true };
    }
  }

  return { valid: false, reason: 'DIGEST_MISMATCH' };
}

// Singleton WahaClient instance
const wahaClient = new WahaClient({
  baseUrl: WAHA_BASE_URL,
  apiKey: WAHA_API_KEY,
  defaultSession: WAHA_DEFAULT_SESSION,
  timeoutMs: WAHA_TIMEOUT_MS
});

const app = express();
app.use(cors());
// Preserve raw wire body for webhook verification
app.use(express.json({
  limit: '15mb',
  verify: (req, res, buf) => {
    req.rawBody = buf;
  }
}));

/**
 * Resolves requested session identifier into dedicated WAHA session name.
 * - 'cafeqr_official' -> 'cafeqr_official' (Platform official OTP/alert session)
 * - All other POS/branch sessions ('org_<id>', 'client_<id>', 'default', null) -> 'cafe_qr_orders'
 *
 * When WAHA_MULTI_SESSION=true is configured, sanitizes and preserves tenant session IDs.
 *
 * @param {string|null|undefined} rawSessionId
 * @returns {string} Target WAHA session name
 */
function resolveWahaSession(rawSessionId) {
  if (!rawSessionId) {
    return WAHA_DEFAULT_SESSION;
  }
  const trimmed = String(rawSessionId).trim();
  if (trimmed === 'cafeqr_official') {
    return 'cafeqr_official';
  }
  if (trimmed === 'default' || trimmed === '') {
    return WAHA_DEFAULT_SESSION;
  }
  if (process.env.WAHA_MULTI_SESSION === 'true') {
    return trimmed.replace(/[^a-zA-Z0-9_-]/g, '_');
  }
  return WAHA_DEFAULT_SESSION;
}

// ─────────────────────────────────────────────────────────────────────────────
// REST API ENDPOINTS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 1. Connection status (accepts query param ?sessionId=...)
 * Returns { success: true, sessionId, status, hasQr, user }
 */
app.get('/api/status', async (req, res) => {
  const requestedSession = req.query.sessionId;
  const targetSession = resolveWahaSession(requestedSession);

  try {
    const sessionInfo = await wahaClient.getSession(targetSession);
    const wahaStatus = (sessionInfo && sessionInfo.status) ? sessionInfo.status : 'STOPPED';

    // Status Mapping Matrix:
    // WAHA 'WORKING'      -> Legacy 'CONNECTED'
    // WAHA 'SCAN_QR_CODE' -> Legacy 'SCAN_QR'
    // WAHA 'STARTING'     -> Legacy 'CONNECTING'
    // WAHA 'STOPPED'      -> Legacy 'DISCONNECTED'
    // WAHA 'FAILED'       -> Legacy 'DISCONNECTED'
    // WAHA 'NOT_FOUND'    -> Legacy 'DISCONNECTED'
    let legacyStatus = 'DISCONNECTED';
    let hasQr = false;
    let user = null;

    if (wahaStatus === 'WORKING') {
      legacyStatus = 'CONNECTED';
      try {
        const me = (sessionInfo.me && sessionInfo.me.id)
          ? sessionInfo.me
          : await wahaClient.getMe(targetSession);

        if (me && me.id) {
          const rawPhone = me.phone || me.id.split('@')[0].split(':')[0];
          user = {
            id: me.id,
            phone: rawPhone,
            name: me.pushName || me.name || 'Cafe QR Branch'
          };
        }
      } catch (meErr) {
        user = {
          id: `${targetSession}@c.us`,
          phone: '',
          name: 'Cafe QR Branch'
        };
      }
    } else if (wahaStatus === 'SCAN_QR_CODE') {
      legacyStatus = 'SCAN_QR';
      hasQr = true;
    } else if (wahaStatus === 'STARTING') {
      legacyStatus = 'CONNECTING';
      hasQr = true;
    } else if (wahaStatus === 'STOPPED' || wahaStatus === 'NOT_FOUND') {
      // Auto-start session immediately so pairing QR is generated with zero manual commands
      wahaClient.startSession(targetSession).catch(err => {
        logger.warn({ err: err.message, session: targetSession }, '[WhatsApp Gateway] Auto-start session on status check error');
      });
      legacyStatus = 'CONNECTING';
      hasQr = true;
    } else if (wahaStatus === 'FAILED') {
      // Auto-recover failed session cleanly so pairing QR regenerates automatically
      wahaClient.restartSession(targetSession, true).catch(err => {
        logger.warn({ err: err.message, session: targetSession }, '[WhatsApp Gateway] Auto-recover failed session on status check error');
      });
      legacyStatus = 'CONNECTING';
      hasQr = true;
    } else {
      legacyStatus = 'DISCONNECTED';
      hasQr = false;
    }

    return res.json({
      success: true,
      sessionId: requestedSession || targetSession,
      status: legacyStatus,
      hasQr: Boolean(hasQr || legacyStatus === 'SCAN_QR'),
      user
    });
  } catch (err) {
    logger.warn({ err: err.message, session: targetSession }, '[WhatsApp Gateway] /api/status error');
    return res.json({
      success: true,
      sessionId: requestedSession || targetSession,
      status: 'DISCONNECTED',
      hasQr: false,
      user: null
    });
  }
});

/**
 * 2. Fetch QR code (accepts query param ?sessionId=...)
 * Returns { success: true, sessionId, status, qr }
 * or { success: true, sessionId, status: 'CONNECTED', message: 'Already connected', user }
 */
app.get('/api/qr', async (req, res) => {
  const requestedSession = req.query.sessionId;
  const targetSession = resolveWahaSession(requestedSession);

  try {
    const sessionInfo = await wahaClient.getSession(targetSession);
    const wahaStatus = (sessionInfo && sessionInfo.status) ? sessionInfo.status : 'STOPPED';

    // If session is already active and authenticated
    if (wahaStatus === 'WORKING') {
      let user = null;
      try {
        const me = (sessionInfo.me && sessionInfo.me.id)
          ? sessionInfo.me
          : await wahaClient.getMe(targetSession);
        const rawPhone = me?.phone || (me?.id ? me.id.split('@')[0].split(':')[0] : '');
        user = {
          id: me?.id || null,
          phone: rawPhone,
          name: me?.pushName || me?.name || 'Cafe QR Branch'
        };
      } catch (meErr) {
        user = null;
      }

      return res.json({
        success: true,
        sessionId: requestedSession || targetSession,
        status: 'CONNECTED',
        message: 'Already connected',
        user
      });
    }

    // Auto-start/recover session if it is stopped, not found, or failed
    if (wahaStatus === 'STOPPED' || wahaStatus === 'NOT_FOUND') {
      logger.info(`[WhatsApp Gateway] Auto-starting session [${targetSession}]...`);
      await wahaClient.startSession(targetSession).catch(err => {
        logger.warn({ err: err.message, session: targetSession }, '[WhatsApp Gateway] startSession error');
      });
    } else if (wahaStatus === 'FAILED') {
      logger.info(`[WhatsApp Gateway] Auto-recovering failed session [${targetSession}]...`);
      await wahaClient.restartSession(targetSession, true).catch(err => {
        logger.warn({ err: err.message, session: targetSession }, '[WhatsApp Gateway] restartSession error');
      });
    }

    // Retrieve QR image via WahaClient
    const qrResult = await wahaClient.getQrImage(targetSession);

    // If QR image fetch gracefully mapped to CONNECTED (HTTP 400 from WAHA)
    if (qrResult.status === 'CONNECTED') {
      let user = null;
      try {
        const me = await wahaClient.getMe(targetSession);
        user = {
          id: me?.id || null,
          phone: me?.phone || (me?.id ? me.id.split('@')[0].split(':')[0] : ''),
          name: me?.pushName || me?.name || 'Cafe QR Branch'
        };
      } catch (meErr) {
        user = null;
      }

      return res.json({
        success: true,
        sessionId: requestedSession || targetSession,
        status: 'CONNECTED',
        message: 'Already connected',
        user
      });
    }

    logger.info({ session: targetSession, hasQr: Boolean(qrResult.qr), status: qrResult.status }, '[WhatsApp Gateway] Fetched QR code result');

    return res.json({
      success: true,
      sessionId: requestedSession || targetSession,
      status: qrResult.status || 'SCAN_QR',
      qr: qrResult.qr || null
    });
  } catch (err) {
    logger.error({ err: err.message, session: targetSession }, '[WhatsApp Gateway] /api/qr error');
    return res.json({
      success: true,
      sessionId: requestedSession || targetSession,
      status: 'DISCONNECTED',
      qr: null
    });
  }
});

/**
 * 3. Send digital bill (with branch session and fallback session handling)
 */
app.post('/api/send-bill', async (req, res) => {
  const { sessionId, fallbackSessionId, phone, text, pdfBase64, filename } = req.body || {};

  if (!phone) {
    return res.status(400).json({ success: false, message: 'Phone number is required' });
  }

  // Session Fallback Resolution:
  // 1. Try primary session (e.g. branch org_*)
  // 2. If disconnected, try fallbackSessionId (e.g. client_*)
  // 3. If disconnected, try defaultSession (cafe_qr_orders)
  const candidateSessions = [];
  if (sessionId) {
    candidateSessions.push(resolveWahaSession(sessionId));
  }
  if (fallbackSessionId) {
    const resolvedFallback = resolveWahaSession(fallbackSessionId);
    if (!candidateSessions.includes(resolvedFallback)) {
      candidateSessions.push(resolvedFallback);
    }
  }
  const defaultSess = resolveWahaSession();
  if (!candidateSessions.includes(defaultSess)) {
    candidateSessions.push(defaultSess);
  }

  let activeSession = null;
  let lastSessionStatus = 'DISCONNECTED';

  for (const sess of candidateSessions) {
    const info = await wahaClient.getSession(sess).catch(() => null);
    if (info && info.status === 'WORKING') {
      activeSession = sess;
      break;
    }
    if (info && info.status) {
      lastSessionStatus = info.status;
    }
  }

  if (!activeSession) {
    logger.warn({ candidates: candidateSessions, lastStatus: lastSessionStatus }, '[WhatsApp Gateway] All candidate sessions unavailable');
    return res.status(503).json({
      success: false,
      message: `WhatsApp sessions [${candidateSessions.join(', ')}] are not connected (status: ${lastSessionStatus}).`
    });
  }

  try {
    let result;
    const typingOptions = {
      simulateTyping: process.env.NODE_ENV !== 'test',
      typingDelayMs: parseInt(process.env.WAHA_TYPING_DELAY_MS || '800', 10)
    };

    if (pdfBase64) {
      const ext = (filename || '').split('.').pop().toLowerCase();
      const isImage = ['jpg', 'jpeg', 'png', 'webp'].includes(ext) || (typeof pdfBase64 === 'string' && pdfBase64.startsWith('data:image/'));
      const mime = isImage ? `image/${ext === 'png' ? 'png' : 'jpeg'}` : 'application/pdf';

      if (isImage) {
        result = await wahaClient.sendImage(phone, pdfBase64, filename || 'bill.jpg', mime, text || '', typingOptions, activeSession);
      } else {
        result = await wahaClient.sendFile(phone, pdfBase64, filename || 'bill.pdf', 'application/pdf', text || '', typingOptions, activeSession);
      }
    } else {
      result = await wahaClient.sendText(phone, text || '', typingOptions, activeSession);
    }

    const messageId = extractMessageId(result);

    return res.json({
      success: true,
      message: pdfBase64 ? 'Bill sent successfully via WAHA' : 'Bill text sent successfully via WAHA',
      data: {
        messageId,
        session: activeSession,
        phone
      },
      result
    });
  } catch (err) {
    logger.error({ err: err.message, session: activeSession }, '[WhatsApp Gateway] /api/send-bill error');
    return res.status(500).json({ success: false, message: err.message });
  }
});

/**
 * 4. Dispatch test message (checks connection and sends ping text)
 */
app.post('/api/test-message', async (req, res) => {
  const { sessionId, phone } = req.body || {};
  const targetSession = resolveWahaSession(sessionId);

  if (!phone) {
    return res.status(400).json({ success: false, message: 'Phone number is required' });
  }

  try {
    const sessionInfo = await wahaClient.getSession(targetSession).catch(() => null);
    if (sessionInfo && sessionInfo.status === 'WORKING') {
      const branchLabel = targetSession.replace('org_', 'Branch ');
      const testText = `✅ *Cafe QR WhatsApp Gateway Test*\n\nYour WhatsApp Digital Bill service is connected and active for *${branchLabel}*! 🚀\n\n_Time: ${new Date().toLocaleString()}_`;
      const typingOptions = {
        simulateTyping: process.env.NODE_ENV !== 'test',
        typingDelayMs: 600
      };
      const result = await wahaClient.sendText(phone, testText, typingOptions, targetSession);
      const messageId = extractMessageId(result);

      return res.json({
        success: true,
        message: `Test message sent to ${phone}`,
        data: {
          messageId,
          session: targetSession,
          phone
        },
        result
      });
    }

    return res.status(503).json({
      success: false,
      message: `WhatsApp session [${targetSession}] is not connected (status: ${sessionInfo?.status || 'DISCONNECTED'}).`
    });
  } catch (err) {
    logger.error({ err: err.message }, '[WhatsApp Gateway] /api/test-message error');
    return res.status(500).json({ success: false, message: err.message });
  }
});

/**
 * Inbound WAHA Webhook Handler
 */
app.post('/api/webhook', async (req, res) => {
  // 1. Signature Verification
  const verification = verifyWebhookSignature(req);
  if (!verification.valid) {
    logger.warn({ reason: verification.reason }, '[WhatsApp Gateway Webhook] Signature verification failed');
    return res.status(401).json({ success: false, error: 'Unauthorized webhook signature', reason: verification.reason });
  }

  const event = req.body || {};
  const eventType = event.event;
  const sessionName = event.session;
  const payload = event.payload || {};

  logger.info({ eventType, session: sessionName, id: event.id }, '[WhatsApp Gateway Webhook] Received webhook event');

  // Immediately respond 200 OK so WAHA BullMQ queue does not stall
  res.status(200).json({ success: true, received: true });

  try {
    // 2. Event Routing
    if (eventType === 'message.ack') {
      const messageId = extractMessageId(payload);
      if (!messageId) {
        logger.warn({ payload }, '[Webhook] message.ack missing messageId');
        return;
      }

      const ackValue = payload.ack;
      const ackName = payload.ackName;
      const newRank = mapAckToRank(ackValue, ackName);
      const existing = messageStatusCache.get(messageId);
      const currentRank = existing ? existing.statusRank : 0;

      // Monotonic Rank Guard
      if (newRank === -1) {
        // Error ack: only update if message was never confirmed as delivered to customer handset
        if (currentRank >= 2) {
          logger.info({ messageId, currentRank, newRank }, '[Webhook] Discarding error ack: message already delivered to device');
          return;
        }
      } else if (newRank <= currentRank) {
        logger.info({ messageId, currentRank, newRank }, '[Webhook] Discarding out-of-order stale delivery ack');
        return;
      }

      const statusRecord = {
        messageId,
        session: sessionName,
        ack: ackValue,
        ackName: ackName || mapRankToName(newRank),
        statusRank: newRank,
        status: mapRankToName(newRank),
        from: payload.from,
        to: payload.to,
        timestamp: payload.timestamp || Date.now(),
        updatedAt: Date.now()
      };

      messageStatusCache.set(messageId, statusRecord);
      logger.info({ messageId, status: statusRecord.status, rank: newRank }, '[Webhook] message.ack updated monotonically');

    } else if (eventType === 'message') {
      // Discard outgoing echoes
      if (payload.fromMe === true) {
        return;
      }

      // Check if directed to platform official session or configured lead sessions
      const isLeadSession = (sessionName === 'cafeqr_official') || 
                            (process.env.LEAD_CAPTURE_SESSIONS && process.env.LEAD_CAPTURE_SESSIONS.split(',').includes(sessionName));

      if (isLeadSession) {
        const rawFrom = payload.from || payload.author || '';
        const cleanPhone = rawFrom.split('@')[0].split(':')[0].replace(/[^0-9]/g, '');
        const pushName = payload.pushName || payload._data?.notifyName || payload.notifyName || 'WhatsApp Prospect';
        const messageText = payload.body || payload.caption || payload.text || '';

        logger.info({ session: sessionName, senderPhone: cleanPhone, pushName }, '[Webhook] Forwarding inbound official message to CRM Auto-Capture');

        const leadPayload = {
          senderPhone: cleanPhone,
          pushName: pushName,
          messageText: messageText,
          gatewayToken: FOUNDER_SECRET_KEY
        };

        // Asynchronously forward to Spring Boot with timeout
        const autoCaptureUrl = process.env.BACKEND_AUTO_CAPTURE_URL || BACKEND_AUTO_CAPTURE_URL;
        if (typeof globalThis.fetch === 'function') {
          globalThis.fetch(autoCaptureUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(leadPayload)
          }).then(async resp => {
            if (resp.ok) {
              logger.info({ senderPhone: cleanPhone, status: resp.status }, '[Webhook] CRM Auto-Capture forwarded successfully');
            } else {
              const errText = await resp.text().catch(() => '');
              logger.warn({ status: resp.status, errText }, '[Webhook] CRM Auto-Capture endpoint returned non-200');
            }
          }).catch(err => {
            logger.warn({ err: err.message }, '[Webhook] Failed to connect to CRM Auto-Capture endpoint');
          });
        }
      }

    } else if (eventType === 'session.status') {
      const targetSession = payload.name || sessionName;
      const newStatus = payload.status;
      if (targetSession && newStatus) {
        sessionStatusCache.set(targetSession, {
          status: newStatus,
          updatedAt: Date.now()
        });
        logger.info({ session: targetSession, status: newStatus }, '[Webhook] Session status cache updated');
      }
    }
  } catch (procErr) {
    logger.error({ err: procErr.message }, '[Webhook] Error processing webhook event');
  }
});

/**
 * Diagnostic & Delivery Status Query Endpoints
 */
const handleGetDeliveryStatus = (req, res) => {
  const messageId = req.params.id;
  const status = messageStatusCache.get(messageId);
  if (!status) {
    return res.status(404).json({ success: false, message: 'Delivery status not found or expired from cache' });
  }
  return res.json({ success: true, data: status });
};

app.get('/api/message-status/:id', handleGetDeliveryStatus);
app.get('/api/delivery-status/:id', handleGetDeliveryStatus);

/**
 * 5. Disconnect / Unlink session
 * Returns { success: true, message: 'Disconnected' }
 */
app.post('/api/disconnect', async (req, res) => {
  const requestedSession = req.body && req.body.sessionId;
  const targetSession = resolveWahaSession(requestedSession);

  try {
    logger.info({ session: targetSession }, '[WhatsApp Gateway] Disconnecting/logging out session');
    await wahaClient.stopSession(targetSession, true);

    // Auto-restart session clean in 1s so it is ready for a new QR scan
    setTimeout(() => {
      wahaClient.startSession(targetSession).catch(err => {
        logger.warn({ err: err.message }, '[WhatsApp Gateway] Post-disconnect auto-restart notice');
      });
    }, 1000);

    return res.json({
      success: true,
      message: 'Disconnected'
    });
  } catch (err) {
    logger.error({ err: err.message, session: targetSession }, '[WhatsApp Gateway] /api/disconnect error');
    return res.status(500).json({
      success: false,
      message: err.message || 'Failed to disconnect session'
    });
  }
});

/**
 * 6. Clear contact session
 * Returns { success: true, clearedFiles: 0, message: 'Contact session cleared (managed by WAHA Core)' }
 */
app.post('/api/clear-contact-session', (req, res) => {
  const { phone } = req.body || {};
  if (!phone) {
    return res.status(400).json({ success: false, message: 'Phone number is required' });
  }

  logger.info({ phone }, '[WhatsApp Gateway] /api/clear-contact-session requested');
  return res.json({
    success: true,
    clearedFiles: 0,
    message: 'Contact session cleared (managed by WAHA Core)'
  });
});

/**
 * 7. Health check & Active sessions inventory
 * Returns { status: 'UP', waha: { reachable: true, status: ..., session: 'cafe_qr_orders' }, uptime: process.uptime() }
 */
app.get('/health', async (req, res) => {
  try {
    const targetSession = resolveWahaSession();
    const [pingResult, sessionInfo] = await Promise.all([
      wahaClient.ping(5).catch(err => ({ ok: false, reachable: false, status: 'UNREACHABLE', error: err.message })),
      wahaClient.getSession(targetSession).catch(err => ({ status: 'UNKNOWN', error: err.message }))
    ]);

    const isConnected = sessionInfo?.status === 'WORKING';
    const legacyStatus = isConnected ? 'CONNECTED' : (sessionInfo?.status === 'SCAN_QR_CODE' ? 'SCAN_QR' : 'DISCONNECTED');
    const phone = sessionInfo?.me?.id ? sessionInfo.me.id.split('@')[0].split(':')[0] : null;

    res.json({
      status: 'UP',
      totalSessions: 1,
      sessions: [
        {
          sessionId: targetSession,
          status: legacyStatus,
          phone
        }
      ],
      waha: {
        reachable: Boolean(pingResult && pingResult.reachable),
        status: sessionInfo?.status || 'UNKNOWN',
        session: targetSession
      },
      uptime: process.uptime()
    });
  } catch (err) {
    res.status(500).json({
      status: 'DOWN',
      error: err.message,
      uptime: process.uptime()
    });
  }
});

/**
 * Gateway Startup
 */
async function startGateway() {
  const server = app.listen(PORT, () => {
    console.log(`[GATEWAY_STARTUP] Multi-tenant WhatsApp Gateway listening on port ${PORT}`);
    logger.info(`[WhatsApp Gateway] Multi-tenant server running on port ${PORT}`);

    // Asynchronously ensure WAHA is reachable and auto-start sessions in background
    (async () => {
      try {
        const pingRes = await wahaClient.ping(50);
        logger.info({ pingRes }, '[WhatsApp Gateway] WAHA cold-start ping completed');
        await Promise.allSettled([
          wahaClient.startSession('cafe_qr_orders'),
          wahaClient.startSession('cafeqr_official')
        ]);
        logger.info('[WhatsApp Gateway] Background auto-start completed for default sessions');
      } catch (err) {
        logger.warn({ err: err.message }, '[WhatsApp Gateway] Background WAHA initialization notice');
      }
    })();
  });
  return server;
}

if (require.main === module) {
  startGateway().catch(err => {
    console.error('[GATEWAY_STARTUP_ERR] Failed to start gateway:', err);
    process.exit(1);
  });
}

module.exports = {
  app,
  wahaClient,
  resolveWahaSession,
  startGateway,
  messageStatusCache,
  sessionStatusCache,
  verifyWebhookSignature
};
