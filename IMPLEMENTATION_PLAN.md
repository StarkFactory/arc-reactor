# Implementation Plan
> 마지막 업데이트: 2026-03-11 | 분석 반복: 3회차 (P2-P4 코드 탐색 + 에러 유발 테스트)

## P0 — 즉시 수정 (런타임 크래시, 데이터 손실)

(없음)

## P1 — Agent 동작 결함 (잘못된 응답, 무한 루프, 도구 실패 미처리)

(모두 완료)

## P2 — 견고성 (에러 복구 실패, 리소스 누수, 동시성 문제)

(모두 완료)

## P3 — 보안 (권한 우회, 인젝션, 정보 노출)

- [x] `SessionController.kt:135,152` — Content-Disposition header injection — 수정 완료 (6c31022)
- [x] `AuthRateLimitFilter.kt:55` — X-Forwarded-For trust를 opt-in으로 변경 — 수정 완료 (6e8640f)

## P4 — 성능 (핫패스 비효율, 불필요한 할당, 확장성 병목)

(분석 완료 — 이슈 없음. TokenEstimator, ConversationMessageTrimmer, 스트리밍 버퍼링 모두 정상)

## P5 — 테스트 갭 (Agent 동작의 중요 시나리오가 테스트되지 않음)

(모두 완료)

## 기동 검증 메모

### 서버 기동에 필요한 추가 환경변수 (PROMPT에 반영 필요)
- `ARC_REACTOR_AUTH_JWT_SECRET` — 32자 이상 필수 (openssl rand -base64 32)
- `--arc.reactor.postgres.required=false` — PostgreSQL 없이 기동
- `--arc.reactor.auth.self-registration-enabled=true` — 사용자 등록 허용
- `ARC_REACTOR_AUTH_ADMIN_EMAIL` + `ARC_REACTOR_AUTH_ADMIN_PASSWORD` — 초기 ADMIN 계정

### 정상 동작 확인
- Health check: OK
- Auth register/login: OK (JWT 발급 정상)
- Empty message validation: OK (400 + ErrorResponse)
- Invalid token: OK (401)
- Casual prompt ("안녕"): OK (응답 정상)
- MCP 서버 연결: OK (41 tools loaded)

### 동작 문제 확인 (수정 전)
- 일반 질문 ("오늘 날씨 어때?"): 차단됨 (unverified_sources) → 수정 완료 (3330c9c)
- 정보 요청 ("당신은 누구인가요?"): 차단됨 (unverified_sources, "누구" 패턴) → 수정 완료 (3330c9c)
- MCP 도구 호출: 선택됨 but 정책 차단 (policy_denied — MCP 서버 access policy 미설정)

### 에러 유발 테스트 (반복 3)
- 빈 메시지 (""): 400 Validation failed — 정상
- 공백만 ("   "): 400 Validation failed — 정상
- 5001자 입력: 200 통과 — input-max-chars=10000 (application.yml). CLAUDE.md 기본값 참고 표 5000과 불일치 (문서 이슈, 코드 정상)
- 10001자 입력: 200 + success=false + guard rejected — 정상
- 50001자 입력: 400 Jakarta @Size validation — 정상
- Zero-width Unicode: 200 정상 응답 — UnicodeNormalization 가드 통과 (비율 미달)
- 인증 없음: 401 — 정상
- 잘못된 토큰: 401 — 정상
- 스트리밍 (/api/chat/stream): SSE 이벤트 정상 수신 — 정상
- 한국어 인사 ("안녕하세요"): 정상 응답, blockReason=null — 정상

## 완료
- [x] P2: `ToolCallOrchestrator.kt:102,240` — 실패한 도구의 에러 메시지 sanitize 적용 (613d0a0)
- [x] P5: `VerifiedSourcesResponseFilter` — casual/general 경계값 테스트 4개 추가 (83ade27, 3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:127-132` — looksLikeInformationRequest ?/패턴 과잉 차단 수정 (3330c9c)
- [x] P1: `VerifiedSourcesResponseFilter.kt:162-164` — CASUAL_PROMPTS에 한국어 변형 추가 (83ade27)

## 완료 (이전 사이클)
- [x] P1: `ConversationMemoryStressTest.kt` — 9개 catch 블록에 throwIfCancellation 추가 (9657db0)
- [x] P2: `TokenEstimator` — DefaultTokenEstimator 단위 테스트 추가 (72e4753)
- [x] P2: `ToolResponsePayloadNormalizer` — 단위 테스트 추가 (d471298)
- [x] P2: `BlockingToolCallbackInvoker` — 단위 테스트 추가 (3f904ea)
