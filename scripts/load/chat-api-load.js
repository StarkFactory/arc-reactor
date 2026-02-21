import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CHAT_PATH = __ENV.CHAT_PATH || '/api/chat';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';

const PROMPTS = (__ENV.PROMPTS ||
  'What is 2 + 2?|Summarize the key risks of enabling write tools on chat channels')
  .split('|')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

const CHANNEL = __ENV.CHANNEL || 'benchmark';
const MODEL = __ENV.MODEL || '';
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 0.1);

const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent': 'k6-arc-reactor-chat-benchmark',
};

export const options = {
  vus: Number(__ENV.VUS || 5),
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
  const payload = {
    message: pickPrompt(),
    metadata: {
      channel: CHANNEL,
      sessionId: `bench-${__VU}`,
    },
  };

  if (MODEL) {
    payload.model = MODEL;
  }

  return payload;
}

function buildHeaders() {
  if (!AUTH_TOKEN) return DEFAULT_HEADERS;
  return {
    ...DEFAULT_HEADERS,
    Authorization: `Bearer ${AUTH_TOKEN}`,
  };
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
