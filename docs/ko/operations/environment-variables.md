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

## LLM 설정

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | Gemini API 키 (Spring AI 바인딩) | |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_API_KEY` | Embedding API 키 | |
| `SPRING_AI_GOOGLE_GENAI_CHAT_OPTIONS_MODEL` | 기본 모델 | `gemini-2.5-flash` |

## 기능 토글

| 변수 | 용도 | 기본값 |
|------|------|--------|
| `ARC_REACTOR_SLACK_TOOLS_ENABLED` | Slack 도구 (find_user 등) | `true` |
| `ARC_REACTOR_MCP_ALLOW_PRIVATE_ADDRESSES` | localhost MCP 허용 | `false` |
| `ARC_REACTOR_AUTH_SELF_REGISTRATION_ENABLED` | 자체 가입 허용 | `false` |
| `ARC_REACTOR_SLACK_MULTI_BOT_ENABLED` | 멀티 봇 모드 | `false` |
| `ARC_REACTOR_MAX_TOOLS_PER_REQUEST` | 요청당 최대 도구 수 | `30` |

## 파일 구조

```
.env          — 현재 활성 환경 (gitignore)
.env.local    — 로컬 개발 토큰 백업
.env.prod     — 프로덕션 토큰
.env.example  — 템플릿 (커밋됨)
```
