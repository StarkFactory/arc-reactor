# Slack 운영 메트릭/부하테스트 런북

## 1) 메트릭 어디서 보나

Arc Reactor 실행 후 다음 엔드포인트에서 확인한다.

- 메트릭 목록: `GET /actuator/metrics`
- 특정 메트릭: `GET /actuator/metrics/{name}`
- Prometheus scrape: `GET /actuator/prometheus`

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
- `DURATION` 테스트 시간
- `SLACK_SIGNING_SECRET` (서명 검증 활성화 시 필수)

## 4) Admin 동적 필터 규칙 API

런타임에 출력 필터 규칙을 변경할 수 있다.

- `GET /api/output-guard/rules`
- `POST /api/output-guard/rules` (ADMIN)
- `PUT /api/output-guard/rules/{id}` (ADMIN)
- `DELETE /api/output-guard/rules/{id}` (ADMIN)

이 규칙은 Output Guard 파이프라인에서 실시간(짧은 주기 캐시 갱신)으로 반영된다.
