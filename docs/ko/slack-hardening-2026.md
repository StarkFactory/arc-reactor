# Slack 연동 하드닝 체크 (2026-02)

이 문서는 `arc-slack` 모듈을 Slack 최신 권장사항에 맞춰 점검/보강한 내용을 기록한다.

## 이번에 반영한 변경

1. 이벤트 중복 처리 방지 (`event_id`)
- Slack Events API의 `event_id`를 기준으로 중복 이벤트를 무시하도록 처리.
- 재시도 헤더(`X-Slack-Retry-Num`, `X-Slack-Retry-Reason`)를 로깅.
- 관련 코드:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventDeduplicator.kt`

2. 동시성 큐 대기 타임아웃 적용
- `requestTimeoutMs`를 실제 처리 경로(슬래시/이벤트)에서 사용.
- permit 획득 타임아웃 시:
  - 슬래시: `response_url`로 busy 메시지 전송
  - 이벤트: 스레드에 busy 메시지 전송
- 관련 코드:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt`

3. Slack Web API 429/5xx 재시도 보강
- `Retry-After` 헤더(429) 우선 적용.
- 5xx는 제한된 횟수로 백오프 재시도.
- 관련 코드:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/service/SlackMessagingService.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/config/SlackProperties.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/config/SlackAutoConfiguration.kt`

4. 운영 설정 키 추가
- `arc.reactor.slack.api-max-retries`
- `arc.reactor.slack.api-retry-default-delay-ms`
- `arc.reactor.slack.event-dedup-enabled`
- `arc.reactor.slack.event-dedup-ttl-seconds`
- `arc.reactor.slack.event-dedup-max-entries`
- 기본값 샘플 반영:
  - `arc-core/src/main/resources/application.yml`

## 테스트

- 실행: `./gradlew :arc-slack:test`
- 결과: 통과

추가된 테스트:
- `SlackEventDeduplicatorTest`
- `SlackEventControllerTest` (event_id dedupe 케이스)
- `SlackMessagingServiceTest` (429/5xx 재시도 케이스)
- `SlackCommandControllerTest` (큐 타임아웃 busy 응답 케이스)

## 남은 권장사항 (다음 단계)

1. 멀티 인스턴스 dedupe
- 현재 dedupe는 인메모리라 단일 인스턴스 기준.
- 다중 Pod/서버 운영 시 Redis 기반 dedupe(짧은 TTL)로 확장 권장.

2. 관측성 강화
- 슬랙 엔드포인트별 메트릭(성공/실패/중복드롭/큐타임아웃/429)을 Prometheus 지표로 분리.

3. 관리자 정책 연동
- 동적 금칙어/민감정보 정책을 Slack 입력/출력 경로에 함께 적용해 운영자가 즉시 조정 가능하도록 확장.

## 참고 레퍼런스

- Slack Events API (재시도, 3초 응답, event_id): https://docs.slack.dev/apis/events-api/
- Slack Events API - HTTP Request URLs: https://docs.slack.dev/apis/events-api/using-http-request-urls/
- Slack Web API Rate Limits (`Retry-After`): https://docs.slack.dev/apis/web-api/rate-limits/
- Slash Commands: https://docs.slack.dev/interactivity/implementing-slash-commands
- Slack Java SDK 이슈 (`X-Slack-Retry-*` 접근): https://github.com/slackapi/java-slack-sdk/issues/676
- Slack Bolt Python 이슈 (idempotence / event_id): https://github.com/slackapi/bolt-python/issues/564
