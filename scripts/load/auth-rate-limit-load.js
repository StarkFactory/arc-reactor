import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

http.setResponseCallback(http.expectedStatuses(401, 429));

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LOGIN_PATH = __ENV.LOGIN_PATH || '/api/auth/login';
const SLEEP_SECONDS = Number(__ENV.SLEEP_SECONDS || 0.1);
const FORWARDED_FOR = __ENV.FORWARDED_FOR || '198.51.100.77';

const rateLimitedRatio = new Rate('auth_rate_limited_ratio');
const unexpectedStatusRatio = new Rate('auth_unexpected_status_ratio');
const rateLimitedCount = new Counter('auth_rate_limited_count');

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    auth_unexpected_status_ratio: ['rate<0.01'],
    auth_rate_limited_ratio: ['rate>0.10'],
  },
};

function payload() {
  return JSON.stringify({
    email: `invalid-user-${__VU}-${__ITER}@example.com`,
    password: 'invalid-password',
  });
}

function headers() {
  return {
    'Content-Type': 'application/json',
    'User-Agent': 'k6-arc-reactor-auth-rate-limit',
    'X-Forwarded-For': FORWARDED_FOR,
  };
}

export default function () {
  const response = http.post(`${BASE_URL}${LOGIN_PATH}`, payload(), {
    headers: headers(),
    timeout: __ENV.REQUEST_TIMEOUT || '10s',
  });

  const expectedStatus = response.status === 401 || response.status === 429;
  const isRateLimited = response.status === 429;

  unexpectedStatusRatio.add(!expectedStatus);
  rateLimitedRatio.add(isRateLimited);
  if (isRateLimited) {
    rateLimitedCount.add(1);
  }

  check(response, {
    'status is 401 or 429': () => expectedStatus,
  });

  sleep(SLEEP_SECONDS);
}
