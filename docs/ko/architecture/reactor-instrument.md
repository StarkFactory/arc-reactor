# Reactor Instrument

작성일: 2026-03-07  
대상: `arc-reactor`, `atlassian-mcp-server`, 향후 `arc-reactor-admin`

이 문서는 Arc Reactor 계열의 기술 판단, 포맷 정책, 안전 조회 정책, 변환 정책을 누적해서 적는 기준 문서다.  
현재 버전은 `workspace content normalization`과 `safe read`를 먼저 정리한다.

## 1. 현재 결론

- LLM 입력 기본 포맷은 `plain text`가 맞다.
- `HTML raw`와 `ADF raw`는 디버그용으로는 의미가 있지만, 읽기형 질의응답 기본 포맷으로는 비효율적이다.
- 이미지/첨부/다운로드 링크처럼 `구조가 남아 있는 media`는 코드 레벨에서 제거 가능하다.
- Markdown 이미지 문법, Confluence legacy image syntax, standalone image URL처럼 `텍스트로 표현된 이미지 토큰`도 패턴 기반으로 제거 가능하다.
- 반대로 사용자가 진짜 일반 문장처럼 써 놓은 텍스트는, 그것이 원래 이미지에서 왔는지 여부를 포맷만 보고 판정할 수 없다.

## 2. 포맷 선택 원칙

### 권장 순서

1. `plain text`
2. `markdown`
3. `HTML raw`
4. `ADF raw`

### 이유

#### Plain text

- 토큰 비용이 가장 낮다.
- 모델 입력이 가장 예측 가능하다.
- 보안 필터를 적용하기 쉽다.
- 읽기형 assistant에는 대부분 충분하다.

#### Markdown

- 제목, 리스트, 링크 구조를 일부 보존할 수 있다.
- 사람에게 보여주는 출력에는 좋다.
- 하지만 markdown 문법도 토큰을 먹기 때문에 plain text보다는 무겁다.

#### HTML raw

- 태그와 속성 때문에 토큰 낭비가 크다.
- media/macro/attachment가 섞여 있으면 필터링 비용이 높다.

#### ADF raw

- 편집기 구조는 보존되지만 JSON 키와 node 정보가 많아 토큰이 무겁다.
- 읽기형 Q&A에는 과하다.

## 3. 비용 모델

중요한 구분:

- `코드 변환 비용`은 CPU 비용이다.
- `LLM 토큰 비용`은 모델에 넣은 문자열 길이로 결정된다.

따라서 아래는 토큰 낭비가 아니다.

1. Jira/Confluence에서 raw 응답을 받음
2. 우리 서버에서 sanitize/normalize 수행
3. `plain text`만 LLM에 전달

반대로 아래는 비효율적이다.

- raw HTML을 그대로 모델에 넣음
- raw ADF를 그대로 모델에 넣음
- raw와 normalized를 둘 다 모델에 넣음

## 4. Safe Read 구분

### Case A. 구조가 남아 있는 media

예:

- `<ac:image>...</ac:image>`
- `<ri:attachment ... />`
- `<img ...>`
- `download/attachments/...`
- Jira attachment/media field

이건 제거 가능하다.

### Case B. 텍스트로 표현된 이미지 토큰

예:

- Markdown image: `![alt](https://.../image.png)`
- Markdown reference image: `![alt][img-ref]`
- Markdown image reference definition: `[img-ref]: https://.../image.webp`
- Confluence legacy wiki image: `!image.png!`, `!image.png|thumbnail!`
- standalone image URL: `https://.../diagram.webp`
- `data:image/png;base64,...`

이것도 제거 가능하다.  
핵심은 이 값들이 `일반 문장`이 아니라 `이미지를 표현하는 포맷 토큰`이라는 점이다.

### Case C. 일반 문장처럼 저장된 텍스트

예:

- 사용자가 그냥 일반 문장으로 길게 적은 텍스트
- OCR 결과가 이미 본문 문단처럼 저장된 텍스트
- 문서 변환기가 이미지 설명을 일반 문장으로 flatten한 텍스트

이건 다르다.  
이 경우는 문자열 자체가 평범한 본문처럼 보이기 때문에, 포맷 정보만으로 `이건 이미지 유래 텍스트다`라고 100% 판정할 수 없다.

즉:

- `이미지 토큰`은 제거 가능
- `일반 문장처럼 저장된 텍스트`는 포맷만으로 완전 판정 불가

## 5. 현재 구현 기준

### 이미 적용된 방향

- Confluence storage에서 image/file macro 제거
- Confluence page content 반환 전 sanitize 수행
- Confluence knowledge path에서 sanitize 후 plain text 추출
- Jira read API는 제한된 필드만 요청
- Arc Reactor는 verified source가 없으면 factual answer를 막는 방향으로 처리

### 이번에 추가할/추가한 text-encoded image 차단

- Markdown image syntax 제거
- Markdown reference image syntax 제거
- Markdown image reference definition 제거
- Confluence legacy wiki image syntax 제거
- `data:image/...;base64,...` 제거
- standalone image URL 제거

## 6. 운영 정책

### 기본 정책

- LLM 입력: `plain text`
- 사용자 출력: `plain text` 기본, 필요 시 `markdown`
- raw HTML/ADF는 디버그에서만 사용

### 보안 정책

- 이미지, 첨부, download URL 제거
- text-encoded image token 제거
- verified source가 없으면 factual answer 확정 금지

### 제품 정책

- "조금 덜 보여줘도 틀리지 않는 쪽"을 우선한다.
- unsupported/low-confidence content는 과감히 버린다.

## 7. 멀티모달 차단

이미지 업로드와 media URL까지 완전히 막고 싶다면:

```yaml
arc:
  reactor:
    multimodal:
      enabled: false
```

이렇게 하면:

- `/api/chat/multipart` 파일 업로드 차단
- JSON `mediaUrls` 입력 무시

## 8. 의사결정 표

| 선택지 | 토큰 비용 | 구현 난이도 | 보안성 | 의미 보존 | 권장도 |
|---|---:|---:|---:|---:|---:|
| HTML raw | 높음 | 낮음 | 낮음 | 중간 | 낮음 |
| ADF raw | 매우 높음 | 낮음 | 낮음 | 높음 | 낮음 |
| Markdown | 중간 | 중간 | 중간 | 중간 | 보조 |
| Plain text | 낮음 | 낮음~중간 | 높음 | 중간 | 최고 |

## 9. 다음 작업 후보

- text-encoded image 패턴 목록을 admin에서 관리 가능하게 만들기
- suspicious text block heuristic 도입 여부 검토
- page normalization diagnostics 화면 추가
- source별 unsupported-content ratio 측정

## 10. 관련 코드

- `arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentPolicyAndFeatureProperties.kt`
- `arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt`
- `arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/MultipartChatController.kt`
- `atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/confluence/ConfluenceContentSanitizer.kt`
- `atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/confluence/ConfluencePageTool.kt`
- `atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/confluence/ConfluenceKnowledgeTool.kt`
- `atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/client/JiraClient.kt`

## 외부 참고 문서

- Confluence Cloud REST API - Content body: <https://developer.atlassian.com/cloud/confluence/rest/v1/api-group-content-body/>
- Confluence Cloud REST API - Page: <https://developer.atlassian.com/cloud/confluence/rest/v2/api-group-page/>
- Jira Cloud REST API v3 Intro: <https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/>
- Atlassian Document Format: <https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/>
