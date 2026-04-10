# Arc Reactor Agent 작업 루프 (Directive 기반)

> **이 루프는 `docs/agent-work-directive.md`를 기준으로 실제 코드 개선을 반복한다.**
> 측정/재시작/인프라 점검이 아닌, 작업 지시서의 5대 우선 패턴을 하나씩 착실하게 구현하는 것이 목적이다.

## 0) 이 루프가 지향하는 것

1. **코드 개선 우선, 측정 보조** — 외부 API 쿼터, 서버 재시작, 프로브 등 인프라 점검은 원칙적으로 건너뛴다.
2. **한 번에 하나의 Directive 패턴** — 여러 패턴을 동시에 손대지 않는다.
3. **측정 없는 개선 금지** — 코드 변경 시 단위 테스트로 효과를 검증한다. 평가셋이 없으면 만든다.
4. **opt-in 기본** — 새 기능은 기본 off, 기존 경로를 깨지 않는다.
5. **보고서는 간결하게** — 작업 내역은 `docs/production-readiness-report.md`의 "10. 반복 검증 이력"에 Round N 섹션으로 기록한다.

## 1) 준비 (Read)

1. `docs/agent-work-directive.md` 전체 Read
2. `docs/production-readiness-report.md`의 마지막 Round 번호 확인 → N+1
3. `TaskList` 확인 — pending/in_progress Directive 태스크 파악

## 2) 작업 선택 (우선순위)

아래 우선순위대로 다음 작업을 고른다. **이미 완료된 것은 건너뛴다.**

| 우선 | 패턴 | 태스크 | 착수 조건 |
|------|------|--------|-----------|
| 1 | #4 Prompt Layer 공식 계층화 | #94 | 항상 가능 |
| 2 | #1 Tool Approval 4단계 구조화 | #95 | #94 완료 후 권장 |
| 3 | #5 Evaluation 상세 메트릭 | #96 | 언제든 |
| 4 | #2 ACI 도구 출력 요약 | #97 | 범위 큰 작업 |
| 5 | #3 Patch-First Editing | #98 | 보류 (범위 결정 후) |

**이미 진행 중인 태스크가 있으면 그것을 이어서 진행한다.** 새 작업을 시작할 때는 해당 태스크를 `in_progress`로 마킹하고, 완료 시 `completed`로 전환한다.

## 3) 작업 실행 규칙

### 3.1) 수정 전 필수

1. 변경 대상 파일 전부 Read
2. 관련 테스트 파일 존재 여부 확인
3. CLAUDE.md + `.claude/rules/*.md` 원칙 숙지 (cancellation, message pair, `content.orEmpty()`, 한글 KDoc 등)

### 3.2) 코드 수정

- **opt-in**: 새 기능은 `arc.reactor.*.enabled=false` 기본값으로 추가
- **선택 의존성**: `ObjectProvider<T>`
- **Configuration 추가**: `ArcReactorAutoConfiguration.kt`의 `@Import`에 등록
- **Kotlin/Spring 규칙**: 메서드 ≤20줄, 줄 ≤120자, 한글 주석, `Regex` 함수 내 생성 금지
- **Executor 수정**: `e.throwIfCancellation()` 필수, ReAct 루프 규칙 준수

### 3.3) 테스트 필수

- JUnit 5 + MockK + Kotest assertions
- 모든 assertion에 실패 메시지 (`assertTrue(x) { "이유" }`)
- 새 클래스마다 테스트 파일 생성
- 기존 테스트 깨지지 않는지 확인

### 3.4) 빌드 검증

```bash
./gradlew compileKotlin compileTestKotlin        # 0 warnings
./gradlew :arc-core:test --tests "변경한 Test 클래스"
./gradlew test                                    # 전체 회귀 (선택적)
```

## 4) 보고

Round N 섹션을 `docs/production-readiness-report.md`의 "10. 반복 검증 이력" 끝에 추가한다.

```markdown
### Round N — 🛠️ YYYY-MM-DDTHH:MM+09:00 — Directive #X: {패턴명}

**작업 종류**: Directive 기반 코드 개선 (측정 없음)
**Directive 패턴**: #{번호} {이름}
**완료 태스크**: #{TaskID}

#### 변경 요약
- 파일: `path:line`
- 핵심 변경: (한 줄)

#### 설계 메모
- (왜 이 접근인지, 대안 대비 장단점)

#### 테스트
- 신규 테스트: N개
- 결과: PASS/FAIL

#### 빌드
- compileKotlin: PASS
- 전체 테스트: PASS (또는 해당 모듈만)

#### opt-in 기본값
- 새 기능 flag: `arc.reactor.X.enabled` (기본 false)
- 기존 경로 영향: 없음

#### 다음 Round 후보
- (남은 Directive 태스크 중 다음 우선순위)
```

## 5) 커밋 & Push

```bash
git add {수정 파일들} docs/production-readiness-report.md
git commit -m "{접두사}: R{N} — Directive #{X} {한 줄 요약}

{상세 설명}"
git push origin main
```

**커밋 접두사 가이드**:
- `feat:` — 새 기능 추가
- `refactor:` — 구조 재정비 (계층화, 분리)
- `test:` — 테스트만 추가
- `docs:` — 문서만
- `perf:` — 성능 개선 (측정값 필수)
- `fix:` — 버그 수정

## 6) 금지 사항 (Directive §8 준수)

1. 외부 OSS의 코드/시스템 프롬프트를 그대로 가져오기 — **절대 금지**
2. 기본값으로 멀티 에이전트/자유 루프 켜기
3. 승인 없는 위험 도구 실행 경로 만들기
4. 평가셋 없이 "좋아졌다"고 판단하기
5. 메모리/컨텍스트 무제한 누적

## 7) QA 측정 루프와의 관계

R217까지의 QA 측정 기반 루프는 Gemini API 쿼터 소진으로 중단된 상태다. 쿼터 회복 후 별도 재개하며, **이 루프와 혼용하지 않는다**. Round 번호는 연속으로 사용한다 (R218~는 Directive 라운드).

## 8) 한 Round 체크리스트 (요약)

```
[ ] 1. docs/agent-work-directive.md Read
[ ] 2. 마지막 Round 번호 확인
[ ] 3. TaskList에서 다음 우선 작업 선택 (in_progress 마킹)
[ ] 4. 대상 파일 전부 Read
[ ] 5. 코드 수정 (opt-in, 한글 주석, 규칙 준수)
[ ] 6. 테스트 작성 (실패 메시지 필수)
[ ] 7. ./gradlew compileKotlin compileTestKotlin
[ ] 8. ./gradlew :arc-core:test --tests "변경 테스트"
[ ] 9. 보고서 Round N 섹션 추가
[ ] 10. git add + commit + push
[ ] 11. TaskList 완료 마킹, 다음 Round 후보 정리
```

## 9) 이 루프를 교체할 때

QA 측정 기반 루프로 돌아가고 싶으면 이 파일을 이전 버전으로 되돌리거나 별도 prompt 파일을 만들어 사용자 iteration 템플릿에서 참조한다. 현재 사용자 템플릿:

```
.claude/prompts/qa-verification-loop.md 파일을 Read로 읽고 그대로 실행하라.
추가로 docs/production-readiness-report.md의 '10. 반복 검증 이력'에서
마지막 Round 번호를 확인하고 +1로 진행. 보고서 업데이트 시 반드시 커밋+push 한다.
```

이 파일 이름을 유지하면 사용자 템플릿을 바꾸지 않고도 루프 동작을 바꿀 수 있다.
