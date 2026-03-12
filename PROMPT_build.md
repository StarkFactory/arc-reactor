# Arc Reactor — Plan & Build

## 시작 전

1. `claude-progress.txt` 마지막 20줄 읽기
2. `git log --oneline -5`
3. `IMPLEMENTATION_PLAN.md` 읽기
4. `KNOWN_ACCEPTABLE.md` 읽기

---

## 1단계: 탐색

IMPLEMENTATION_PLAN.md에 미완료(`- [ ]`) 항목이 **0개**일 때만 실행한다.

### 스캔 방식

**초회 스캔**: 병렬 서브에이전트 3~5개로 전체 코드베이스 탐색.
**재스캔** (이전 탐색 이후 수정이 있었을 때): `git diff --name-only <마지막탐색커밋>..HEAD`로 변경 파일만 + import 의존 1단계까지 탐색.

서브에이전트에 **반드시 KNOWN_ACCEPTABLE.md 전체 내용을 프롬프트에 포함**시킨다. "파일 읽어라"가 아니라 내용을 복사해서 넣는다.

### 탐색 영역 — 회전 렌즈 (Rotating Lens)

매 탐색마다 **다른 렌즈**로 코드를 본다. `claude-progress.txt`에서 마지막 사용 렌즈를 확인하고 **다음 번호**를 사용한다. 8번까지 가면 1번으로 돌아간다.

**항상 먼저 실행**:
- `./gradlew compileKotlin compileTestKotlin` — 0 warnings 확인
- `./gradlew test` — 전체 통과 확인

**렌즈 1 — 보안**: 인젝션 (SQL, 헤더, 경로, 로그), SSRF, 권한 우회, 인증 우회, 정보 노출, 타이밍 공격
**렌즈 2 — 견고성**: 리소스 누수 (스트림, 커넥션, 코루틴 스코프), 동시성 (race condition, 데드락), 에러 복구 실패, 미처리 예외
**렌즈 3 — Agent 동작**: ReAct 루프 정확성, tool call 실패 처리, 무한 루프 가능성, 컨텍스트 트리밍 안전성, 메시지 쌍 무결성
**렌즈 4 — 성능**: 핫패스 Regex/할당, N+1 쿼리, 불필요한 직렬화, 캐시 미스, 스레드풀 고갈
**렌즈 5 — 테스트 갭**: 소스 대비 테스트 누락, 핵심 경로 엣지 케이스 미검증, 에러 경로 테스트 부재
**렌즈 6 — API 계약**: Breaking change 위험, 응답 형식 불일치, @Operation 누락, 에러 응답 표준 미준수
**렌즈 7 — 운영**: 헬스체크 갭, 메트릭/로깅 품질, 설정 기본값 안전성, graceful shutdown
**렌즈 8 — Critical Gotchas**: CancellationException, activeTools=emptyList, .forEach in suspend, AssistantMessage builder, Guard null userId

### 필터링 (보고 전 필수)

1. KNOWN_ACCEPTABLE에 있으면 → 스킵
2. "실제 런타임에 문제가 되는가?" → 이론적이면 스킵
3. 설계 의도인가? → 코드 주석/CLAUDE.md에 근거가 있으면 스킵
4. 증명 가능한가? → 코드 경로를 보여줄 수 없으면 스킵

통과한 항목만 IMPLEMENTATION_PLAN.md에 등록. 기각 항목은 KNOWN_ACCEPTABLE.md에 추가.

### 등록 형식

```
- [ ] [HIGH|MED] P2: `파일:라인` — 설명
```

**LOW confidence → 등록하지 않음** → KNOWN_ACCEPTABLE에 기록.

### 탐색 후 즉시 구현

탐색에서 항목을 발견하면 **같은 반복 안에서** 2단계로 진행한다.

---

## 2단계: 구현

### 항목 선택

P0 → P1 → P2 → P3 → P4 순. **같은 우선순위에서 2~3개를 배치로 선택** 가능 (단, 수정 파일이 겹치지 않을 것).

### 구현 플로우

```
코드 탐색 (grep/Glob/Read) → 수정 → 자기 검증 → 다음 항목 (배치 내)
                                                    ↓
                              배치 완료 → 빌드 검증 → 커밋 → 머지 → 푸시
```

**배치 내 각 항목마다**:
1. 관련 코드를 먼저 검색한다. 이미 유사 구현이 있으면 활용한다
2. 수정한다 (CLAUDE.md Critical Gotchas 준수, 기존 테스트 assertion 변경 금지)
3. diff를 다시 읽고 자기 검증:
   - 원래 문제를 실제로 해결하는가?
   - 부작용은 없는가?
   - diff가 최소한인가?

**배치 전체 완료 후**:
1. `./gradlew compileKotlin compileTestKotlin` (0 warnings)
2. `./gradlew test` (전체 통과)
3. 실패 시: 원인 파악 → 수정 → 재검증. 3회 실패 시 `git checkout .` 후 다른 항목

### 브랜치 전략

| 우선순위 | 방식 |
|---------|------|
| P0~P2 | `git checkout -b improve/[설명]` → 머지 → 삭제 |
| P3~P4 | dev에 직접 커밋 (저위험, 오버헤드 절감) |

### 커밋 & 푸시

```bash
git add [수정 파일들]
git commit -m "[타입]: [한 줄 설명]"
# P0~P2인 경우만:
git checkout dev && git merge improve/[설명] && git branch -d improve/[설명]
git push origin dev
```

### 기록 업데이트

1. IMPLEMENTATION_PLAN.md: `- [ ]` → `- [x]` + 커밋 해시
2. claude-progress.txt에 1~3줄 append:
   ```
   [반복 N] 날짜 — 렌즈 X 탐색, 항목 A, B 수정 (커밋 hash). 다음: 렌즈 Y
   ```

---

## 완료 조건

아래를 **모두** 만족:

1. P0~P3 미완료 0개
2. 최근 탐색에서 P0~P3 신규 0건

<promise>IMPROVEMENTS COMPLETE</promise>

### 수렴 판정 (자동 완료)

하나라도 만족 시 추가 탐색 없이 완료:
- **8개 렌즈 전부** 최소 1회씩 실행 완료 + 마지막 **전체 사이클**(렌즈 1~8) 동안 P0~P3 신규 0건
- 총 **20회 초과** 시 P0~P1만 추가 작업

---

## 절대 규칙

1. 구현 전 코드 검색 먼저
2. 기존 테스트를 깨지 않는다
3. main 브랜치 절대 금지
4. AGENTS.md 수정 금지

## progress 관리

claude-progress.txt가 **50줄 초과** 시 오래된 항목을 `claude-progress-archive.txt`로 이동. 최근 20줄만 유지.
