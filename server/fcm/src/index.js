'use strict';

/*
 * 出网代理（仅在国内网络等需要代理的环境生效）。
 *
 * Firebase Admin 的鉴权与 FCM 发送都直接走 Node 的 https 全局 Agent，
 * 不会读取 HTTPS_PROXY 环境变量，因此这里显式替换 globalAgent。
 * 未设置 HTTPS_PROXY 时不做任何改动，生产环境无副作用。
 */
if (process.env.HTTPS_PROXY) {
  const https = require('https');
  const { HttpsProxyAgent } = require('https-proxy-agent');
  https.globalAgent = new HttpsProxyAgent(process.env.HTTPS_PROXY);
}

const express = require('express');
const path = require('path');
const admin = require('firebase-admin');
const { TokenStore } = require('./token-store');

const PORT = Number(process.env.PORT || 8787);
const ADMIN_TOKEN = process.env.FCM_ADMIN_TOKEN || '';
const STORE_PATH = process.env.TOKEN_STORE
  || path.join(__dirname, '..', 'data', 'tokens.json');
const MAX_BATCH = 500;

const store = new TokenStore(STORE_PATH);
const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '64kb' }));

let messaging = null;

/** Firebase Admin 初始化：优先使用内联 JSON，其次使用 GOOGLE_APPLICATION_CREDENTIALS */
function initFirebase() {
  try {
    if (process.env.FIREBASE_SERVICE_ACCOUNT) {
      const credentials = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      admin.initializeApp({ credential: admin.credential.cert(credentials) });
    } else {
      admin.initializeApp({ credential: admin.credential.applicationDefault() });
    }
    messaging = admin.messaging();
    if (process.env.HTTPS_PROXY) {
      // FCM 默认使用 HTTP/2，而 HTTP/2 不经过 https.globalAgent，无法走上面的代理；
      // 配置了代理时切换为 HTTP/1.1 传输（官方提供的兼容开关）。
      messaging.enableLegacyHttpTransport();
    }
    console.log('[fcm] Firebase Admin 初始化成功');
  } catch (err) {
    // 允许无凭据启动，便于本地联调令牌登记接口；发送接口会返回 503
    console.warn('[fcm] Firebase Admin 未就绪（发送接口不可用）:', err.message);
    messaging = null;
  }
}

/* ------------------------------------------------------------------ */
/* 基础中间件                                                          */
/* ------------------------------------------------------------------ */

const rateBuckets = new Map();

/** 极简按 IP 限流：仅用于保护公开的令牌登记接口 */
function rateLimit(limit, windowMs) {
  return (req, res, next) => {
    const key = req.ip || 'unknown';
    const now = Date.now();
    let bucket = rateBuckets.get(key);
    if (!bucket || now > bucket.resetAt) {
      bucket = { count: 0, resetAt: now + windowMs };
      rateBuckets.set(key, bucket);
    }
    bucket.count += 1;
    if (bucket.count > limit) {
      res.status(429).json({ ok: false, error: 'too_many_requests' });
      return;
    }
    next();
  };
}

function requireAdmin(req, res, next) {
  if (!ADMIN_TOKEN) {
    res.status(503).json({ ok: false, error: 'server_token_not_configured' });
    return;
  }
  const header = req.get('authorization') || '';
  const value = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (value !== ADMIN_TOKEN) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }
  next();
}

/* ------------------------------------------------------------------ */
/* 令牌登记                                                            */
/* ------------------------------------------------------------------ */

app.get('/health', (req, res) => {
  res.json({ ok: true, tokens: store.size(), messagingReady: Boolean(messaging) });
});

app.post('/api/fcm/register', rateLimit(60, 60 * 1000), (req, res) => {
  const body = req.body || {};
  const token = typeof body.token === 'string' ? body.token.trim() : '';
  if (!token || token.length < 20 || token.length > 4096) {
    res.status(400).json({ ok: false, error: 'invalid_token' });
    return;
  }
  store.upsert({
    token,
    platform: body.platform === 'ios' ? 'ios' : 'android',
    deviceId: body.deviceId ? String(body.deviceId) : '',
    userId: body.userId === undefined || body.userId === null ? '' : String(body.userId),
    appVersion: body.appVersion ? String(body.appVersion) : '',
  });
  res.json({ ok: true });
});

app.post('/api/fcm/unregister', rateLimit(60, 60 * 1000), (req, res) => {
  const token = req.body && req.body.token ? String(req.body.token) : '';
  const removed = store.remove(token);
  res.json({ ok: true, removed });
});

/* ------------------------------------------------------------------ */
/* 推送                                                                */
/* ------------------------------------------------------------------ */

function asStringMap(data) {
  const out = {};
  if (!data || typeof data !== 'object') {
    return out;
  }
  for (const [key, value] of Object.entries(data)) {
    if (value === undefined || value === null) {
      continue;
    }
    out[String(key)] = String(value);
  }
  return out;
}

function isInvalidToken(error) {
  const code = error && error.code;
  return code === 'messaging/registration-token-not-registered'
    || code === 'messaging/invalid-registration-token'
    || code === 'messaging/invalid-argument';
}

function chunks(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) {
    out.push(list.slice(i, i + size));
  }
  return out;
}

/**
 * 批量发送。
 *
 * body:
 *   tokens?: string[]      显式指定令牌（缺省时按 userId / 全部设备发送）
 *   userId?: string|number 只发给某个用户的设备
 *   topic?: string         向主题发送（与 tokens/userId 互斥）
 *   title / body           通知文案
 *   data?: object          附加数据（值会统一转字符串；route/query/group 由客户端约定）
 *   dataOnly?: boolean     默认 true：data-only 消息，前台/后台/被杀均由客户端处理
 */
app.post('/api/fcm/send', requireAdmin, async (req, res) => {
  if (!messaging) {
    res.status(503).json({ ok: false, error: 'messaging_unavailable' });
    return;
  }
  const body = req.body || {};
  const title = body.title ? String(body.title) : '';
  const text = body.body ? String(body.body) : '';
  const data = asStringMap(body.data);
  if (title) {
    data.title = title;
  }
  if (text) {
    data.body = text;
  }
  const dataOnly = body.dataOnly !== false;

  try {
    if (body.topic) {
      const message = {
        topic: String(body.topic),
        data,
        ...(dataOnly ? {} : { notification: { title, body: text } }),
        android: { priority: 'high' },
      };
      const id = await messaging.send(message);
      res.json({ ok: true, mode: 'topic', messageId: id });
      return;
    }

    let tokens = Array.isArray(body.tokens) ? body.tokens.map(String) : null;
    if (!tokens || tokens.length === 0) {
      tokens = body.userId !== undefined
        ? store.byUser(body.userId).map((item) => item.token)
        : store.all().map((item) => item.token);
    }
    tokens = [...new Set(tokens.filter(Boolean))];
    if (tokens.length === 0) {
      res.status(400).json({ ok: false, error: 'no_tokens' });
      return;
    }

    let success = 0;
    let failure = 0;
    const invalid = [];
    const failures = [];

    for (const chunk of chunks(tokens, MAX_BATCH)) {
      const response = await messaging.sendEachForMulticast({
        tokens: chunk,
        data,
        ...(dataOnly ? {} : { notification: { title, body: text } }),
        android: { priority: 'high' },
      });
      success += response.successCount;
      failure += response.failureCount;
      response.responses.forEach((item, index) => {
        if (item.success) {
          return;
        }
        const token = chunk[index];
        const code = item.error && item.error.code;
        failures.push({ token: `${token.slice(0, 8)}…`, code });
        if (isInvalidToken(item.error)) {
          invalid.push(token);
        }
      });
    }

    // 清理失效令牌，避免后续持续失败
    invalid.forEach((token) => store.remove(token));

    res.json({
      ok: true,
      mode: 'tokens',
      requested: tokens.length,
      success,
      failure,
      invalidTokensRemoved: invalid.length,
      failures: failures.slice(0, 20),
    });
  } catch (err) {
    console.error('[fcm] 发送失败:', err);
    res.status(500).json({ ok: false, error: 'send_failed', message: err.message });
  }
});

/* ------------------------------------------------------------------ */
/* 客户端送达回执（可选）                                              */
/* ------------------------------------------------------------------ */

const receipts = [];

/**
 * 客户端收到消息并展示通知后回执，用于统计真实送达率。
 *
 * FCM 本身不提供服务端「已送达」保证：sendEach 的成功只代表已被 FCM 接受。
 * 需要精确投递数据时可同时开启 BigQuery 导出。
 */
app.post('/api/fcm/ack', rateLimit(120, 60 * 1000), (req, res) => {
  const body = req.body || {};
  const token = body.token ? String(body.token) : '';
  const messageId = body.messageId ? String(body.messageId) : '';
  if (!token || !store.has(token)) {
    res.status(400).json({ ok: false, error: 'unknown_token' });
    return;
  }
  receipts.push({
    token: `${token.slice(0, 8)}…`,
    messageId,
    at: new Date().toISOString(),
  });
  while (receipts.length > 1000) {
    receipts.shift();
  }
  res.json({ ok: true });
});

app.get('/api/fcm/ack', requireAdmin, (req, res) => {
  res.json({ ok: true, receipts });
});

initFirebase();

app.listen(PORT, () => {
  console.log(`[fcm] listening on :${PORT}`);
});
