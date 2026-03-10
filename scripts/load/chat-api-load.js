import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

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
const EXPECT_STAGE_TIMINGS = String(__ENV.EXPECT_STAGE_TIMINGS || 'false').toLowerCase() === 'true';

const DEFAULT_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent': 'k6-arc-reactor-chat-benchmark',
};

const STAGE_TRENDS = {
  queue_wait: new Trend('arc_stage_queue_wait_ms', true),
  guard: new Trend('arc_stage_guard_ms', true),
  before_hooks: new Trend('arc_stage_before_hooks_ms', true),
  intent_resolution: new Trend('arc_stage_intent_resolution_ms', true),
  cache_lookup: new Trend('arc_stage_cache_lookup_ms', true),
  history_load: new Trend('arc_stage_history_load_ms', true),
  rag_retrieval: new Trend('arc_stage_rag_retrieval_ms', true),
  tool_selection: new Trend('arc_stage_tool_selection_ms', true),
  agent_loop: new Trend('arc_stage_agent_loop_ms', true),
  llm_calls: new Trend('arc_stage_llm_calls_ms', true),
  tool_execution: new Trend('arc_stage_tool_execution_ms', true),
  fallback: new Trend('arc_stage_fallback_ms', true),
  finalizer: new Trend('arc_stage_finalizer_ms', true),
};
const missingStageTimings = new Counter('arc_stage_timings_missing');

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

function recordStageTimings(parsed) {
  const stageTimings = parsed && parsed.metadata && parsed.metadata.stageTimings;
  if (!stageTimings || typeof stageTimings !== 'object') {
    missingStageTimings.add(1);
    return false;
  }

  let hasKnownStage = false;
  Object.entries(stageTimings).forEach(([stage, value]) => {
    const trend = STAGE_TRENDS[stage];
    const durationMs = Number(value);
    if (!trend || !Number.isFinite(durationMs)) {
      return;
    }
    trend.add(durationMs);
    hasKnownStage = true;
  });
  return hasKnownStage;
}

export default function () {
  const payload = JSON.stringify(buildPayload());
  const res = http.post(`${BASE_URL}${CHAT_PATH}`, payload, {
    headers: buildHeaders(),
    timeout: __ENV.REQUEST_TIMEOUT || '30s',
  });

  let successField = false;
  let hasStageTimings = false;
  try {
    const parsed = res.json();
    successField = parsed && parsed.success === true;
    hasStageTimings = recordStageTimings(parsed);
  } catch (_) {
    successField = false;
    hasStageTimings = false;
  }

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response success=true': () => successField,
    'stage timings present when expected': () => !EXPECT_STAGE_TIMINGS || hasStageTimings,
  });

  sleep(SLEEP_SECONDS);
}
