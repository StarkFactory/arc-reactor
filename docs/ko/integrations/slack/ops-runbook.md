# Slack 운영 메트릭/부하테스트 런북

## 1) 메트릭 어디서 보나

Arc Reactor 실행 후 다음 엔드포인트에서 확인한다.

- 메트릭 목록: `GET /actuator/metrics`
- 특정 메트릭: `GET /actuator/metrics/{name}`
- Prometheus scrape: `GET /actuator/prometheus`
- 운영 대시보드 스냅샷(API): `GET /api/ops/dashboard` (ADMIN)
- 운영 메트릭 이름 목록(API): `GET /api/ops/metrics/names` (ADMIN)

기본 노출 설정은 `arc-core/src/main/resources/application.yml`에 반영되어 있다.

## 2) 이번에 추가된 Slack 메트릭

- `arc.slack.inbound.total`
  - tags: `entrypoint`, `event_type`
- `arc.slack.duplicate.total`
  - tags: `event_type`
- `arc.slack.dropped.total`
  - tags: `entrypoint`, `reason`, `event_type`
- `arc.slack.handler.duration`
  - tags: `entrypoint`, `event_type`, `success`
- `arc.slack.api.duration`
  - tags: `method`, `outcome`
- `arc.slack.api.retry.total`
  - tags: `method`, `reason`
- `arc.slack.response_url.total`
  - tags: `outcome`

## 3) 부하테스트 자동화

k6 스크립트와 실행 스크립트를 추가했다.

- 스크립트: `scripts/load/slack-gateway-load.js`
- 실행기: `scripts/load/run-slack-load-test.sh`

예시:

```bash
BASE_URL=http://localhost:8080 MODE=mixed VUS=100 DURATION=3m \
SLACK_SIGNING_SECRET=your_secret \
scripts/load/run-slack-load-test.sh
```

파라미터:

- `MODE=events|commands|mixed`
- `VUS` 동시 사용자 수
- `SLEEP_SECONDS` 가상 사용자 요청 간격(기본 `0.1`)
- `DURATION` 테스트 시간
- `SLACK_SIGNING_SECRET` (서명 검증 활성화 시 필수)

## 4) 백프레셔 설정

고부하에서 코루틴 대기열이 비정상적으로 커지지 않도록 fail-fast 모드를 기본값으로 둔다.

- `ARC_REACTOR_SLACK_FAIL_FAST_ON_SATURATION=true`
  - 포화 시 즉시 드롭(`arc.slack.dropped.total{reason="queue_overflow"}` 증가)
- `ARC_REACTOR_SLACK_NOTIFY_ON_DROP=false`
  - 드롭 알림 메시지 전송 비활성화(외부 Slack API 호출 폭증 방지)
- `ARC_REACTOR_SLACK_REQUEST_TIMEOUT_MS`
  - `fail-fast=false`일 때만 큐 대기 타임아웃으로 사용

## 5) Admin 동적 필터 규칙 API

런타임에 출력 필터 규칙을 변경할 수 있다.

- `GET /api/output-guard/rules`
- `POST /api/output-guard/rules` (ADMIN)
- `PUT /api/output-guard/rules/{id}` (ADMIN)
- `DELETE /api/output-guard/rules/{id}` (ADMIN)
- `POST /api/output-guard/rules/simulate` (ADMIN, dry-run)
- `GET /api/output-guard/rules/audits?limit=100` (ADMIN)

운영 포인트:

- 각 규칙은 `priority`(낮을수록 먼저 적용)를 갖는다.
- 관리 API로 규칙 변경 시 캐시 무효화가 즉시 발생해, 주기 캐시를 기다리지 않고 반영된다.
- `simulate`로 차단/마스킹 결과를 사전 검증한 뒤 저장 규칙을 적용할 수 있다.
