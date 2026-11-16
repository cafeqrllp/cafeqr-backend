/**
 * waha_client.js — Dedicated WhatsApp HTTP API (WAHA) Client & Resilience Module
 *
 * Implements a production-grade REST client for WAHA Core 2026.10.1 (NOWEB engine)
 * featuring:
 * 1. SlidingWindowCircuitBreaker with atomic CAS probe lock (Resilience4j standard).
 * 2. Exponential backoff with Decorrelated Full Jitter for transient errors (429, 502, 503, 504, ECONNRESET).
 * 3. Render Free Tier 50s cold-start wake-up probe (`ping`).
 * 4. Comprehensive session lifecycle operations (get, start, stop, restart, delete, ensureWorking).
 * 5. QR code image streamer with HTTP 400 graceful degradation.
 * 6. Multi-session isolation with dedicated defaults (`cafe_qr_orders`).
 *
 * Modeled on proven patterns from SI Lead Xtract (`waha_sender.py`) & waha_migration_plan.md.
 */

'use strict';

const http = require('http');
const https = require('https');
const { URL } = require('url');

/**
 * Circuit Breaker States
 */
const BreakerState = Object.freeze({
  CLOSED: 'CLOSED',
  OPEN: 'OPEN',
  HALF_OPEN: 'HALF_OPEN'
});

/**
 * SlidingWindowCircuitBreaker
 *
 * Maintains a rolling ring buffer of the last N calls (default 30).
 * Trips to OPEN when minimum calls threshold (default 10) is met and failure rate >= 50%.
 * During HALF_OPEN, employs an atomic CAS probe permit (`isProbing`) allowing exactly
 * one probe call to evaluate server recovery while concurrent requests are rejected.
 */
class SlidingWindowCircuitBreaker {
  /**
   * @param {Object} options
   * @param {number} [options.windowSize=30] - Number of calls in rolling buffer
   * @param {number} [options.failureThreshold=0.5] - Failure rate fraction (0.5 = 50%) or percent
   * @param {number} [options.minCalls=10] - Minimum calls before tripping can occur
   * @param {number} [options.cooldownMs=60000] - Duration in ms to stay OPEN before HALF_OPEN
   */
  constructor(options = {}) {
    this.windowSize = Math.max(1, options.windowSize || 30);
    const rawThreshold = options.failureThreshold != null ? options.failureThreshold : 0.5;
    this.failureThreshold = rawThreshold > 1 ? rawThreshold / 100 : rawThreshold;
    this.minCalls = Math.max(1, options.minCalls != null ? options.minCalls : 10);
    this.cooldownMs = options.cooldownMs != null ? options.cooldownMs : 60000;

    this.state = BreakerState.CLOSED;
    this.history = []; // boolean[]: true for success, false for failure
    this.openedAt = 0;
    this.isProbing = false; // Atomic CAS permit for HALF_OPEN
  }

  /**
   * Evaluates if a new request is permitted.
   * In HALF_OPEN, guarantees only 1 probe call passes; concurrent calls are blocked.
   * @returns {boolean}
   */
  allowRequest() {
    const now = Date.now();

    if (this.state === BreakerState.OPEN) {
      if (now - this.openedAt >= this.cooldownMs) {
        this.state = BreakerState.HALF_OPEN;
      } else {
        return false;
      }
    }

    if (this.state === BreakerState.HALF_OPEN) {
      // Atomic CAS probe permit: only the first caller gets the permit
      if (!this.isProbing) {
        this.isProbing = true;
        return true;
      }
      return false;
    }

    return true; // CLOSED
  }

  /**
   * Records a successful execution.
   */
  recordSuccess() {
    if (this.state === BreakerState.HALF_OPEN) {
      this.state = BreakerState.CLOSED;
      this.history = [];
      this.isProbing = false;
      this.openedAt = 0;
    } else if (this.state === BreakerState.CLOSED) {
      this.pushHistory(true);
      this.evaluateState();
    }
  }

  /**
   * Records a failed execution.
   */
  recordFailure() {
    if (this.state === BreakerState.HALF_OPEN) {
      this.state = BreakerState.OPEN;
      this.openedAt = Date.now();
      this.isProbing = false;
    } else if (this.state === BreakerState.CLOSED) {
      this.pushHistory(false);
      this.evaluateState();
    }
  }

  /**
   * Pushes result to ring buffer.
   * @private
   */
  pushHistory(success) {
    this.history.push(Boolean(success));
    if (this.history.length > this.windowSize) {
      this.history.shift();
    }
  }

  /**
   * Evaluates if the current ring buffer failure rate breaches threshold.
   * @private
   */
  evaluateState() {
    if (this.history.length < this.minCalls) {
      return;
    }
    const failureCount = this.history.filter(h => !h).length;
    const failureRate = failureCount / this.history.length;
    if (failureRate >= this.failureThreshold) {
      this.state = BreakerState.OPEN;
      this.openedAt = Date.now();
      this.isProbing = false;
    }
  }

  /**
   * Returns current breaker state, taking cooldown expiration into account.
   * @returns {string} BreakerState
   */
  getState() {
    if (this.state === BreakerState.OPEN && (Date.now() - this.openedAt >= this.cooldownMs)) {
      this.state = BreakerState.HALF_OPEN;
    }
    return this.state;
  }

  /**
   * Returns diagnostic stats for monitoring.
   */
  getStats() {
    const currentState = this.getState();
    const totalCalls = this.history.length;
    const failureCount = this.history.filter(h => !h).length;
    const failureRate = totalCalls > 0 ? (failureCount / totalCalls) : 0;
    return {
      state: currentState,
      totalCalls,
      failureCount,
      failureRate: Math.round(failureRate * 100) / 100,
      isProbing: this.isProbing,
      openedAt: this.openedAt
    };
  }

  /**
   * Resets breaker to pristine CLOSED state.
   */
  reset() {
    this.state = BreakerState.CLOSED;
    this.history = [];
    this.openedAt = 0;
    this.isProbing = false;
  }
}

/**
 * Custom Error for Circuit Breaker rejections
 */
class CircuitBreakerOpenError extends Error {
  constructor(message, sessionName) {
    super(message || `Circuit breaker is OPEN for session: ${sessionName}`);
    this.name = 'CircuitBreakerOpenError';
    this.sessionName = sessionName;
  }
}

/**
 * Universal HTTP/HTTPS request dispatcher supporting both global fetch (Node 18+)
 * and built-in http/https fallback with strict timeout and buffer handling.
 */
async function dispatchHttpRequest(urlStr, options = {}) {
  const timeoutMs = options.timeoutMs || 15000;
  const method = (options.method || 'GET').toUpperCase();
  const headers = Object.assign({}, options.headers);
  const body = options.body;

  // Prefer globalThis.fetch if available
  if (typeof globalThis.fetch === 'function') {
    let controller = null;
    let timer = null;
    let signal = options.signal;

    if (!signal) {
      controller = new AbortController();
      signal = controller.signal;
      timer = setTimeout(() => {
        controller.abort(new Error(`Request timed out after ${timeoutMs}ms`));
      }, timeoutMs);
    }

    try {
      const fetchOpts = {
        method,
        headers,
        signal
      };

      if (body !== undefined && body !== null && method !== 'GET' && method !== 'HEAD') {
        if (typeof body === 'object' && !Buffer.isBuffer(body)) {
          if (!headers['Content-Type']) {
            headers['Content-Type'] = 'application/json';
          }
          fetchOpts.body = JSON.stringify(body);
        } else {
          fetchOpts.body = body;
        }
      }

      const res = await globalThis.fetch(urlStr, fetchOpts);
      const contentType = res.headers.get('content-type') || '';
      const isJson = contentType.includes('application/json');
      const isImage = contentType.includes('image/');

      let responseData;
      let rawBuffer;

      if (isImage) {
        const arrayBuf = await res.arrayBuffer();
        rawBuffer = Buffer.from(arrayBuf);
        responseData = rawBuffer;
      } else {
        const text = await res.text();
        rawBuffer = Buffer.from(text);
        if (isJson) {
          try {
            responseData = JSON.parse(text);
          } catch (e) {
            responseData = text;
          }
        } else {
          try {
            responseData = JSON.parse(text);
          } catch (e) {
            responseData = text;
          }
        }
      }

      const headerMap = {};
      res.headers.forEach((v, k) => { headerMap[k.toLowerCase()] = v; });

      return {
        status: res.status,
        statusText: res.statusText,
        ok: res.ok,
        headers: headerMap,
        data: responseData,
        rawBuffer
      };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  // Fallback: Node.js native http/https module
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(urlStr);
    const isHttps = parsedUrl.protocol === 'https:';
    const client = isHttps ? https : http;

    let payload = null;
    if (body !== undefined && body !== null && method !== 'GET' && method !== 'HEAD') {
      if (typeof body === 'object' && !Buffer.isBuffer(body)) {
        payload = JSON.stringify(body);
        if (!headers['Content-Type']) headers['Content-Type'] = 'application/json';
      } else {
        payload = body;
      }
      headers['Content-Length'] = Buffer.byteLength(payload);
    }

    const reqOpts = {
      protocol: parsedUrl.protocol,
      hostname: parsedUrl.hostname,
      port: parsedUrl.port || (isHttps ? 443 : 80),
      path: parsedUrl.pathname + parsedUrl.search,
      method,
      headers,
      timeout: timeoutMs
    };

    const req = client.request(reqOpts, (res) => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const rawBuffer = Buffer.concat(chunks);
        const contentType = (res.headers['content-type'] || '').toLowerCase();
        let data;

        if (contentType.includes('image/')) {
          data = rawBuffer;
        } else {
          const text = rawBuffer.toString('utf8');
          try {
            data = JSON.parse(text);
          } catch (e) {
            data = text;
          }
        }

        resolve({
          status: res.statusCode,
          statusText: res.statusMessage,
          ok: res.statusCode >= 200 && res.statusCode < 300,
          headers: res.headers,
          data,
          rawBuffer
        });
      });
    });

    req.on('timeout', () => {
      req.destroy();
      const err = new Error(`Request timed out after ${timeoutMs}ms`);
      err.code = 'ETIMEDOUT';
      reject(err);
    });

    req.on('error', (err) => {
      reject(err);
    });

    if (payload) {
      req.write(payload);
    }
    req.end();
  });
}

/**
 * WahaClient
 *
 * Dedicated, resilient HTTP client wrapper for WAHA Core 2026.10.1 (NOWEB).
 */
class WahaClient {
  /**
   * @param {Object} [options={}]
   * @param {string} [options.baseUrl] - Base WAHA URL (falls back to WAHA_BASE_URL or Render URL)
   * @param {string} [options.apiKey] - Optional WAHA API Key (falls back to WAHA_API_KEY)
   * @param {string} [options.defaultSession='cafe_qr_orders'] - Default session namespace
   * @param {number} [options.timeoutMs=15000] - Request timeout in ms
   * @param {number} [options.maxRetries=3] - Maximum retry attempts on transient failure
   * @param {Object} [options.breakerOptions] - Options passed to SlidingWindowCircuitBreaker
   */
  constructor(options = {}) {
    const rawUrl = options.baseUrl || process.env.WAHA_BASE_URL || 'http://waha:3000';
    this.baseUrl = String(rawUrl).replace(/\/+$/, '');
    this.apiKey = options.apiKey !== undefined
      ? options.apiKey
      : (process.env.WAHA_API_KEY || process.env.WHATSAPP_API_KEY || 'cafeqr-waha-secret-2026');
    this.defaultSession = options.defaultSession || process.env.WAHA_DEFAULT_SESSION || 'cafe_qr_orders';
    this.timeoutMs = parseInt(options.timeoutMs || process.env.WAHA_TIMEOUT_MS || '15000', 10);
    this.maxRetries = options.maxRetries != null ? options.maxRetries : 3;

    this.circuitBreakers = new Map();
    this.breakerOptions = Object.assign({
      windowSize: 30,
      failureThreshold: 0.5,
      minCalls: 10,
      cooldownMs: 60000
    }, options.breakerOptions);

    // Optional override for custom sleeping in unit tests
    this._sleepFn = options.sleepFn || ((ms) => new Promise(resolve => setTimeout(resolve, ms)));
  }

  /**
   * Obtains or creates the circuit breaker instance for a given session.
   * @param {string} [sessionName]
   * @returns {SlidingWindowCircuitBreaker}
   */
  getCircuitBreaker(sessionName = this.defaultSession) {
    const key = sessionName || this.defaultSession;
    if (!this.circuitBreakers.has(key)) {
      this.circuitBreakers.set(key, new SlidingWindowCircuitBreaker(this.breakerOptions));
    }
    return this.circuitBreakers.get(key);
  }

  /**
   * Checks if an error or HTTP status code is transient and eligible for retry.
   * @param {Error|null} error
   * @param {number|null} statusCode
   * @returns {boolean}
   */
  isTransient(error, statusCode) {
    if (statusCode && [429, 502, 503, 504].includes(Number(statusCode))) {
      return true;
    }
    if (!error) return false;

    const code = error.code || '';
    if (['ECONNRESET', 'ETIMEDOUT', 'ENOTFOUND', 'ECONNREFUSED', 'EAI_AGAIN', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_SOCKET'].includes(code)) {
      return true;
    }

    const msg = String(error.message || '').toLowerCase();
    const transientPhrases = [
      'timed out',
      'timeout',
      '502',
      '503',
      '504',
      '429',
      'connectionpool',
      'unreachable',
      'fetch failed',
      'socket hang up',
      'econnreset',
      'econnrefused'
    ];
    return transientPhrases.some(phrase => msg.includes(phrase));
  }

  /**
   * Calculates exponential backoff delay with Decorrelated Full Jitter.
   * Formula: delay = Math.floor(Math.random() * Math.min(30000, 1500 * Math.pow(2, attempt)))
   * @param {number} attempt - Zero-based retry attempt number (0, 1, 2...)
   * @param {number} [baseInterval=1500]
   * @param {number} [maxCeiling=30000]
   * @returns {number} Delay in milliseconds
   */
  calculateJitter(attempt, baseInterval = 1500, maxCeiling = 30000) {
    const exponentialCap = Math.min(maxCeiling, baseInterval * Math.pow(2, attempt));
    return Math.floor(Math.random() * exponentialCap);
  }

  /**
   * Core resilient HTTP dispatch method.
   * Applies circuit breaker checks and Decorrelated Full Jitter retries.
   *
   * @param {string} method - HTTP method (GET, POST, DELETE, etc.)
   * @param {string} endpoint - Relative path (e.g. '/api/sessions')
   * @param {Object} [options={}]
   * @param {string} [options.sessionName] - Associated session for circuit breaker tracking
   * @param {Object} [options.body] - Request JSON body
   * @param {Object} [options.headers] - Additional HTTP headers
   * @param {number} [options.timeoutMs] - Custom timeout in ms
   * @param {boolean} [options.skipBreaker=false] - Whether to bypass circuit breaker (e.g. ping)
   * @param {number} [options.maxRetries] - Override retry count
   * @returns {Promise<Object>}
   */
  async request(method, endpoint, options = {}) {
    const sessionName = options.sessionName || this.defaultSession;
    const skipBreaker = Boolean(options.skipBreaker);
    const timeoutMs = options.timeoutMs || this.timeoutMs;
    const maxRetries = options.maxRetries != null ? options.maxRetries : this.maxRetries;

    const breaker = skipBreaker ? null : this.getCircuitBreaker(sessionName);

    // 1. Guard check with circuit breaker
    if (breaker && !breaker.allowRequest()) {
      throw new CircuitBreakerOpenError(
        `WAHA circuit breaker is OPEN for session [${sessionName}]. Fast-failing request.`,
        sessionName
      );
    }

    const cleanEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
    const url = `${this.baseUrl}${cleanEndpoint}`;

    const headers = Object.assign({
      'Accept': 'application/json, text/plain, */*'
    }, options.headers);

    if (this.apiKey) {
      headers['X-Api-Key'] = this.apiKey;
    }

    let lastError = null;
    let lastResponse = null;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const response = await dispatchHttpRequest(url, {
          method,
          headers,
          body: options.body,
          timeoutMs
        });

        lastResponse = response;

        // Successful HTTP response
        if (response.ok) {
          if (breaker) breaker.recordSuccess();
          return response;
        }

        // Handle specific non-OK statuses
        // Transient server errors (429, 502, 503, 504) -> retry
        if (this.isTransient(null, response.status)) {
          lastError = new Error(`WAHA transient error: HTTP ${response.status} (${response.statusText})`);
          lastError.status = response.status;
          lastError.data = response.data;

          if (attempt < maxRetries) {
            const jitterDelay = this.calculateJitter(attempt);
            await this._sleepFn(jitterDelay);
            continue;
          }
          // Exhausted retries on transient error
          if (breaker) breaker.recordFailure();
          throw lastError;
        }

        // Non-transient client/server error (400, 401, 404, 500, etc.)
        const errorMsg = (response.data && response.data.message)
          ? response.data.message
          : `WAHA HTTP ${response.status}: ${response.statusText}`;
        const error = new Error(errorMsg);
        error.status = response.status;
        error.data = response.data;
        error.rawBuffer = response.rawBuffer;

        // Trip breaker on server crashes (500)
        if (response.status >= 500 && breaker) {
          breaker.recordFailure();
        } else if (response.status < 500 && breaker) {
          // Client errors (400, 404) are normal API contracts; do not count as server failure
          breaker.recordSuccess();
        }

        throw error;
      } catch (err) {
        lastError = err;

        // Don't retry if circuit breaker itself threw or if non-transient
        if (err.name === 'CircuitBreakerOpenError') {
          throw err;
        }

        // If error has a non-transient status code (e.g. 400, 404), throw immediately
        if (err.status && !this.isTransient(err, err.status)) {
          throw err;
        }

        // Check if network error is transient
        if (this.isTransient(err, err.status)) {
          if (attempt < maxRetries) {
            const jitterDelay = this.calculateJitter(attempt);
            await this._sleepFn(jitterDelay);
            continue;
          }
          if (breaker) breaker.recordFailure();
          throw err;
        }

        // Non-transient error caught
        if (breaker && (!err.status || err.status >= 500)) {
          breaker.recordFailure();
        }
        throw err;
      }
    }

    if (breaker) breaker.recordFailure();
    throw lastError || new Error(`Failed request to ${url}`);
  }

  /**
   * Cold-start wake-up probe.
   * Dispatches non-blocking sequential health probes across multiple endpoints to
   * absorb cloud container spin-up delays (up to 50s on Render Free Tier).
   * Does NOT trip or record failures to the circuit breaker.
   *
   * @param {number} [timeoutSeconds=50]
   * @returns {Promise<{ ok: boolean, reachable: boolean, status: string, endpoint: string|null, statusCode: number|null, details: any }>}
   */
  async ping(timeoutSeconds = 50) {
    const probeEndpoints = [
      '/api/server/version',
      '/api/sessions',
      '/health',
      '/ping',
      '/api/version',
      '/'
    ];

    const probeTimeoutMs = Math.max(5000, (timeoutSeconds || 50) * 1000);
    const perProbeTimeout = Math.min(15000, Math.floor(probeTimeoutMs / probeEndpoints.length));

    for (const ep of probeEndpoints) {
      try {
        const res = await this.request('GET', ep, {
          skipBreaker: true,
          timeoutMs: perProbeTimeout,
          maxRetries: 0
        });

        if (res.status === 200 || res.status === 201) {
          return {
            ok: true,
            reachable: true,
            status: 'OK',
            endpoint: ep,
            statusCode: res.status,
            details: res.data
          };
        }
        if (res.status === 401) {
          return {
            ok: false,
            reachable: true,
            status: 'UNAUTHORIZED',
            endpoint: ep,
            statusCode: res.status,
            details: res.data
          };
        }
      } catch (e) {
        // Continue probing next endpoint
      }
    }

    return {
      ok: false,
      reachable: false,
      status: 'UNREACHABLE',
      endpoint: null,
      statusCode: null,
      details: null
    };
  }

  /**
   * Retrieves status and metadata for a WAHA session.
   * Gracefully maps HTTP 404 to status 'STOPPED'.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<Object>} Session object (e.g. { name, status, me, engine, config })
   */
  async getSession(sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    try {
      const res = await this.request('GET', `/api/sessions/${encodeURIComponent(target)}`, {
        sessionName: target
      });
      return res.data || { name: target, status: 'UNKNOWN' };
    } catch (err) {
      if (err.status === 404) {
        return {
          name: target,
          status: 'STOPPED',
          notFound: true
        };
      }
      throw err;
    }
  }

  /**
   * Starts a session. If session does not exist, registers and starts it via POST /api/sessions.
   * If already registered, invokes POST /api/sessions/{session}/start.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @param {Object} [options={}]
   * @returns {Promise<Object>}
   */
  async startSession(sessionName = this.defaultSession, options = {}) {
    const target = sessionName || this.defaultSession;
    const payload = Object.assign({
      name: target,
      start: true
    }, options);

    // 1. Try atomic upsert & start via POST /api/sessions/start (SI Lead Xtract reference pattern)
    try {
      const res = await this.request('POST', '/api/sessions/start', {
        sessionName: target,
        body: payload
      });
      return res.data;
    } catch (err) {
      // Fall through to standard creation flow
    }

    // 2. Try POST /api/sessions (create session)
    try {
      const res = await this.request('POST', '/api/sessions', {
        sessionName: target,
        body: payload
      });
      return res.data;
    } catch (err) {
      // If session already exists (400, 409, 422), call /start on existing session
      if ([400, 409, 422].includes(Number(err.status))) {
        const startRes = await this.request('POST', `/api/sessions/${encodeURIComponent(target)}/start`, {
          sessionName: target,
          body: options
        });
        return startRes.data;
      }
      throw err;
    }
  }

  /**
   * Stops or logs out a WAHA session.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @param {boolean} [logout=false] - When true, clears WhatsApp auth credentials from container
   * @returns {Promise<Object>}
   */
  async stopSession(sessionName = this.defaultSession, logout = false) {
    const target = sessionName || this.defaultSession;
    const endpoint = logout
      ? `/api/sessions/${encodeURIComponent(target)}/logout`
      : `/api/sessions/${encodeURIComponent(target)}/stop`;

    const res = await this.request('POST', endpoint, {
      sessionName: target,
      body: { name: target }
    });
    return res.data;
  }

  /**
   * Restarts a session.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @param {boolean} [cleanIfFailed=false] - If true, deletes and recreates session on failure
   * @returns {Promise<Object>}
   */
  async restartSession(sessionName = this.defaultSession, cleanIfFailed = false) {
    const target = sessionName || this.defaultSession;

    if (cleanIfFailed) {
      try {
        await this.deleteSession(target);
      } catch (e) {
        // ignore if not present
      }
      await this._sleepFn(1000);
      return this.startSession(target);
    }

    try {
      const res = await this.request('POST', `/api/sessions/${encodeURIComponent(target)}/restart`, {
        sessionName: target,
        body: { name: target }
      });
      return res.data;
    } catch (err) {
      // Fallback: stop then start
      await this.stopSession(target).catch(() => { });
      await this._sleepFn(1000);
      return this.startSession(target);
    }
  }

  /**
   * Deletes a session and purges its stored credentials.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<Object>}
   */
  async deleteSession(sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    const res = await this.request('DELETE', `/api/sessions/${encodeURIComponent(target)}`, {
      sessionName: target
    });
    return res.data;
  }

  /**
   * 2-second polling loop to ensure a session reaches 'WORKING' status.
   * Automatically triggers startSession if STOPPED/NOT_FOUND, or restartSession if FAILED.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @param {number} [maxWaitMs=35000]
   * @returns {Promise<Object>} Final session object
   */
  async ensureSessionWorking(sessionName = this.defaultSession, maxWaitMs = 35000) {
    const target = sessionName || this.defaultSession;
    const startTime = Date.now();

    while (Date.now() - startTime < maxWaitMs) {
      const session = await this.getSession(target);

      if (session && session.status === 'WORKING') {
        return session;
      }

      if (session.status === 'STOPPED' || session.status === 'NOT_FOUND') {
        await this.startSession(target).catch(() => { });
      } else if (session.status === 'FAILED') {
        await this.restartSession(target).catch(() => { });
      }

      await this._sleepFn(2000);
    }

    const finalSession = await this.getSession(target);
    return finalSession;
  }

  /**
   * Fetches QR code pairing image.
   * Crucial resilience invariant: Catches HTTP 400 (session not in SCAN_QR_CODE status,
   * e.g. already WORKING or STARTING) gracefully without throwing, queries getSession(),
   * and maps to legacy POS states.
   *
   * WAHA Core exposes QR via /api/{session}/auth/qr (binary image or base64 JSON),
   * with legacy fallback to /api/sessions/{session}/auth/qr.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<{ qr: string|null, dataUrl: string|null, status: string, session?: Object }>}
   */
  async getQrImage(sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    const endpoints = [
      `/api/${encodeURIComponent(target)}/auth/qr?format=image`,
      `/api/${encodeURIComponent(target)}/auth/qr`,
      `/api/sessions/${encodeURIComponent(target)}/auth/qr?format=image`,
      `/api/sessions/${encodeURIComponent(target)}/auth/qr`
    ];

    let lastErr = null;
    for (const ep of endpoints) {
      try {
        const res = await this.request('GET', ep, {
          sessionName: target,
          headers: {
            'Accept': 'image/png, image/*, application/json, */*'
          }
        });

        let dataUrl = null;

        // Case A: Raw binary PNG buffer
        if (Buffer.isBuffer(res.data)) {
          dataUrl = `data:image/png;base64,${res.data.toString('base64')}`;
        } else if (typeof res.data === 'object' && res.data !== null) {
          // Case B: Base64 JSON payload (supports WAHA fields: base64, data, qr, value, image, url)
          const rawData = res.data.base64 || res.data.data || res.data.qr || res.data.value || res.data.image || res.data.url || '';
          const mime = res.data.mimetype || res.data.format || 'image/png';
          if (typeof rawData === 'string' && rawData.startsWith('data:')) {
            dataUrl = rawData;
          } else if (typeof rawData === 'string' && rawData.length > 0) {
            const cleanMime = mime.includes('/') ? mime : `image/${mime}`;
            dataUrl = `data:${cleanMime};base64,${rawData}`;
          }
        } else if (typeof res.data === 'string' && res.data.length > 0) {
          // Case C: Raw string data URI or base64 string
          dataUrl = res.data.startsWith('data:') ? res.data : `data:image/png;base64,${res.data}`;
        }

        if (dataUrl) {
          return {
            qr: dataUrl,
            dataUrl,
            status: 'SCAN_QR'
          };
        }
      } catch (err) {
        lastErr = err;
        // If 400 (session already WORKING/STARTING, not in SCAN_QR_CODE), stop probing and check status
        if (err.status === 400) {
          break;
        }
        // If 404 (endpoint not recognized), fall through to next endpoint candidate
        if (err.status === 404) {
          continue;
        }
      }
    }

    // If all endpoints failed or returned 400/404, query session status
    if (lastErr && (lastErr.status === 400 || lastErr.status === 404)) {
      const session = await this.getSession(target).catch(() => ({ status: 'DISCONNECTED' }));
      let mappedStatus = 'DISCONNECTED';
      if (session.status === 'WORKING') mappedStatus = 'CONNECTED';
      else if (session.status === 'STARTING') mappedStatus = 'CONNECTING';
      else if (session.status === 'SCAN_QR_CODE') mappedStatus = 'SCAN_QR';

      return {
        qr: null,
        dataUrl: null,
        status: mappedStatus,
        session
      };
    }

    if (lastErr) {
      throw lastErr;
    }

    return {
      qr: null,
      dataUrl: null,
      status: 'CONNECTING'
    };
  }

  /**
   * Retrieves connected WhatsApp user details from /api/{session}/me or /api/sessions/{session}/me.
   *
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<{ id: string, pushName: string, phone: string, name: string }>}
   */
  async getMe(sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    let res;
    try {
      res = await this.request('GET', `/api/${encodeURIComponent(target)}/me`, { sessionName: target });
    } catch (e) {
      res = await this.request('GET', `/api/sessions/${encodeURIComponent(target)}/me`, { sessionName: target });
    }

    const data = res.data || {};
    const id = data.id || '';
    const pushName = data.pushName || data.name || 'Cafe QR POS';

    // Parse phone from jid (e.g. 919876543210@c.us or 919876543210:1@s.whatsapp.net)
    const phone = id.split('@')[0].split(':')[0] || '';

    return {
      id,
      pushName,
      name: pushName,
      phone
    };
  }

  /**
   * Emits typing presence to WhatsApp chat.
   * Non-blocking, fails gracefully without tripping circuit breaker.
   *
   * @param {string|number} phone
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<boolean>}
   */
  async startTyping(phone, sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    const chatId = formatChatId(phone);
    if (!chatId) return false;

    try {
      await this.request('POST', '/api/startTyping', {
        sessionName: target,
        skipBreaker: true,
        timeoutMs: 3000,
        maxRetries: 0,
        body: {
          session: target,
          chatId
        }
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * Stops typing presence.
   * Non-blocking, fails gracefully without tripping circuit breaker.
   *
   * @param {string|number} phone
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<boolean>}
   */
  async stopTyping(phone, sessionName = this.defaultSession) {
    const target = sessionName || this.defaultSession;
    const chatId = formatChatId(phone);
    if (!chatId) return false;

    try {
      await this.request('POST', '/api/stopTyping', {
        sessionName: target,
        skipBreaker: true,
        timeoutMs: 3000,
        maxRetries: 0,
        body: {
          session: target,
          chatId
        }
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * Simulates typing presence for a given duration.
   *
   * @param {string|number} phone
   * @param {number} [ms=800]
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<void>}
   */
  async simulateTyping(phone, ms = 800, sessionName = this.defaultSession) {
    if (ms <= 0) return;
    await this.startTyping(phone, sessionName);
    await this._sleepFn(ms);
    await this.stopTyping(phone, sessionName);
  }

  /**
   * Sends plain text message via WAHA with phone formatting and optional typing.
   *
   * @param {string|number} phone - Target phone number
   * @param {string} text - Message text
   * @param {Object|string} [options={}] - Options object or sessionName string
   * @param {string} [sessionName=this.defaultSession] - Session name
   * @returns {Promise<Object>}
   */
  async sendText(phone, text, options = {}, sessionName = this.defaultSession) {
    let opts = options;
    let target = sessionName;
    if (typeof options === 'string') {
      target = options;
      opts = (typeof sessionName === 'object' && sessionName !== null) ? sessionName : {};
    }
    target = target || this.defaultSession;
    const chatId = formatChatId(phone);

    if (opts.simulateTyping) {
      await this.simulateTyping(chatId, opts.typingDelayMs || 800, target);
    }

    const payload = {
      chatId,
      text,
      session: target
    };
    if (opts.replyTo) {
      payload.replyTo = opts.replyTo;
    }

    const res = await this.request('POST', '/api/sendText', {
      sessionName: target,
      body: payload
    });
    return res.data;
  }

  /**
   * Sends inline image via WAHA POST /api/sendImage (with fallback to sendFile on 404).
   *
   * @param {string|number} phone
   * @param {string|Buffer} fileData
   * @param {string} [filename='image.jpg']
   * @param {string} [mimetype='image/jpeg']
   * @param {string} [caption='']
   * @param {Object|string} [options={}]
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<Object>}
   */
  async sendImage(phone, fileData, filename = 'image.jpg', mimetype = 'image/jpeg', caption = '', options = {}, sessionName = this.defaultSession) {
    let opts = options;
    let target = sessionName;
    if (typeof options === 'string') {
      target = options;
      opts = (typeof sessionName === 'object' && sessionName !== null) ? sessionName : {};
    }
    target = target || this.defaultSession;
    const chatId = formatChatId(phone);

    if (opts.simulateTyping) {
      await this.simulateTyping(chatId, opts.typingDelayMs || 800, target);
    }

    let b64Data = fileData;
    let effectiveMime = mimetype || 'image/jpeg';

    if (Buffer.isBuffer(fileData)) {
      b64Data = fileData.toString('base64');
    } else if (typeof fileData === 'string' && fileData.startsWith('data:')) {
      const match = fileData.match(/^data:([^;]+);base64,(.+)$/s);
      if (match) {
        effectiveMime = match[1] || effectiveMime;
        b64Data = match[2];
      }
    }

    try {
      const res = await this.request('POST', '/api/sendImage', {
        sessionName: target,
        body: {
          chatId,
          file: {
            mimetype: effectiveMime,
            filename: filename || 'image.jpg',
            data: b64Data
          },
          caption: caption || '',
          session: target
        }
      });
      return res.data;
    } catch (err) {
      if (err.statusCode === 404 || String(err.message).includes('404')) {
        // Fallback to /api/sendFile for older WAHA Core releases
        const res = await this.request('POST', '/api/sendFile', {
          sessionName: target,
          body: {
            chatId,
            file: {
              mimetype: effectiveMime,
              filename: filename || 'image.jpg',
              data: b64Data
            },
            caption: caption || '',
            session: target
          }
        });
        return res.data;
      }
      throw err;
    }
  }

  /**
   * Sends file attachment via WAHA. Auto-routes image types to sendImage.
   *
   * @param {string|number} phone
   * @param {string|Buffer} fileData
   * @param {string} [filename='document.pdf']
   * @param {string} [mimetype='application/pdf']
   * @param {string} [caption='']
   * @param {Object|string} [options={}]
   * @param {string} [sessionName=this.defaultSession]
   * @returns {Promise<Object>}
   */
  async sendFile(phone, fileData, filename = 'document.pdf', mimetype = 'application/pdf', caption = '', options = {}, sessionName = this.defaultSession) {
    let opts = options;
    let target = sessionName;
    if (typeof options === 'string') {
      target = options;
      opts = (typeof sessionName === 'object' && sessionName !== null) ? sessionName : {};
    }
    target = target || this.defaultSession;

    const ext = (filename || '').split('.').pop().toLowerCase();
    const isImage = (mimetype && mimetype.startsWith('image/')) || ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp'].includes(ext);

    if (isImage && !opts._skipImageRoute) {
      return this.sendImage(phone, fileData, filename, mimetype || 'image/jpeg', caption, opts, target);
    }

    const chatId = formatChatId(phone);

    if (opts.simulateTyping) {
      await this.simulateTyping(chatId, opts.typingDelayMs || 800, target);
    }

    let b64Data = fileData;
    let effectiveMime = mimetype || 'application/pdf';

    if (Buffer.isBuffer(fileData)) {
      b64Data = fileData.toString('base64');
    } else if (typeof fileData === 'string' && fileData.startsWith('data:')) {
      const match = fileData.match(/^data:([^;]+);base64,(.+)$/s);
      if (match) {
        effectiveMime = match[1] || effectiveMime;
        b64Data = match[2];
      }
    }

    const res = await this.request('POST', '/api/sendFile', {
      sessionName: target,
      body: {
        chatId,
        file: {
          mimetype: effectiveMime,
          filename: filename || 'document.pdf',
          data: b64Data
        },
        caption: caption || '',
        session: target
      }
    });
    return res.data;
  }

  formatChatId(phone, defaultCountryCode) {
    return formatChatId(phone, defaultCountryCode);
  }

  extractMessageId(resp) {
    return extractMessageId(resp);
  }
}

/**
 * Formats a phone number into international WhatsApp Chat ID (<digits>@c.us).
 * - Cleanly handles string and number inputs.
 * - Preserves existing @c.us or @g.us suffixes.
 * - Strips non-digits and leading zeros.
 * - Prepends defaultCountryCode ('91') for 10-digit numbers.
 * - Appends @c.us.
 *
 * @param {string|number} phone
 * @param {string} [defaultCountryCode='91']
 * @returns {string} e.g. "919496958200@c.us"
 */
function normalizePhone(rawPhone, defaultCountryCode = process.env.DEFAULT_COUNTRY_CODE || '91') {
  if (rawPhone === null || rawPhone === undefined || typeof rawPhone === 'boolean') {
    throw new Error('Invalid phone input: non-string/non-numeric');
  }
  if (typeof rawPhone !== 'string' && typeof rawPhone !== 'number') {
    throw new Error('Invalid phone input: non-string/non-numeric');
  }

  let s = String(rawPhone).trim();
  if (!s) {
    throw new Error(`Phone number invalid or too short: ${rawPhone}`);
  }

  // Preserve group JIDs
  if (s.endsWith('@g.us')) {
    return s;
  }

  // If already ends with @c.us, strip suffix first to normalize digits
  if (s.endsWith('@c.us')) {
    s = s.slice(0, -5);
  }

  // Strip pandas float artifact (.0 or .00)
  if (s.endsWith('.0')) {
    s = s.slice(0, -2);
  } else {
    s = s.replace(/\.0+$/, '');
  }

  // An explicit "+" means the number already carries its country code (the backend resolves it from the
  // store's country). Never re-prefix it: e.g. +65 9123 4567 is 10 digits and must not become 91…
  const isExplicitInternational = s.startsWith('+');

  // Strip all non-digit characters
  s = s.replace(/[^0-9]/g, '');

  if (isExplicitInternational) {
    if (s.length < 8 || s.length > 15 || s.startsWith('0')) {
      throw new Error(`Phone number invalid or too short: ${rawPhone}`);
    }
    return `${s}@c.us`;
  }

  // Strip leading zeros if 11 digits (e.g. 09496958200 -> 9496958200)
  if (s.length === 11 && s.startsWith('0')) {
    s = s.slice(1);
  }

  // Prepend default Indian country code for 10-digit national numbers
  if (s.length === 10) {
    s = `${defaultCountryCode || '91'}${s}`;
  }

  // Validation: Must be at least 10 digits and not all zeros
  if (s.length < 10 || s.includes('0000000000')) {
    throw new Error(`Phone number invalid or too short: ${rawPhone}`);
  }

  return `${s}@c.us`;
}

function formatChatId(phone, defaultCountryCode = process.env.DEFAULT_COUNTRY_CODE || '91') {
  try {
    return normalizePhone(phone, defaultCountryCode);
  } catch (err) {
    if (phone === null || phone === undefined) return '';
    const str = String(phone).trim();
    if (!str) return '';

    if (str.endsWith('@c.us') || str.endsWith('@g.us')) {
      return str;
    }

    // Strip non-digits
    let digits = str.replace(/\D/g, '');
    // Strip leading zeros
    digits = digits.replace(/^0+/, '');
    if (!digits) return '';

    // If 10 digits, prepend defaultCountryCode
    if (digits.length === 10) {
      digits = `${defaultCountryCode}${digits}`;
    }

    return `${digits}@c.us`;
  }
}

/**
 * Polymorphic extractor resolving message ID across all WAHA engine variations.
 *
 * @param {Object|string} resp
 * @returns {string|null}
 */
function extractMessageId(resp) {
  if (!resp) return null;
  if (typeof resp === 'string') return resp;
  if (resp?.id?._serialized && typeof resp.id._serialized === 'string') return resp.id._serialized;
  if (resp?.id === 'string') return resp.id;
  if (resp?.key?.id && typeof resp.key.id === 'string') return resp.key.id;
  if (typeof resp?.messageId === 'string') return resp.messageId;
  if (resp?.data?.id && typeof resp.data.id === 'string') return resp.data.id;
  if (resp?.id?.id && typeof resp.id.id === 'string') return resp.id.id;
  return null;
}

WahaClient.normalizePhone = normalizePhone;
WahaClient.formatChatId = formatChatId;
WahaClient.extractMessageId = extractMessageId;

module.exports = {
  WahaClient,
  SlidingWindowCircuitBreaker,
  BreakerState,
  CircuitBreakerOpenError,
  normalizePhone,
  formatChatId,
  extractMessageId
};
