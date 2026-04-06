# Arc Reactor 환경변수 가이드

## 필수 환경변수

| 변수 | 용도 | 예시 |
|------|------|------|
| `GEMINI_API_KEY` | Gemini LLM API 키 | `AIzaSy...` |
| `ARC_REACTOR_AUTH_JWT_SECRET` | JWT 서명 키 (32바이트 이상) | `arc-reactor-...` |
| `SPRING_DATASOURCE_URL` | PostgreSQL URL | `jdbc:postgresql://localhost:5432/arcreactor` |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 | `arc` |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 | `arc` |

## Slack 연동

| 변수 | 용도 | 비고 |
|------|------|------|
| `ARC_REACTOR_SLACK_ENABLED` | Slack 활성화 | `true`/`false` |
| `SLACK_BOT_TOKEN` | Bot User OAuth Token | `xoxb-...` |
| `SLACK_APP_TOKEN` | App-Level Token (Socket Mode) | `xapp-...` |
| `SLACK_CHANNEL_ID` | 기본 채널 ID | `C0AQ...` |

**중요**: Bot Token과 App Token은 반드시 같은 Slack 앱에서 발급.

## Atlassian MCP 연동

| 변수 | 용도 | 비고 |
|------|------|------|
| `ATLASSIAN_BASE_URL` | 사이트 URL | `https://site.atlassian.net` |
| `ATLASSIAN_CLOUD_ID` | Cloud ID | UUID 형태 |
| `ATLASSIAN_USERNAME` | 토큰 발급 계정 이메일 | |
| `ATLASSIAN_API_TOKEN` | 기본 API 토큰 | |
| `JIRA_API_TOKEN` | Jira 전용 (없으면 위 폴백) | |
| `CONFLUENCE_API_TOKEN` | Confluence 전용 | |
| `JIRA_USE_API_GATEWAY` | Granular 토큰 시 필수 | `true` |
| `CONFLUENCE_USE_API_GATEWAY` | Granular 토큰 시 필수 | `true` |
| `CONFLUENCE_ALLOWED_SPACE_KEYS` | 허용 스페이스 (콤마 구분) | `AHA,ENG` |

**판별법**: 토큰이 `ATATT3xFfGF0`으로 시작 → Granular → Gateway 필수.

### Granular 토큰 API 호출 규칙

Granular(Scoped) 토큰은 **API Gateway URL + Basic Auth** 조합만 정상 동작합니다.

| URL | 인증 | 결과 |
|-----|------|------|
| `api.atlassian.com/ex/{product}/{cloudId}/...` | Basic Auth (`email:token`) | 정상 |
| `api.atlassian.com/ex/{product}/{cloudId}/...` | Bearer 토큰 | Anonymous 처리 (공개 데이터만) |
| `{site}.atlassian.net/...` | Basic Auth 또는 Bearer | 401 |

Bearer로 호출하면 200이 오지만 사용자 미식별 → 빈 배열이 주요 증상입니다.
상세 설정은 `atlassian-mcp-server/docs/ATLASSIAN_TOKEN_SETUP.md` 참조.

## LLM 설정

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | Gemini API 키 (Spring AI 바인딩) | |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_API_KEY` | Embedding API 키 | |
| `SPRING_AI_GOOGLE_GENAI_CHAT_OPTIONS_MODEL` | 기본 모델 | `gemini-2.5-flash` |

## 인증

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `ARC_REACTOR_AUTH_JWT_SECRET` | JWT 서명 키 (32바이트 이상) | |
| `ARC_REACTOR_AUTH_ADMIN_EMAIL` | 초기 ADMIN 계정 이메일 | |
| `ARC_REACTOR_AUTH_ADMIN_PASSWORD` | 초기 ADMIN 비밀번호 (8자 이상) | |
| `ARC_REACTOR_AUTH_ADMIN_NAME` | ADMIN 표시 이름 | `Admin` |
| `ARC_REACTOR_AUTH_SELF_REGISTRATION_ENABLED` | 자체 가입 허용 | `false` |
| `ARC_REACTOR_AUTH_IAM_ENABLED` | aslan-iam 연동 | `false` |
| `ARC_REACTOR_AUTH_IAM_ISSUER` | IAM 발급자 URL | |
| `ARC_REACTOR_AUTH_IAM_PUBLIC_KEY_URL` | IAM JWKS URL | |

## 기능 토글

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `ARC_REACTOR_SLACK_TOOLS_ENABLED` | Slack 도구 (find_user 등) | `true` |
| `ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES` | localhost MCP 허용 | `false` |
| `ARC_REACTOR_SLACK_MULTI_BOT_ENABLED` | 멀티 봇 모드 | `false` |
| `ARC_REACTOR_AGENT_MULTI_AGENT_ENABLED` | 멀티에이전트 오케스트레이션 | `false` |
| `ARC_REACTOR_MAX_TOOLS_PER_REQUEST` | 요청당 최대 도구 수 | `30` |
| `ARC_REACTOR_SLACK_PROCESS_DM_WITHOUT_THREAD` | 스레드 없는 DM 처리 | `true` |

## Slack 고급 설정

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `ARC_REACTOR_SLACK_TRANSPORT_MODE` | 전송 방식 | `socket_mode` |
| `ARC_REACTOR_SLACK_SOCKET_BACKEND` | WebSocket 백엔드 | `java_websocket` |
| `ARC_REACTOR_SLACK_API_MAX_RETRIES` | API 재시도 횟수 | `2` |
| `ARC_REACTOR_SLACK_API_RETRY_DEFAULT_DELAY_MS` | 재시도 기본 대기 | `1000` |
| `ARC_REACTOR_SLACK_FAIL_FAST_ON_SATURATION` | 포화 시 즉시 거부 | `true` |
| `ARC_REACTOR_SLACK_NOTIFY_ON_DROP` | 드롭 시 사용자 알림 | `false` |
| `ARC_REACTOR_SLACK_EVENT_DEDUP_ENABLED` | 이벤트 중복 방지 | `true` |
| `ARC_REACTOR_SLACK_EVENT_DEDUP_TTL_SECONDS` | 중복 판단 TTL | `600` |
| `ARC_REACTOR_SLACK_USER_EMAIL_RESOLUTION_ENABLED` | 사용자 이메일 조회 | `true` |
| `ARC_REACTOR_SLACK_USER_EMAIL_CACHE_TTL_SECONDS` | 이메일 캐시 TTL | `3600` |

## 런타임 설정 (DB 오버라이드)

RuntimeSettingsService를 통해 DB에서 환경변수를 오버라이드할 수 있다.

| 우선순위 | 소스 | 설명 |
|---------|------|------|
| 1 (최고) | Redis 캐시 | 30초 TTL |
| 2 | DB `runtime_settings` | Admin API로 관리 |
| 3 (최저) | 환경변수 | `.env` 파일 |

Admin API: `GET/PUT/DELETE /api/admin/settings/{key}`

## 파일 구조

```
.env          — 현재 활성 환경 (gitignore)
.env.local    — 로컬 개발 토큰 백업
.env.prod     — 프로덕션 토큰
.env.example  — 템플릿 (커밋됨)
```
