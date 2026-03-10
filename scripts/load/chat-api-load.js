import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CHAT_PATH = __ENV.CHAT_PATH || '/api/chat';
const TENANT_ID = __ENV.TENANT_ID || 'default';
const AUTH_TOKENS = (__ENV.AUTH_TOKENS || __ENV.AUTH_TOKEN || '')
  .split(/[|,\n]/)
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

const PROMPTS = (__ENV.PROMPTS ||
  'What is 2 + 2?|Summarize the key risks of enabling write tools on chat channels')
  .split('|')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

const CHANNEL = __ENV.CHANNEL || 'benchmark';
const SESSION_PREFIX = __ENV.SESSION_PREFIX || '';
const MODEL = __ENV.MODEL || '';
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 6.5);

const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent': 'k6-arc-reactor-chat-benchmark',
};

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

function pickPrompt() {
  if (PROMPTS.length === 0) {
    return 'Hello';
  }
  const idx = (__ITER + __VU) % PROMPTS.length;
  return PROMPTS[idx];
}

function buildPayload() {
  const metadata = {
    channel: CHANNEL,
  };
  if (SESSION_PREFIX) {
    metadata.sessionId = `${SESSION_PREFIX}-${__VU}`;
  }
  const payload = {
    message: pickPrompt(),
    metadata,
  };

  if (MODEL) {
    payload.model = MODEL;
  }

  return payload;
}

function buildHeaders() {
  const token = AUTH_TOKENS.length === 0 ? '' : AUTH_TOKENS[(__VU - 1) % AUTH_TOKENS.length];
  const headers = { ...DEFAULT_HEADERS };
  if (TENANT_ID) {
    headers['X-Tenant-Id'] = TENANT_ID;
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

export default function () {
  const payload = JSON.stringify(buildPayload());
  const res = http.post(`${BASE_URL}${CHAT_PATH}`, payload, {
    headers: buildHeaders(),
    timeout: __ENV.REQUEST_TIMEOUT || '30s',
  });

  let successField = false;
  try {
    const parsed = res.json();
    successField = parsed && parsed.success === true;
  } catch (_) {
    successField = false;
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response success=true': () => successField,
  });

  sleep(SLEEP_SECONDS);
}
