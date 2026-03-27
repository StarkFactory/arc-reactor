# Architecture Rules

## 확장 포인트

| Component | 규칙 | 실패 정책 |
|-----------|------|----------|
| **GuardStage** | 내장 순서 0-5, 커스텀 10+ | fail-close (차단) |
| **Hook** | try-catch + `e.throwIfCancellation()` | fail-open (계속) |
| **OutputGuardStage** | Modified 반환 가능 (콘텐츠 변환). 순서 100+ | fail-close |
| **ResponseFilter** | 내장 1-99, 커스텀 100+ | 순서 기반 체인 |
| **ToolCallback** | `"Error: ..."` 문자열 반환, throw 금지 | LLM 대안 탐색 |
| **Bean** | `@ConditionalOnMissingBean` 필수 | 사용자 교체 가능 |

## 실행 모드

- **REACT**: LLM ↔ Tool 반복 루프 (기본)
- **PLAN_EXECUTE**: LLM이 JSON 계획 생성 → PlanValidator 검증 → 순차 실행 → 결과 종합
- **STANDARD**: 도구 없이 단일 LLM 호출
- **Streaming**: 별도 클래스 체인 — 비스트리밍과 독립. 양쪽 모두 수정 필요

## 비용/예산

- `StepBudgetTracker.EXHAUSTED` → `activeTools = emptyList()` + `BUDGET_EXHAUSTED` 에러코드
- `CostCalculator` 이원화: arc-core(Double, 실시간), arc-admin(BigDecimal, 정산) — 모델 가격 양쪽 동기화
- 미등록 모델 → 비용 0.0 반환 (예산 추적 무효화) → `DEFAULT_PRICING` 테이블 업데이트 필수
