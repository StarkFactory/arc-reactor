import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = (__ENV.MODE || 'mixed').toLowerCase(); // events | commands | mixed
const SIGNING_SECRET = __ENV.SLACK_SIGNING_SECRET || '';

const EVENT_PATH = __ENV.EVENT_PATH || '/api/slack/events';
const COMMAND_PATH = __ENV.COMMAND_PATH || '/api/slack/commands';

const DEFAULT_HEADERS = {
  'User-Agent': 'k6-slack-load-test',
};

export const options = {
  vus: Number(__ENV.VUS || 30),
  duration: __ENV.DURATION || '2m',
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500'],
  },
};

function signHeaders(rawBody, contentType) {
  if (!SIGNING_SECRET) {
    return {
      ...DEFAULT_HEADERS,
      'Content-Type': contentType,
    };
  }

  const timestamp = `${Math.floor(Date.now() / 1000)}`;
  const base = `v0:${timestamp}:${rawBody}`;
  const digest = crypto.hmac('sha256', SIGNING_SECRET, base, 'hex');

  return {
    ...DEFAULT_HEADERS,
    'Content-Type': contentType,
    'X-Slack-Request-Timestamp': timestamp,
    'X-Slack-Signature': `v0=${digest}`,
  };
}

function sendEvent(index) {
  const payload = JSON.stringify({
    type: 'event_callback',
    event_id: `EvLoad-${__VU}-${__ITER}-${index}`,
    event: {
      type: 'app_mention',
      user: `U${__VU}${index}`,
      channel: 'CLOADTEST',
      text: `<@BOT> load test message ${index}`,
      ts: `${Date.now() / 1000}`,
    },
  });

  const res = http.post(`${BASE_URL}${EVENT_PATH}`, payload, {
    headers: signHeaders(payload, 'application/json'),
    timeout: '5s',
  });

  check(res, {
    'event status is 200': (r) => r.status === 200,
  });
}

function sendCommand(index) {
  const body =
    `command=%2Fjarvis&text=load+test+${index}` +
    `&user_id=U${__VU}${index}` +
    `&user_name=load_user_${__VU}` +
    '&channel_id=CLOADTEST' +
    '&channel_name=load-test' +
    `&response_url=${encodeURIComponent('https://example.com/response')}`;

  const res = http.post(`${BASE_URL}${COMMAND_PATH}`, body, {
    headers: signHeaders(body, 'application/x-www-form-urlencoded'),
    timeout: '5s',
  });

  check(res, {
    'command status is 200': (r) => r.status === 200,
  });
}

export default function () {
  if (MODE === 'events') {
    sendEvent(1);
  } else if (MODE === 'commands') {
    sendCommand(1);
  } else {
    sendEvent(1);
    sendCommand(2);
  }

  sleep(Number(__ENV.SLEEP_SECONDS || 0.1));
}
