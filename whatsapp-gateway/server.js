/**
 * server.js — Cafe QR Multi-Tenant WhatsApp Companion Gateway
 * 
 * Provides an isolated, zero-cost WhatsApp Web integration using Baileys.
 * Supports multi-session architecture:
 * - Each Branch/Organization has its own independent session: `org_<orgId>`
 * - Optional Client-level master fallback session: `client_<clientId>`
 * - Sessions are persisted in `auth_sessions/<sessionId>/`
 */

const express = require('express');
const cors = require('cors');
const qrcode = require('qrcode');
const pino = require('pino');
const path = require('path');
const fs = require('fs');

// Ensure global crypto / WebCrypto is available in all Node environments for Baileys
const nodeCrypto = require('crypto');
if (typeof globalThis.crypto === 'undefined') {
  globalThis.crypto = nodeCrypto.webcrypto || nodeCrypto;
}
if (typeof global.crypto === 'undefined') {
  global.crypto = nodeCrypto.webcrypto || nodeCrypto;
}

// Baileys >= 6.7.19 is ESM-only (and carries the LID fixes), so it cannot be require()'d from CommonJS.
// It is loaded with a dynamic import() in startGateway() before the server starts listening.
let makeWASocket, useMultiFileAuthState, DisconnectReason, fetchLatestBaileysVersion,
  makeCacheableSignalKeyStore, makeInMemoryStore, addTransactionCapability, Browsers, proto;

const PORT = process.env.PORT || 3005;
const AUTH_DIR = path.join(__dirname, 'auth_sessions');

// Ensure base sessions directory exists
if (!fs.existsSync(AUTH_DIR)) {
  fs.mkdirSync(AUTH_DIR, { recursive: true });
}

const app = express();
app.use(cors());
app.use(express.json({ limit: '15mb' }));

const logger = pino({ level: 'info' });

// In-memory registry of active branch/client sessions
const sessions = new Map();

// Standard in-memory store for Baileys events (if available in Baileys release)
let store = null; // created in startGateway() once Baileys is loaded

// Persistent directory for recent sent & upserted messages across gateway restarts
const MSG_DIR = path.join(AUTH_DIR, 'messages');
if (!fs.existsSync(MSG_DIR)) {
  fs.mkdirSync(MSG_DIR, { recursive: true });
}

// Track message retries across socket lifecycle
let NodeCache;
try {
  NodeCache = require('node-cache');
} catch (e) {
  NodeCache = null;
}

const RETRY_TTL_MS = 15 * 60 * 1000;
const retryCounterMap = new Map();
const msgRetryCounterCache = NodeCache
  ? new NodeCache({ stdTTL: 900, checkperiod: 120 })
  : {
      get: (key) => {
        const entry = retryCounterMap.get(key);
        if (!entry) return undefined;
        if (Date.now() > entry.expiresAt) {
          retryCounterMap.delete(key);
          return undefined;
        }
        return entry.count;
      },
      set: (key, val) => {
        retryCounterMap.set(key, { count: val, expiresAt: Date.now() + RETRY_TTL_MS });
      },
      del: (key) => retryCounterMap.delete(key),
      flushAll: () => retryCounterMap.clear()
    };

// Bounded in-memory store + disk persistence for messages to resolve WhatsApp E2EE retryRequests ('Waiting for this message')
const messageStore = new Map();
const MAX_CACHED_MESSAGES = 1500;

function persistMessageToDisk(key, messageContent) {
  if (!key || !key.id || !messageContent) return;
  try {
    const cleanId = String(key.id).replace(/[^a-zA-Z0-9_-]/g, '_');
    const filePath = path.join(MSG_DIR, `${cleanId}.json`);
    const data = JSON.stringify({
      id: key.id,
      remoteJid: key.remoteJid,
      timestamp: Date.now(),
      message: messageContent
    });
    fs.writeFileSync(filePath, data, 'utf-8');
  } catch (err) {
    logger.warn({ err: err.message, id: key?.id }, '[WhatsApp Gateway] Failed to persist message to disk');
  }
}

function loadPersistedMessageFromDisk(keyId) {
  if (!keyId) return undefined;
  try {
    const cleanId = String(keyId).replace(/[^a-zA-Z0-9_-]/g, '_');
    const filePath = path.join(MSG_DIR, `${cleanId}.json`);
    if (fs.existsSync(filePath)) {
      const raw = fs.readFileSync(filePath, 'utf-8');
      const parsed = JSON.parse(raw);
      return parsed?.message;
    }
  } catch (err) {
    logger.warn({ err: err.message, id: keyId }, '[WhatsApp Gateway] Failed to read persisted message from disk');
  }
  return undefined;
}

function wrapProtoMessage(rawMsg) {
  if (!rawMsg) return undefined;
  if (proto && proto.Message && typeof proto.Message.fromObject === 'function') {
    try {
      return proto.Message.fromObject(rawMsg);
    } catch (e) {
      return rawMsg;
    }
  }
  return rawMsg;
}

function normalizeJid(rawJid) {
  if (!rawJid || typeof rawJid !== 'string') return '';
  const user = rawJid.split(':')[0].split('@')[0];
  return `${user}@s.whatsapp.net`;
}

function cacheMessage(key, messageContent) {
  if (!key || !key.id || !messageContent) return;
  const rawKey = `${key.remoteJid || ''}:${key.id}`;
  const normJid = normalizeJid(key.remoteJid);
  const normKey = `${normJid}:${key.id}`;

  if (messageStore.size >= MAX_CACHED_MESSAGES) {
    const oldest = messageStore.keys().next().value;
    messageStore.delete(oldest);
  }
  messageStore.set(rawKey, messageContent);
  if (normKey !== rawKey) {
    messageStore.set(normKey, messageContent);
  }
  messageStore.set(key.id, messageContent);

  // Persist to disk so restarts/reconnects don't burn decryption retries
  persistMessageToDisk(key, messageContent);
}

/**
 * Normalizes input sessionId into a safe, valid filesystem directory name.
 */
function normalizeSessionId(raw) {
  if (!raw || typeof raw !== 'string') return 'default';
  const clean = raw.trim().replace(/[^a-zA-Z0-9_-]/g, '_');
  return clean || 'default';
}

/**
 * Retrieves an existing session or initializes a new one.
 */
async function getOrCreateSession(rawSessionId) {
  const sessionId = normalizeSessionId(rawSessionId);

  if (sessions.has(sessionId)) {
    return sessions.get(sessionId);
  }

  const sessionObj = {
    id: sessionId,
    sock: null,
    store: null,
    currentQr: null,
    qrDataUrl: null,
    connectionState: 'DISCONNECTED', // 'DISCONNECTED' | 'SCAN_QR' | 'CONNECTING' | 'CONNECTED'
    connectedUser: null,
    reconnectTimer: null
  };

  sessions.set(sessionId, sessionObj);
  await initSession(sessionObj);
  return sessionObj;
}

/**
 * Safely terminates and removes all event listeners from an existing socket.
 * Prevents dual-socket conflicts and ghost connections on reconnects.
 */
function destroySocket(sessionObj) {
  if (!sessionObj || !sessionObj.sock) return;
  const sid = sessionObj.id;
  console.log(`[SOCKET_TEARDOWN] [${sid}] Cleaning up existing socket to prevent ghost/zombie connections...`);
  logger.info(`[WhatsApp Gateway] [${sid}] Cleaning up existing socket...`);
  try {
    sessionObj.sock.ev.removeAllListeners();
  } catch (e) {
    console.warn(`[SOCKET_TEARDOWN] [${sid}] Error removing event listeners: ${e.message}`);
  }
  try {
    if (sessionObj.sock.ws) {
      sessionObj.sock.ws.terminate();
    }
  } catch (e) {
    console.warn(`[SOCKET_TEARDOWN] [${sid}] Error terminating ws: ${e.message}`);
  }
  try {
    sessionObj.sock.end(new Error('Socket teardown before reconnect'));
  } catch (e) {
    // ignore
  }
  sessionObj.sock = null;
  sessionObj.store = null;
  console.log(`[SOCKET_TEARDOWN] [${sid}] Socket teardown complete.`);
}

/**
 * Initializes a Baileys socket for a specific branch/client session.
 */
async function initSession(sessionObj) {
  const sessionId = sessionObj.id;
  const sessionDir = path.join(AUTH_DIR, sessionId);

  if (!fs.existsSync(sessionDir)) {
    fs.mkdirSync(sessionDir, { recursive: true });
  }

  if (sessionObj.reconnectTimer) {
    clearTimeout(sessionObj.reconnectTimer);
    sessionObj.reconnectTimer = null;
  }

  // CRITICAL: Destroy any prior socket before instantiating a new one
  destroySocket(sessionObj);

  sessionObj.connectionState = 'CONNECTING';
  console.log(`[GATEWAY_INIT] [${sessionId}] Initializing Baileys session... (authDir: ${sessionDir})`);
  logger.info(`[WhatsApp Gateway] Initializing session [${sessionId}]...`);

  try {
    const { state, saveCreds } = await useMultiFileAuthState(sessionDir);

    // Fetch version safely with an 8-second timeout to prevent hanging if GitHub raw is slow/unreachable
    let version = [2, 3000, 1043857760];
    try {
      const v = await Promise.race([
        fetchLatestBaileysVersion(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 8000))
      ]);
      if (v && v.version) {
        version = v.version;
        console.log(`[GATEWAY_INIT] [${sessionId}] Using latest Baileys protocol version: ${version.join('.')}`);
      }
    } catch (verErr) {
      console.warn(`[GATEWAY_INIT] [${sessionId}] Using fallback Baileys version: ${verErr.message}`);
      logger.warn(`[WhatsApp Gateway] Using fallback Baileys version: ${verErr.message}`);
    }

    const cachedSignalKeyStore = makeCacheableSignalKeyStore(state.keys, logger);
    const keys = typeof addTransactionCapability === 'function'
      ? addTransactionCapability(cachedSignalKeyStore, logger, { maxCommitRetries: 5, delayBetweenTriesMs: 50 })
      : cachedSignalKeyStore;

    const sock = makeWASocket({
      version,
      auth: {
        creds: state.creds,
        keys
      },
      msgRetryCounterCache,
      markOnlineOnConnect: true,
      printQRInTerminal: false,
      logger: pino({ level: 'info' }),
      browser: Browsers.ubuntu('Chrome'),
      keepAliveIntervalMs: 15000,
      connectTimeoutMs: 60000,
      defaultQueryTimeoutMs: 60000,
      retryRequestDelayMs: 250,
      syncFullHistory: false,
      getMessage: async (key) => {
        if (!key || !key.id) return undefined;
        console.log(`[MSG_RETRY_REQ] [${sessionId}] Decryption retry requested by WhatsApp for msgId: ${key.id}, jid: ${key.remoteJid}`);
        logger.info({ jid: key.remoteJid, id: key.id, participant: key.participant }, '[WhatsApp Gateway] getMessage retry requested by WhatsApp');
        if (sessionObj.store) {
          try {
            const msg = await sessionObj.store.loadMessage(key.remoteJid, key.id);
            if (msg && msg.message) {
              console.log(`[MSG_RETRY_OK] [${sessionId}] Resolved via session store for msgId: ${key.id}`);
              logger.info({ id: key.id }, '[WhatsApp Gateway] Resolved retry via session store');
              return wrapProtoMessage(msg.message);
            }
          } catch (e) {
            // ignore
          }
        }
        const storeKey = `${key.remoteJid || ''}:${key.id}`;
        if (messageStore.has(storeKey)) {
          console.log(`[MSG_RETRY_OK] [${sessionId}] Resolved via memory rawKey for msgId: ${key.id}`);
          logger.info({ id: key.id, storeKey }, '[WhatsApp Gateway] Resolved retry via memory rawKey');
          return wrapProtoMessage(messageStore.get(storeKey));
        }
        const normJid = normalizeJid(key.remoteJid);
        const normKey = `${normJid}:${key.id}`;
        if (messageStore.has(normKey)) {
          console.log(`[MSG_RETRY_OK] [${sessionId}] Resolved via memory normKey for msgId: ${key.id}`);
          logger.info({ id: key.id, normKey }, '[WhatsApp Gateway] Resolved retry via memory normKey');
          return wrapProtoMessage(messageStore.get(normKey));
        }
        if (messageStore.has(key.id)) {
          console.log(`[MSG_RETRY_OK] [${sessionId}] Resolved via memory key.id for msgId: ${key.id}`);
          logger.info({ id: key.id }, '[WhatsApp Gateway] Resolved retry via memory key.id');
          return wrapProtoMessage(messageStore.get(key.id));
        }
        // Fallback to disk persistence
        const diskMsg = loadPersistedMessageFromDisk(key.id);
        if (diskMsg) {
          console.log(`[MSG_RETRY_OK] [${sessionId}] Resolved via disk persistence for msgId: ${key.id}`);
          logger.info({ id: key.id }, '[WhatsApp Gateway] Resolved retry via disk persistence');
          return wrapProtoMessage(diskMsg);
        }
        console.warn(`[MSG_RETRY_MISS] [${sessionId}] getMessage: message content NOT FOUND for msgId: ${key.id}`);
        logger.warn({ id: key.id, jid: key.remoteJid }, '[WhatsApp Gateway] getMessage: message content not found');
        return undefined;
      }
    });

    if (typeof makeInMemoryStore === 'function') {
      try {
        sessionObj.store = makeInMemoryStore({ logger: pino({ level: 'silent' }) });
        sessionObj.store.bind(sock.ev);
      } catch (storeErr) {
        console.warn(`[STORE_BIND_WARN] [${sessionId}] Could not bind isolated session store: ${storeErr.message}`);
      }
    }

    sessionObj.sock = sock;

    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('messages.upsert', async ({ messages }) => {
      for (const msg of messages || []) {
        if (msg?.key && msg?.message) {
          cacheMessage(msg.key, msg.message);

          // Auto-capture lead in Founder CRM if inbound message on official founder session
          if (sessionId === 'cafeqr_official' && !msg.key.fromMe && msg.key.remoteJid && !msg.key.remoteJid.endsWith('@g.us')) {
            try {
              const rawJid = msg.key.remoteJid;
              const senderPhone = rawJid.split('@')[0].split(':')[0];
              const pushName = msg.pushName || '';
              const messageText = msg.message.conversation || msg.message.extendedTextMessage?.text || '';

              if (senderPhone && messageText) {
                console.log(`[FOUNDER_CRM_CAPTURE] Forwarding inbound message from +${senderPhone} (${pushName}) to Founder CRM...`);
                const postData = JSON.stringify({ senderPhone, pushName, messageText });
                const backendUrl = process.env.BACKEND_API_URL || 'http://127.0.0.1:8080';
                
                if (typeof fetch === 'function') {
                  fetch(`${backendUrl}/api/v1/public/leads/auto-capture`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: postData
                  }).then(res => {
                    if (res.ok) console.log(`[FOUNDER_CRM_CAPTURE_OK] Lead captured in Founder CRM for +${senderPhone}`);
                  }).catch(e => {
                    console.warn(`[FOUNDER_CRM_CAPTURE_ERR] Failed to post lead: ${e.message}`);
                  });
                }
              }
            } catch (leadErr) {
              console.warn(`[FOUNDER_CRM_CAPTURE_ERR] Error processing lead capture: ${leadErr.message}`);
            }
          }
        }
      }
    });

    sock.ev.on('messages.update', (updates) => {
      for (const item of updates || []) {
        if (item.update?.status) {
          const statusMap = {
            1: 'PENDING',
            2: 'SERVER_ACK (Single Tick ✓)',
            3: 'DELIVERY_ACK (Double Tick ✓✓)',
            4: 'READ (Blue Tick ✓✓)',
            5: 'PLAYED'
          };
          const statusText = statusMap[item.update.status] || `Code ${item.update.status}`;
          console.log(`[DELIVERY_STATUS] [${sessionId}] Msg: ${item.key?.id} -> ${item.key?.remoteJid}: ${statusText}`);
          logger.info({ id: item.key?.id, jid: item.key?.remoteJid, status: statusText }, `[WhatsApp Gateway] [${sessionId}] Delivery status update`);
        }
      }
    });

    sock.ev.on('connection.update', async (update) => {
      const { connection, lastDisconnect, qr } = update;

      if (qr) {
        sessionObj.currentQr = qr;
        sessionObj.connectionState = 'SCAN_QR';
        try {
          sessionObj.qrDataUrl = await qrcode.toDataURL(qr, {
            margin: 2,
            width: 320,
            color: { dark: '#0f172a', light: '#ffffff' }
          });
          console.log(`[QR_GENERATED] [${sessionId}] New QR code generated (${sessionObj.qrDataUrl?.length || 0} chars). Waiting for user scan...`);
          logger.info(`[WhatsApp Gateway] [${sessionId}] New QR code generated successfully (${sessionObj.qrDataUrl?.length || 0} chars). Waiting for scan...`);
        } catch (e) {
          console.error(`[QR_ERROR] [${sessionId}] Failed to convert QR to Data URL:`, e.message);
          logger.error({ err: e, sessionId }, 'Failed to convert QR to Data URL');
        }
      }

      if (connection === 'close') {
        sessionObj.currentQr = null;
        sessionObj.qrDataUrl = null;

        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const errorReason = lastDisconnect?.error?.message || 'Unknown reason';
        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
        sessionObj.connectionState = 'DISCONNECTED';

        console.error(`[CONNECTION_CLOSED] [${sessionId}] Disconnected. Code: ${statusCode}, Reason: "${errorReason}", ShouldReconnect: ${shouldReconnect}`);
        logger.warn(`[WhatsApp Gateway] [${sessionId}] Connection closed. Code: ${statusCode}. Reconnect: ${shouldReconnect}`);

        if (statusCode === DisconnectReason.loggedOut) {
          sessionObj.connectedUser = null;
          console.warn(`[LOGGED_OUT] [${sessionId}] Session logged out by WhatsApp device. Clearing credentials...`);
          logger.info(`[WhatsApp Gateway] [${sessionId}] Session logged out. Clearing auth files.`);
          destroySocket(sessionObj);
          try {
            fs.rmSync(sessionDir, { recursive: true, force: true });
            fs.mkdirSync(sessionDir, { recursive: true });
          } catch (err) {
            console.error(`[AUTH_RESET_ERR] [${sessionId}] Error cleaning session directory:`, err.message);
            logger.error({ err, sessionId }, 'Error cleaning session directory');
          }
          // Restart clean for new scan
          initSession(sessionObj);
        } else if (shouldReconnect) {
          // Rapid restart on WhatsApp's normal stream-restart (515) after QR pairing
          const delay = statusCode === DisconnectReason.restartRequired ? 500 : 3000;
          console.log(`[RECONNECT_SCHEDULED] [${sessionId}] Reconnecting session in ${delay}ms...`);
          sessionObj.reconnectTimer = setTimeout(() => {
            initSession(sessionObj);
          }, delay);
        }
      } else if (connection === 'open') {
        sessionObj.currentQr = null;
        sessionObj.qrDataUrl = null;
        sessionObj.connectionState = 'CONNECTED';
        const user = sock.user;
        const rawPhone = (user?.id || '').split(':')[0].split('@')[0];
        sessionObj.connectedUser = {
          id: user?.id,
          phone: rawPhone,
          name: user?.name || user?.notify || 'Cafe QR Branch'
        };
        console.log(`[CONNECTION_OPEN] [${sessionId}] WhatsApp linked successfully! Phone: +${rawPhone}, Identity: "${sessionObj.connectedUser.name}"`);
        logger.info(`[WhatsApp Gateway] [${sessionId}] Connected successfully! Phone: +${rawPhone}`);
        try {
          await sock.sendPresenceUpdate('available');
          console.log(`[PRESENCE_OK] [${sessionId}] Presence set to 'available'`);
          logger.info(`[WhatsApp Gateway] [${sessionId}] Sent presence update 'available'`);
        } catch (presErr) {
          console.warn(`[PRESENCE_ERR] [${sessionId}] Could not set presence:`, presErr.message);
          logger.debug(`[WhatsApp Gateway] Could not send presence update: ${presErr.message}`);
        }
      }
    });

  } catch (error) {
    console.error(`[GATEWAY_INIT_ERR] [${sessionId}] Session initialization failed:`, error.message, error.stack);
    logger.error({ err: error, sessionId }, `[WhatsApp Gateway] Session [${sessionId}] initialization failed`);
    sessionObj.connectionState = 'DISCONNECTED';
    sessionObj.reconnectTimer = setTimeout(() => initSession(sessionObj), 5000);
  }
}

/**
 * Format phone number to WhatsApp JID (e.g. 919847920009@s.whatsapp.net)
 */
function formatToJid(inputPhone) {
  if (!inputPhone) return null;
  let digits = String(inputPhone).replace(/[^0-9]/g, '');
  if (!digits) return null;

  // Remove leading 0 if 11 digits (e.g. 09496958200 -> 9496958200)
  if (digits.length === 11 && digits.startsWith('0')) {
    digits = digits.substring(1);
  }

  // If 10 digits (Standard Indian mobile number without country code), prepend 91
  if (digits.length === 10) {
    digits = '91' + digits;
  }
  return `${digits}@s.whatsapp.net`;
}

// ─────────────────────────────────────────────────────────────────────────────
// REST API ENDPOINTS
// ─────────────────────────────────────────────────────────────────────────────

// 1. Connection status (accepts query param ?sessionId=...)
app.get('/api/status', async (req, res) => {
  const session = await getOrCreateSession(req.query.sessionId);
  res.json({
    success: true,
    sessionId: session.id,
    status: session.connectionState,
    hasQr: !!session.qrDataUrl,
    user: session.connectedUser
  });
});

// 2. Fetch QR code (accepts query param ?sessionId=...)
app.get('/api/qr', async (req, res) => {
  const session = await getOrCreateSession(req.query.sessionId);
  if (session.connectionState === 'CONNECTED') {
    return res.json({
      success: true,
      sessionId: session.id,
      status: 'CONNECTED',
      message: 'Already connected',
      user: session.connectedUser
    });
  }
  res.json({
    success: true,
    sessionId: session.id,
    status: session.connectionState,
    qr: session.qrDataUrl
  });
});

// 3. Send digital bill (with branch session and optional fallback session)
app.post('/api/send-bill', async (req, res) => {
  const { sessionId, fallbackSessionId, phone, text, pdfBase64, filename } = req.body;
  console.log(`[SEND_BILL_REQ] Incoming bill request: session=${sessionId}, fallback=${fallbackSessionId}, phone=${phone}, hasText=${!!text}, hasPdf=${!!pdfBase64}`);
  logger.info({ sessionId, fallbackSessionId, phone, hasText: !!text, hasPdf: !!pdfBase64 }, '[WhatsApp Gateway] /api/send-bill request received');

  let activeSession = null;
  const primaryId = normalizeSessionId(sessionId);
  const primary = sessions.get(primaryId);

  if (primary && primary.connectionState === 'CONNECTED' && primary.sock) {
    activeSession = primary;
  } else if (fallbackSessionId) {
    const fallbackId = normalizeSessionId(fallbackSessionId);
    const fallback = sessions.get(fallbackId);
    if (fallback && fallback.connectionState === 'CONNECTED' && fallback.sock) {
      activeSession = fallback;
      console.log(`[SEND_BILL_FALLBACK] Primary [${primaryId}] not connected. Using fallback session [${fallbackId}]`);
      logger.info(`[WhatsApp Gateway] Primary [${primaryId}] not connected. Using fallback session [${fallbackId}]`);
    }
  }

  // If still not found in active memory, attempt to load primary
  if (!activeSession) {
    const loaded = await getOrCreateSession(sessionId);
    if (loaded && (loaded.connectionState === 'CONNECTED' || loaded.connectionState === 'CONNECTING') && loaded.sock) {
      activeSession = loaded;
    }
  }

  // Fallback to official session or any connected session if requested session is not connected
  if (!activeSession || activeSession.connectionState !== 'CONNECTED' || !activeSession.sock) {
    const officialSession = sessions.get('cafeqr_official');
    if (officialSession && officialSession.connectionState === 'CONNECTED' && officialSession.sock) {
      activeSession = officialSession;
      console.log(`[SEND_BILL_OFFICIAL] Using connected official session [cafeqr_official]`);
    } else {
      for (const [id, sess] of sessions.entries()) {
        if (sess && sess.connectionState === 'CONNECTED' && sess.sock) {
          activeSession = sess;
          console.log(`[SEND_BILL_AUTO_FALLBACK] Using connected session [${id}]`);
          break;
        }
      }
    }
  }

  if (!activeSession || activeSession.connectionState !== 'CONNECTED' || !activeSession.sock) {
    console.warn(`[SEND_BILL_REJECTED] Session not connected: primary=${primaryId}, state=${activeSession?.connectionState}`);
    logger.warn({ primaryId, fallbackSessionId, state: activeSession?.connectionState }, '[WhatsApp Gateway] Cannot send bill: WhatsApp session is not connected');
    const hasCreds = fs.existsSync(path.join(AUTH_DIR, primaryId, 'creds.json'));
    const message = hasCreds
      ? `WhatsApp session for branch [${primaryId}] is currently synchronizing with WhatsApp servers. Please wait a moment.`
      : `WhatsApp is not linked for branch [${primaryId}]. Scan QR in Configurations -> Customers to link.`;
    return res.status(503).json({
      success: false,
      message
    });
  }

  const rawDigits = String(phone || '').replace(/[^0-9]/g, '');
  const formattedJid = formatToJid(phone);
  if (!formattedJid) {
    console.warn(`[SEND_BILL_ERR] Invalid customer phone format: "${phone}"`);
    logger.warn({ phone }, '[WhatsApp Gateway] Invalid customer phone format');
    return res.status(400).json({
      success: false,
      message: 'Invalid customer phone number'
    });
  }

  if (!text && !pdfBase64) {
    console.warn('[SEND_BILL_ERR] Neither text nor PDF payload was provided');
    logger.warn('[WhatsApp Gateway] Neither text nor PDF payload was provided');
    return res.status(400).json({
      success: false,
      message: 'Message text or PDF document is required'
    });
  }

  // Pre-flight check: Verify number exists on WhatsApp and get canonical JID to eliminate single-tick dead drops
  let targetJid = formattedJid;
  try {
    console.log(`[ON_WHATSAPP_CHECK] Verifying registration for ${formattedJid}...`);
    const results = await activeSession.sock.onWhatsApp(formattedJid);
    const checked = Array.isArray(results) && results.length > 0 ? results[0] : null;
    if (checked && checked.exists) {
      // CRITICAL: NEVER route direct 1-to-1 customer bills to an @lid!
      // If checked.jid is a standard @s.whatsapp.net phone JID, use it; otherwise strictly preserve formattedJid.
      if (checked.jid && checked.jid.endsWith('@s.whatsapp.net')) {
        targetJid = checked.jid;
      } else {
        targetJid = formattedJid;
      }
      console.log(`[ON_WHATSAPP_OK] Verified: ${phone} -> canonical targetJid: ${targetJid} (raw check jid: ${checked.jid})`);
    } else {
      console.warn(`[ON_WHATSAPP_NOT_FOUND] Number ${phone} (${formattedJid}) is NOT registered on WhatsApp`);
      return res.status(400).json({
        success: false,
        message: `Phone number +${rawDigits} is not registered on WhatsApp.`
      });
    }
  } catch (checkErr) {
    console.warn(`[ON_WHATSAPP_BYPASS] Check failed (${checkErr.message}), proceeding with ${formattedJid}`);
  }

  try {
    let sentMsg = null;

    if (pdfBase64) {
      console.log(`[DISPATCH_MSG] Sending PDF invoice to ${targetJid} (${filename || 'Tax_Invoice.pdf'})...`);
      const buffer = Buffer.from(pdfBase64, 'base64');
      sentMsg = await activeSession.sock.sendMessage(targetJid, {
        document: buffer,
        mimetype: 'application/pdf',
        fileName: filename || 'Tax_Invoice.pdf',
        caption: text ? String(text).trim() : '📄 Official Tax Invoice PDF'
      });
      if (sentMsg?.key && sentMsg?.message) {
        cacheMessage(sentMsg.key, sentMsg.message);
      }
      console.log(`[DISPATCH_OK] PDF invoice sent to ${targetJid}, msgId: ${sentMsg?.key?.id}`);
      logger.info(`[WhatsApp Gateway] [${activeSession.id}] Sent PDF invoice with digital receipt caption to ${targetJid}`);
    } else if (text) {
      console.log(`[DISPATCH_MSG] Sending text digital bill to ${targetJid}...`);
      sentMsg = await activeSession.sock.sendMessage(targetJid, { text: String(text).trim() });
      if (sentMsg?.key && sentMsg?.message) {
        cacheMessage(sentMsg.key, sentMsg.message);
      }
      console.log(`[DISPATCH_OK] Digital bill sent to ${targetJid}, msgId: ${sentMsg?.key?.id}`);
      logger.info(`[WhatsApp Gateway] [${activeSession.id}] Sent digital bill text to ${targetJid}`);
    }

    console.log(`[SEND_BILL_SUCCESS] Msg ID: ${sentMsg?.key?.id} dispatched to WhatsApp servers. Awaiting DELIVERY_ACK (double tick).`);
    logger.info({ messageId: sentMsg?.key?.id, jid: targetJid, sessionId: activeSession.id }, '[WhatsApp Gateway] Message dispatched successfully to WhatsApp server (Awaiting DELIVERY_ACK)');

    return res.json({
      success: true,
      sessionId: activeSession.id,
      messageId: sentMsg?.key?.id,
      timestamp: Date.now()
    });
  } catch (err) {
    console.error(`[SEND_BILL_FATAL] Error sending to ${targetJid}:`, err.message, err.stack);
    logger.error({ err: err.message, stack: err.stack, jid: targetJid, sessionId: activeSession.id }, '[WhatsApp Gateway] Failed to send message via Baileys socket');
    return res.status(500).json({
      success: false,
      message: err.message || 'Failed to dispatch WhatsApp message'
    });
  }
});

// 4. Send test message
app.post('/api/test-message', async (req, res) => {
  const { sessionId, phone } = req.body;
  console.log(`[TEST_MSG_REQ] Incoming test message request: session=${sessionId}, phone=${phone}`);
  const session = await getOrCreateSession(sessionId);

  // If session is currently reconnecting or synchronizing, wait up to 8 seconds for it to open
  if (session && session.connectionState === 'CONNECTING') {
    console.log(`[TEST_MSG_WAIT] Session [${session.id}] is CONNECTING. Waiting up to 8s...`);
    const startWait = Date.now();
    while (Date.now() - startWait < 8000) {
      if (session.connectionState === 'CONNECTED' && session.sock) {
        break;
      }
      await new Promise(r => setTimeout(r, 400));
    }
  }

  if (session.connectionState !== 'CONNECTED' || !session.sock) {
    const cleanId = normalizeSessionId(sessionId);
    const hasCreds = fs.existsSync(path.join(AUTH_DIR, cleanId, 'creds.json'));
    console.warn(`[TEST_MSG_REJECTED] Session [${cleanId}] not connected. hasCreds: ${hasCreds}, state: ${session?.connectionState}`);
    const message = hasCreds
      ? `WhatsApp session is currently synchronizing with WhatsApp servers. Please wait a few seconds and try again.`
      : `WhatsApp is not connected for this branch. Please scan the QR code first.`;
    return res.status(503).json({
      success: false,
      message
    });
  }

  const targetPhone = phone || session.connectedUser?.phone;
  if (!targetPhone) {
    console.warn('[TEST_MSG_ERR] No target phone number provided');
    return res.status(400).json({ success: false, message: 'Phone number is required' });
  }

  const formattedJid = formatToJid(targetPhone);
  if (!formattedJid) {
    console.warn(`[TEST_MSG_ERR] Invalid target phone format: "${targetPhone}"`);
    return res.status(400).json({ success: false, message: 'Invalid target phone format' });
  }

  let targetJid = formattedJid;
  try {
    console.log(`[TEST_MSG_CHECK] Verifying registration for ${formattedJid}...`);
    const results = await session.sock.onWhatsApp(formattedJid);
    const checked = Array.isArray(results) && results.length > 0 ? results[0] : null;
    if (checked && checked.exists) {
      if (checked.jid && checked.jid.endsWith('@s.whatsapp.net')) {
        targetJid = checked.jid;
      } else {
        targetJid = formattedJid;
      }
      console.log(`[TEST_MSG_CHECK_OK] Verified: ${targetPhone} -> canonical targetJid: ${targetJid} (raw check jid: ${checked.jid})`);
    } else {
      console.warn(`[TEST_MSG_CHECK_FAIL] Number ${targetPhone} (${formattedJid}) is NOT registered on WhatsApp`);
      return res.status(400).json({
        success: false,
        message: `Phone number +${String(targetPhone).replace(/[^0-9]/g, '')} is not registered on WhatsApp.`
      });
    }
  } catch (checkErr) {
    console.warn(`[TEST_MSG_CHECK_BYPASS] Check failed (${checkErr.message}), proceeding with ${formattedJid}`);
  }

  try {
    const branchLabel = session.id.replace('org_', 'Branch ');
    const testText = `✅ *Cafe QR WhatsApp Gateway Test*\n\nYour WhatsApp Digital Bill service is connected and active for *${branchLabel}*! 🚀\n\n_Time: ${new Date().toLocaleString()}_`;
    console.log(`[TEST_MSG_DISPATCH] Dispatching test text to ${targetJid}...`);
    const testMsg = await session.sock.sendMessage(targetJid, { text: testText });
    if (testMsg?.key && testMsg?.message) {
      cacheMessage(testMsg.key, testMsg.message);
    }
    console.log(`[TEST_MSG_SUCCESS] Test message sent successfully to ${targetJid}, msgId: ${testMsg?.key?.id}`);
    return res.json({ success: true, message: `Test message sent to ${targetPhone}` });
  } catch (err) {
    console.error(`[TEST_MSG_FATAL] Error dispatching test message to ${targetJid}:`, err.message, err.stack);
    return res.status(500).json({ success: false, message: err.message });
  }
});

// 5. Disconnect / Unlink session
app.post('/api/disconnect', async (req, res) => {
  const { sessionId } = req.body;
  const cleanId = normalizeSessionId(sessionId);
  const sessionDir = path.join(AUTH_DIR, cleanId);
  console.log(`[DISCONNECT_REQ] Disconnecting session [${cleanId}]...`);

  try {
    const session = sessions.get(cleanId);
    if (session) {
      if (session.sock) {
        console.log(`[DISCONNECT_LOGOUT] Logging out socket for [${cleanId}]...`);
        await session.sock.logout().catch((e) => console.warn(`[DISCONNECT_WARN] Logout error: ${e.message}`));
      }
      destroySocket(session);
    }

    if (fs.existsSync(sessionDir)) {
      console.log(`[DISCONNECT_PURGE] Removing auth directory: ${sessionDir}`);
      fs.rmSync(sessionDir, { recursive: true, force: true });
      fs.mkdirSync(sessionDir, { recursive: true });
    }

    if (session) {
      session.currentQr = null;
      session.qrDataUrl = null;
      session.connectedUser = null;
      session.connectionState = 'DISCONNECTED';
      console.log(`[DISCONNECT_RESTART] Scheduling clean initSession for [${cleanId}] in 1s...`);
      setTimeout(() => initSession(session), 1000);
    }

    return res.json({ success: true, message: `Disconnected session [${cleanId}]. You can now scan a new number.` });
  } catch (err) {
    console.error(`[DISCONNECT_FATAL] Error disconnecting session [${cleanId}]:`, err.message, err.stack);
    logger.error({ err, sessionId: cleanId }, 'Error during disconnect');
    return res.status(500).json({ success: false, message: err.message });
  }
});

// 6. Clear contact session (heals stale contact Signal ratchets without unlinking the branch)
app.post('/api/clear-contact-session', async (req, res) => {
  const { sessionId, phone } = req.body;
  const cleanId = normalizeSessionId(sessionId);
  const sessionDir = path.join(AUTH_DIR, cleanId);
  console.log(`[CLEAR_CONTACT_REQ] [${cleanId}] Request to clear contact session for phone: ${phone}`);

  if (!phone) {
    return res.status(400).json({ success: false, message: 'Phone number is required' });
  }

  const digits = String(phone).replace(/[^0-9]/g, '');
  if (!digits) {
    return res.status(400).json({ success: false, message: 'Invalid phone number' });
  }

  try {
    let clearedFiles = 0;
    if (fs.existsSync(sessionDir)) {
      const files = fs.readdirSync(sessionDir);
      let cleanDigits = digits;
      if (cleanDigits.length === 10) cleanDigits = '91' + cleanDigits;
      const altDigits = digits.length === 10 ? digits : (digits.startsWith('91') ? digits.slice(2) : digits);

      for (const file of files) {
        // ONLY clear session/sender-key files for THIS specific contact
        // NEVER delete files matching a broad 'session-' prefix as that wipes all other customers' ratchets!
        const matchesContact = file.includes(digits) || file.includes(cleanDigits) || file.includes(altDigits);
        if (matchesContact && (file.startsWith('session-') || file.startsWith('sender-key-'))) {
          try {
            fs.unlinkSync(path.join(sessionDir, file));
            clearedFiles++;
          } catch (e) {
            // ignore
          }
        }
      }
    }
    const session = sessions.get(cleanId);
    if (session) {
      console.log(`[CLEAR_CONTACT_RESTART] [${cleanId}] Destroying socket and restarting clean session to evict cached Signal keys for ${digits}...`);
      destroySocket(session);
      setTimeout(() => initSession(session), 600);
    }

    console.log(`[CLEAR_CONTACT_OK] [${cleanId}] Cleared ${clearedFiles} session file(s) for ${digits}`);
    return res.json({
      success: true,
      clearedFiles,
      message: `Cleared ${clearedFiles} contact session file(s). Next message to each contact will establish a fresh Signal key ratchet.`
    });
  } catch (err) {
    console.error(`[CLEAR_CONTACT_FATAL] [${cleanId}] Error clearing contact session:`, err.message, err.stack);
    logger.error({ err, sessionId: cleanId }, 'Error clearing contact session');
    return res.status(500).json({ success: false, message: err.message });
  }
});

// 7. Health check & Active sessions inventory
app.get('/health', (req, res) => {
  const inventory = [];
  sessions.forEach((s, id) => {
    inventory.push({
      sessionId: id,
      status: s.connectionState,
      phone: s.connectedUser?.phone || null
    });
  });

  res.json({
    status: 'ok',
    totalSessions: sessions.size,
    sessions: inventory,
    uptime: process.uptime()
  });
});

// Auto-restore previously linked sessions on container startup
function autoRestoreExistingSessions() {
  try {
    if (!fs.existsSync(AUTH_DIR)) return;
    const entries = fs.readdirSync(AUTH_DIR, { withFileTypes: true });
    console.log(`[AUTO_RESTORE] Scanning ${AUTH_DIR} for existing sessions...`);
    for (const entry of entries) {
      if (entry.isDirectory() && entry.name !== 'messages') {
        const sid = entry.name;
        // Verify creds.json exists in this folder
        if (fs.existsSync(path.join(AUTH_DIR, sid, 'creds.json'))) {
          console.log(`[AUTO_RESTORE] Found saved credentials for session: [${sid}]. Restoring...`);
          logger.info(`[WhatsApp Gateway] Auto-restoring existing session: [${sid}]`);
          getOrCreateSession(sid);
        }
      }
    }
  } catch (err) {
    console.error('[AUTO_RESTORE_ERR] Error scanning for existing sessions:', err.message);
    logger.error({ err }, '[WhatsApp Gateway] Error scanning for existing sessions');
  }
}

async function startGateway() {
  const baileys = await import('@whiskeysockets/baileys');
  ({
    default: makeWASocket,
    useMultiFileAuthState,
    DisconnectReason,
    fetchLatestBaileysVersion,
    makeCacheableSignalKeyStore,
    makeInMemoryStore,
    addTransactionCapability,
    Browsers,
    proto
  } = baileys);

  app.listen(PORT, () => {
    console.log(`[GATEWAY_STARTUP] Multi-tenant WhatsApp Gateway listening on port ${PORT}`);
    logger.info(`[WhatsApp Gateway] Multi-tenant server running on port ${PORT}`);
    autoRestoreExistingSessions();
  });
}

startGateway().catch((err) => {
  console.error('[GATEWAY_STARTUP_ERR] Failed to start gateway:', err);
  process.exit(1);
});
