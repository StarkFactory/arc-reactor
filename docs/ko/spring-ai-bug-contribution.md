# Spring AI 버그 발견 및 오픈소스 기여 기록

Arc Reactor 프레임워크를 개발하는 과정에서 Spring AI의 Google GenAI 모듈 버그를 발견하고, 이슈 제기 및 PR을 통해 수정에 기여한 전체 과정을 기록한다.

---

## 1. 배경

Arc Reactor는 Spring AI 기반의 AI Agent 프레임워크다. v2.0에서 **Semantic Tool Discovery**(의미 기반 도구 선택)와 **Human-in-the-Loop**(도구 실행 승인) 기능을 추가한 후, Playwright를 이용한 E2E 검증을 진행하고 있었다.

**사용 환경:**
- Spring AI 1.1.2
- Spring Boot 3.5.9
- Kotlin 2.3.10, JDK 21
- LLM: Google Gemini 2.0 Flash (`gemini-2.0-flash`)

## 2. 버그 발견 과정

### 2.1 E2E 테스트 시나리오

Semantic Tool Discovery가 올바른 도구를 선택하는지 검증하기 위해, 3개의 예제 도구를 등록하고 Playwright로 테스트했다.

| 프롬프트 | 기대 도구 | 결과 |
|----------|-----------|------|
| "3 + 5는?" | `calculator` | 성공 — `"3.0 + 5.0 = 8.0"` 반환 |
| "지금 몇시야?" | `current_datetime` | **실패 — 서버 크래시** |
| "서울 날씨 알려줘" | `weather` | 성공 |

Calculator는 정상 동작했지만, DateTimeTool은 서버가 크래시했다.

### 2.2 에러 분석

서버 로그에서 다음 에러를 확인했다:

```
java.lang.RuntimeException: Failed to parse JSON: 2026-02-11 00:14:54 (수요일) [Asia/Seoul]
    at o.s.ai.google.genai.GoogleGenAiChatModel.parseJsonToMap(GoogleGenAiChatModel.java:397)
    at o.s.ai.google.genai.GoogleGenAiChatModel.messageToGeminiParts(GoogleGenAiChatModel.java:337)
```

**원인**: DateTimeTool이 `"2026-02-11 00:14:54 (수요일) [Asia/Seoul]"`이라는 plain text를 반환했는데, Spring AI의 `GoogleGenAiChatModel.parseJsonToMap()`이 이를 JSON으로 파싱하려다 실패한 것이다.

### 2.3 원인 추적

Spring AI 소스를 디컴파일해서 확인한 결과:

```java
// GoogleGenAiChatModel.java — 도구 응답을 Gemini API 형식으로 변환
if (message instanceof ToolResponseMessage toolResponseMessage) {
    return toolResponseMessage.getResponses().stream()
        .map(response -> Part.builder()
            .functionResponse(FunctionResponse.builder()
                .name(response.name())
                .response(parseJsonToMap(response.responseData()))  // 여기서 크래시
                .build())
            .build())
        .toList();
}
```

`parseJsonToMap()`은 도구 응답을 `ObjectMapper.readValue()`로 JSON 파싱하는데, **실패 시 RuntimeException을 throw**한다. plain text에 대한 fallback이 없었다.

```java
private static Map<String, Object> parseJsonToMap(String json) {
    try {
        Object parsed = OBJECT_MAPPER.readValue(json, Object.class);
        // ... List, Map, primitive 처리
    } catch (Exception e) {
        throw new RuntimeException("Failed to parse JSON: " + json, e);  // fallback 없음!
    }
}
```

## 3. 버그 검증 — 한국어 문제인가?

처음에는 한국어(`수요일`) 때문에 발생한 문제인지 의심했다. Jackson ObjectMapper로 다양한 입력을 테스트했다:

| 입력 | 결과 |
|------|------|
| `2026-02-11 00:14:54 (수요일) [Asia/Seoul]` | **FAIL** — `2026` 숫자 파싱 후 `-`에서 크래시 |
| `2026-02-11 00:14:54 (Wednesday) [Asia/Seoul]` | **FAIL** — 동일한 에러 |
| `Hello, the current time is 3pm` | **FAIL** — `Hello`는 JSON 토큰 아님 |
| `Error: Invalid timezone` | **FAIL** — `Error`는 JSON 토큰 아님 |
| `3.0 + 5.0 = 8.0` | "PASS" — **데이터 손실** (`3.0`만 남음) |
| `{"key": "value"}` | PASS — 유효한 JSON |

**결론: 한국어와 무관한, Spring AI 자체의 버그.** 영어든 한국어든 JSON이 아닌 모든 도구 응답에서 동일하게 크래시한다. Jackson이 `2026`을 숫자로 파싱 시작하다가 5번째 바이트 `-`에서 실패하는 것이므로, 한국어 문자(`수요일`)에는 아예 도달하지도 않는다.

추가로 **데이터 손실 버그**도 발견했다: `"3.0 + 5.0 = 8.0"` 입력 시 Jackson이 `3.0`만 파싱하고 나머지를 무시하여, 도구의 실제 결과가 소실된다.

## 4. 기존 보고 여부 확인

Spring AI GitHub에서 동일한 이슈를 검색한 결과:

- **#4230** (2025년 9월) — Vertex AI 모듈에서 동일한 버그 보고, 5개월째 미해결
- **embabel-agent #1391** (2026-02-08) — 다른 프레임워크 사용자가 동일 크래시 경험

여러 사람이 독립적으로 부딪힌 실제 버그였으나, Google GenAI 모듈에 대해서는 아직 아무도 이슈를 올리지 않은 상태였다. 2.0.0-M1 소스를 확인한 결과 코드가 동일하여, 최신 버전에서도 미수정 상태였다.

## 5. 오픈소스 기여

### 5.1 Spring AI 기여 절차

Spring 프로젝트의 기여 절차:

1. **이슈 먼저 생성** (GitHub Issues, Bug Report 템플릿)
2. **Fork** → 브랜치 `gh-{이슈번호}` → 코드 수정 → PR
3. **DCO** (Developer Certificate of Origin) sign-off 필수 (`git commit -s`)
4. PR은 항상 **main 브랜치** 대상 (1.1.x 백포트는 메인테이너가 판단)

### 5.2 이슈 생성

**Issue #5437**: [GoogleGenAiChatModel.parseJsonToMap() crashes on non-JSON tool responses and silently loses data](https://github.com/spring-projects/spring-ai/issues/5437)

포함 내용:
- 버그 설명 (크래시 + 데이터 손실 2개)
- 환경 정보
- Java 코드로 된 재현 방법
- 8개 입력에 대한 테스트 매트릭스
- 전체 스택 트레이스
- 소스 코드 기반 원인 분석
- 수정 제안 코드

### 5.3 코드 수정

**수정 내용**: `parseJsonToMap()`의 catch 블록에서 RuntimeException 대신 `{"result": "<text>"}`로 감싸서 반환

```java
// Before
catch (Exception e) {
    throw new RuntimeException("Failed to parse JSON: " + json, e);
}

// After
catch (Exception e) {
    Map<String, Object> wrapper = new HashMap<>();
    wrapper.put("result", json);
    return wrapper;
}
```

**수정 근거**:
- 같은 메서드에서 List, primitive 값도 `{"result": value}`로 감싸고 있어 패턴이 일관됨
- Vertex AI 모듈(`VertexAiGeminiChatModel`)에서도 동일한 `"result"` 키 패턴 사용
- Gemini API의 `FunctionResponse.response`는 `Map<String, Object>`를 받으므로 유효함
- 기존에 정상 동작하던 JSON 입력은 try 블록에서 성공하므로 catch에 도달하지 않아 영향 없음

### 5.4 테스트 작성

4개의 유닛 테스트를 추가했다:

| 테스트 | 입력 | 검증 |
|--------|------|------|
| `shouldHandlePlainTextToolResponse` | `"2026-02-11 00:14:54 (Tuesday) [UTC]"` | `{"result": "..."}` 반환 |
| `shouldHandleErrorTextToolResponse` | `"Error: Invalid timezone 'foo/bar'"` | `{"result": "..."}` 반환 |
| `shouldHandleValidJsonToolResponse` | `{"temperature": 25, "unit": "celsius"}` | 기존 동작 유지 (회귀 테스트) |
| `shouldHandleJsonArrayToolResponse` | `["item1", "item2", "item3"]` | `{"result": [...]}` 반환 |

모듈 전체 184개 테스트 통과 확인.

### 5.5 PR 생성

**PR #5438**: [GH-5437: Handle non-JSON tool responses in GoogleGenAiChatModel.parseJsonToMap()](https://github.com/spring-projects/spring-ai/pull/5438)

- 변경: +110 / -1 (catch 블록 수정 1줄 + 테스트 파일 1개)
- DCO 체크 통과
- Spring Java Format 검증 통과
- 커밋: `Jinan Choi <dig04059@gmail.com>`

## 6. 기여 시 주의사항 (경험에서 얻은 교훈)

### 코드 스타일
- **탭 들여쓰기** (스페이스 아님) — Spring 프로젝트 전체 규칙
- **LF 줄바꿈** (CRLF 아님)
- **Apache License 2.0 헤더** 필수 (새 파일)
- **`@author` + `@since` 태그** — 다른 테스트 파일과 일관성 유지
- **120자 줄 제한**

### 기여 프로세스
- **이슈 먼저**, PR은 그 다음 — trivial 수정이 아니면 반드시 이슈 선행
- **DCO sign-off** — `git commit -s` (CLA 대신 DCO 사용)
- **본명 사용** — CONTRIBUTING.adoc에 "real first and last name" 명시
- **단일 커밋으로 squash** — 깔끔한 히스토리 유지
- **main 브랜치 대상** — 구버전 백포트는 메인테이너가 결정
- **Reviewer/Label 지정 불가** — 외부 기여자는 권한 없음, 메인테이너가 처리

### AI 도구 사용
- Spring AI에 AI 보조 기여를 금지하는 정책은 없음 (2026-02-11 기준)
- DCO의 "created in whole or in part by me" 조항이 유일한 관련 규정
- 업계 동향: 일부 프로젝트(QEMU, Ghostty)는 금지, LLVM은 공개 의무화, Spring은 아직 정책 없음

## 7. 링크

| 항목 | URL |
|------|-----|
| Issue #5437 | https://github.com/spring-projects/spring-ai/issues/5437 |
| PR #5438 | https://github.com/spring-projects/spring-ai/pull/5438 |
| 관련 이슈 #4230 (Vertex AI) | https://github.com/spring-projects/spring-ai/issues/4230 |
| Spring AI CONTRIBUTING | https://github.com/spring-projects/spring-ai/blob/main/CONTRIBUTING.adoc |
| DCO 원문 | https://developercertificate.org/ |
