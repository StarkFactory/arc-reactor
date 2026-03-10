import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STREAM_PATH = __ENV.STREAM_PATH || '/api/chat/stream';
const AUTH_TOKENS = (__ENV.AUTH_TOKENS || __ENV.AUTH_TOKEN || '')
  .split(/[|,\n]/)
  .map((s) => s.trim())
  .filter((s) => s.length > 0);
const TENANT_ID = __ENV.TENANT_ID || 'default';
const OTHER_TENANT_ID = __ENV.OTHER_TENANT_ID || 'other-tenant';
const MODE = (__ENV.MODE || 'mixed').toLowerCase();
const OVERSIZED_CHARS = Number(__ENV.OVERSIZED_CHARS || 15000);
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 6.5);

if (AUTH_TOKENS.length === 0) {
  throw new Error('AUTH_TOKEN or AUTH_TOKENS is required for stream security load test');
}

http.setResponseCallback(http.expectedStatuses(200, 400));

const contractFailureRatio = new Rate('chat_stream_contract_failure_ratio');
const unexpectedStatusRatio = new Rate('chat_stream_unexpected_status_ratio');
const tenantMismatchRejectedCount = new Counter('chat_stream_tenant_mismatch_rejected_count');
const guardRejectedCount = new Counter('chat_stream_guard_rejected_count');

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
    chat_stream_unexpected_status_ratio: ['rate<0.01'],
    chat_stream_contract_failure_ratio: ['rate<0.01'],
  },
};

const oversizedMessage = 'A'.repeat(OVERSIZED_CHARS);

function chooseCase() {
  if (MODE === 'oversized') return 'oversized';
  if (MODE === 'tenant-mismatch' || MODE === 'tenant_mismatch') return 'tenant_mismatch';
  return __ITER % 2 === 0 ? 'oversized' : 'tenant_mismatch';
}

function buildRequestPayload(testCase) {
  if (testCase === 'tenant_mismatch') {
    return { message: `stream tenant mismatch probe ${__VU}-${__ITER}` };
  }
  return { message: oversizedMessage };
}

function buildRequestHeaders(testCase) {
  const tenant = testCase === 'tenant_mismatch' ? OTHER_TENANT_ID : TENANT_ID;
  const token = AUTH_TOKENS[(__VU - 1) % AUTH_TOKENS.length];
  return {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    'User-Agent': 'k6-arc-reactor-chat-stream-security-load',
    Authorization: `Bearer ${token}`,
    'X-Tenant-Id': tenant,
  };
}

function containsAny(text, patterns) {
  if (!text) return false;
  const lowered = String(text).toLowerCase();
  return patterns.some((pattern) => lowered.includes(pattern.toLowerCase()));
}

function validateTenantMismatchContract(response) {
  if (response.status !== 400) return false;
  return containsAny(response.body, [
    'Tenant header does not match resolved tenant context',
    'tenant header does not match',
  ]);
}

function validateOversizedContract(response) {
  if (response.status === 400) {
    return containsAny(response.body, [
      'Boundary violation',
      'input.max_chars',
      'Request rejected by guard',
      'GUARD_REJECTED',
      'rate limit',
      'RATE_LIMITED',
      'too many requests',
    ]);
  }

  if (response.status === 200) {
    const hasErrorEvent = containsAny(response.body, ['event:error', 'event: error']);
    const hasDoneEvent = containsAny(response.body, ['event:done', 'event: done']);
    const hasGuardSignal = containsAny(response.body, [
      'Boundary violation',
      'input.max_chars',
      'Request rejected by guard',
      'GUARD_REJECTED',
      'rate limit',
      'RATE_LIMITED',
      'too many requests',
    ]);
    return hasErrorEvent && hasDoneEvent && hasGuardSignal;
  }

  return false;
}

export default function () {
  const testCase = chooseCase();
  const payload = JSON.stringify(buildRequestPayload(testCase));
  const response = http.post(`${BASE_URL}${STREAM_PATH}`, payload, {
    headers: buildRequestHeaders(testCase),
    timeout: __ENV.REQUEST_TIMEOUT || '15s',
  });

  const expectedStatus = response.status === 200 || response.status === 400;
  let contractOk = false;

  if (testCase === 'tenant_mismatch') {
    contractOk = validateTenantMismatchContract(response);
    if (contractOk) tenantMismatchRejectedCount.add(1);
  } else {
    contractOk = validateOversizedContract(response);
    if (contractOk) guardRejectedCount.add(1);
  }

  unexpectedStatusRatio.add(!expectedStatus);
  contractFailureRatio.add(!contractOk);

  check(response, {
    'status is 200 or 400': () => expectedStatus,
    'stream security contract holds': () => contractOk,
  });

  sleep(SLEEP_SECONDS);
}
