# ReAct 재시도 강화 + Confluence RAG 경합 해소

> 날짜: 2026-03-19 | 상태: 승인됨

## 배경

감사 #17~#26에서 반복 확인된 P1 이슈 3건을 코드 수준에서 완화한다.

| 이슈 | 근본 원인 | 완화 전략 |
|------|----------|----------|
| JQL 오류 후 ReAct 재시도 미작동 | LLM이 tool_call 대신 텍스트 응답 생성 | 텍스트 응답 감지 → 루프 계속 |
| ReAct 체이닝 실패 | 동일 근본 원인 | 동일 수정으로 해결 |
| Confluence 도구 라우팅 간헐적 실패 | RAG 키워드와 도구 키워드 경합 | RAG에서 workspace 키워드 제거 |

추가로 P2 캐시 metadata 누락은 코드 수정(724ce441) 완료 확인 → 해결 완료로 이동.

## 변경 1: ReAct 텍스트 응답 재시도

### 현재 동작
```
도구 에러 → TOOL_ERROR_RETRY_HINT(UserMessage) 주입 → LLM 호출
→ LLM이 텍스트 응답 생성 ("재시도하겠습니다")
→ pendingToolCalls.isEmpty() == true → 루프 종료 ❌
```

### 목표 동작
```
도구 에러 → 힌트 주입 → LLM 호출
→ LLM이 텍스트 응답 생성
→ 도구 에러 직후 + 재시도 < 2 → 강화 힌트(SystemMessage) 주입 → 루프 계속
→ LLM이 tool_call 생성 (또는 2회 실패 후 종료)
```

### 수정 파일

**ReActLoopUtils.kt**:
- `TOOL_ERROR_FORCE_RETRY_HINT`: SystemMessage 기반 강화 힌트 추가
- `MAX_TEXT_RETRIES_AFTER_TOOL_ERROR = 2`: 무한루프 방지 상수
- `injectForceRetryHint()`: 강화 힌트 주입 메서드
- `hasToolError()`: 도구 응답에서 에러 존재 여부 확인 헬퍼

**ManualReActLoopExecutor.kt**:
- `hadToolError` 플래그 + `textRetryCount` 카운터 추가
- 단계 C에서 `pendingToolCalls.isEmpty()` 시 재시도 조건 검사
- 조건 충족 시 강화 힌트 주입 후 `continue`

**StreamingReActLoopExecutor.kt**:
- Manual과 동일한 로직 적용

### 안전장치
- 최대 2회 텍스트 재시도 (무한루프 불가)
- maxToolCalls 도달 시 재시도 비활성화
- 도구 에러가 없었던 경우 기존 동작 그대로 유지

## 변경 2: Confluence RAG 경합 해소

### 현재 문제
`RAG_TRIGGER_KEYWORDS`에 "confluence", "wiki" 등이 포함되어 있어,
Confluence 관련 질문 시 RAG가 먼저 트리거 → 도구가 호출되지 않는 경우 발생.

### 수정
`RagRelevanceClassifier.kt`의 `RAG_TRIGGER_KEYWORDS`에서 workspace 도구 키워드 4개 제거:
- `confluence`, `wiki`, `컨플루언스`, `위키`

### 안전성
- "confluence 아키텍처 문서" → "아키텍처", "문서" 키워드가 RAG 트리거 → 영향 없음
- "confluence 검색해줘" → RAG 미트리거 → 도구 라우팅으로 정상 처리 → 개선
- 순수 workspace 쿼리만 영향받으며, 이 경우 도구가 더 적합

## 변경 3: AUDIT_CHECKLIST 업데이트

- P2 "캐시 metadata 누락" → 해결 완료 섹션으로 이동
- P1 3건에 코드 완화 적용 기록 추가

## 테스트 전략

1. 기존 테스트 전체 통과 확인: `./gradlew test`
2. 컴파일 0 warnings: `./gradlew compileKotlin compileTestKotlin`
3. ReAct 재시도 단위 테스트 추가 (ManualReActLoopExecutor)
4. RagRelevanceClassifier 단위 테스트 업데이트
