import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CHAT_PATH = __ENV.CHAT_PATH || '/api/chat';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const TENANT_ID = __ENV.TENANT_ID || 'default';
const OTHER_TENANT_ID = __ENV.OTHER_TENANT_ID || 'other-tenant';
const MODE = (__ENV.MODE || 'mixed').toLowerCase();
const OVERSIZED_CHARS = Number(__ENV.OVERSIZED_CHARS || 15000);
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 0.1);

if (!AUTH_TOKEN) {
  throw new Error('AUTH_TOKEN is required for chat guard load test');
}

http.setResponseCallback(http.expectedStatuses(200, 400));

const contractFailureRatio = new Rate('chat_guard_contract_failure_ratio');
const unexpectedStatusRatio = new Rate('chat_guard_unexpected_status_ratio');
const oversizedRejectedCount = new Counter('chat_guard_oversized_rejected_count');
const tenantMismatchRejectedCount = new Counter('chat_guard_tenant_mismatch_rejected_count');

export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
    chat_guard_unexpected_status_ratio: ['rate<0.01'],
    chat_guard_contract_failure_ratio: ['rate<0.01'],
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
    return {
      message: `tenant mismatch probe ${__VU}-${__ITER}`,
    };
  }
  return {
    message: oversizedMessage,
  };
}

function buildRequestHeaders(testCase) {
  const tenant = testCase === 'tenant_mismatch' ? OTHER_TENANT_ID : TENANT_ID;
  return {
    'Content-Type': 'application/json',
    'User-Agent': 'k6-arc-reactor-chat-guard-load',
    Authorization: `Bearer ${AUTH_TOKEN}`,
    'X-Tenant-Id': tenant,
  };
}

function parseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function containsAny(text, patterns) {
  if (!text) return false;
  const lowered = String(text).toLowerCase();
  return patterns.some((pattern) => lowered.includes(pattern.toLowerCase()));
}

function validateTenantMismatchContract(response, body) {
  if (response.status !== 400) return false;
  const error = body && body.error ? String(body.error) : '';
  return containsAny(error, [
    'Tenant header does not match resolved tenant context',
    'tenant header does not match',
  ]);
}

function validateOversizedContract(response, body) {
  if (response.status === 400) {
    const error = body && body.error ? String(body.error) : '';
    return containsAny(error, [
      'Request rejected by guard',
      'GUARD_REJECTED',
      'Boundary violation',
      'input.max_chars',
      'rate limit',
      'RATE_LIMITED',
      'too many requests',
    ]);
  }

  if (response.status === 200) {
    const successPresent = body && Object.prototype.hasOwnProperty.call(body, 'success');
    const successValue = successPresent ? body.success : null;
    const errorMessage = body && body.errorMessage ? String(body.errorMessage) : '';
    if (successPresent && successValue !== false) return false;
    return containsAny(errorMessage, [
      'Request rejected by guard',
      'GUARD_REJECTED',
      'Boundary violation',
      'input.max_chars',
      'rate limit',
      'RATE_LIMITED',
      'too many requests',
    ]);
  }

  return false;
}

export default function () {
  const testCase = chooseCase();
  const payload = JSON.stringify(buildRequestPayload(testCase));
  const response = http.post(`${BASE_URL}${CHAT_PATH}`, payload, {
    headers: buildRequestHeaders(testCase),
    timeout: __ENV.REQUEST_TIMEOUT || '15s',
  });
  const body = parseJson(response);

  const expectedStatus = response.status === 200 || response.status === 400;
  let contractOk = false;

  if (testCase === 'tenant_mismatch') {
    contractOk = validateTenantMismatchContract(response, body);
    if (contractOk) tenantMismatchRejectedCount.add(1);
  } else {
    contractOk = validateOversizedContract(response, body);
    if (contractOk) oversizedRejectedCount.add(1);
  }

  unexpectedStatusRatio.add(!expectedStatus);
  contractFailureRatio.add(!contractOk);

  check(response, {
    'status is 200 or 400': () => expectedStatus,
    'guard/filter contract holds': () => contractOk,
  });

  sleep(SLEEP_SECONDS);
}
