# 멀티모달 지원

## 한 줄 요약

**이미지, 오디오, 비디오를 텍스트와 함께 에이전트에 전달할 수 있습니다.**

---

## 왜 멀티모달인가?

텍스트만 처리하는 에이전트는 한계가 있습니다. 현실의 많은 작업은 시각/청각 이해가 필요합니다:

```
사용자: "이 에러 스크린샷 뭐가 문제야?"
         + [screenshot.png 첨부]

[에이전트]
  LLM이 텍스트와 이미지를 함께 봄
  → "스크린샷에 42번 줄에서 NullPointerException이 발생했습니다..."
```

멀티모달 없이는 사용자가 에러 메시지를 직접 타이핑해야 합니다.
멀티모달이 있으면 에이전트가 **이미지를 직접 보고** 응답합니다.

---

## 핵심 개념

### MediaAttachment

미디어 콘텐츠를 MIME 타입과 함께 감싸는 데이터 클래스입니다. 두 가지 모드를 지원합니다:

```kotlin
// 모드 1: URI 기반 (외부 리소스 참조)
MediaAttachment(
    mimeType = MimeType("image", "png"),
    uri = URI("https://example.com/photo.png")
)

// 모드 2: 바이트 배열 (인라인 콘텐츠, 예: 파일 업로드)
MediaAttachment(
    mimeType = MimeType("image", "jpeg"),
    data = fileBytes
)
```

### AgentCommand.media

`AgentCommand`의 `media` 필드가 현재 요청의 미디어를 전달합니다:

```kotlin
data class AgentCommand(
    val systemPrompt: String? = null,
    val userPrompt: String,
    val media: List<MediaAttachment> = emptyList(),
    // ... 기타 필드
)
```

### MediaConverter

`MediaAttachment`를 Spring AI `Media` 객체로 변환하는 유틸리티입니다.
내부적으로 처리되므로 직접 호출할 필요가 없습니다.

---

## 사용법

### 1. JSON 엔드포인트로 URL 기반 미디어 전송

메시지와 함께 미디어 URL을 전송합니다. 에이전트가 가져와서 처리합니다.

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "이 이미지를 설명해줘",
    "mediaUrls": [
      { "url": "https://example.com/photo.png", "mimeType": "image/png" }
    ]
  }'
```

여러 미디어를 동시에 전송할 수 있습니다:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "이 두 다이어그램을 비교해줘",
    "mediaUrls": [
      { "url": "https://example.com/diagram-v1.png", "mimeType": "image/png" },
      { "url": "https://example.com/diagram-v2.png", "mimeType": "image/png" }
    ]
  }'
```

### 2. 멀티파트 엔드포인트로 파일 업로드

파일을 직접 업로드합니다. MIME 타입은 파일 확장자에서 자동 감지됩니다.

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=이 이미지에 뭐가 있어?" \
  -F "files=@photo.png"
```

여러 파일 업로드:

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=이 문서들을 요약해줘" \
  -F "files=@slide1.png" \
  -F "files=@slide2.png" \
  -F "files=@slide3.png"
```

### 3. 코드에서 직접 사용 (Kotlin)

`AgentCommand.media`로 미디어를 직접 전달합니다:

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "시각 분석 어시스턴트입니다",
        userPrompt = "이 이미지에 뭐가 있어?",
        media = listOf(
            MediaAttachment(
                mimeType = MimeType("image", "png"),
                uri = URI("https://example.com/photo.png")
            )
        )
    )
)
println(result.content)  // "이미지에는 바다 위로 지는 석양이 보입니다..."
```

바이트 배열 데이터 사용 (예: 커스텀 컨트롤러에서 파일 업로드 처리):

```kotlin
val imageBytes = file.inputStream().readBytes()

val result = agentExecutor.execute(
    AgentCommand(
        userPrompt = "이 사진을 설명해줘",
        media = listOf(
            MediaAttachment(
                mimeType = MimeType("image", "jpeg"),
                data = imageBytes
            )
        )
    )
)
```

---

## 동작 원리

```
1. 사용자가 미디어와 함께 요청 전송
   JSON 엔드포인트: mediaUrls 필드 → MediaAttachment(uri=...)
   멀티파트 엔드포인트: files → MediaAttachment(data=byte[])
      ↓
2. ChatController가 AgentCommand 구성
   AgentCommand(userPrompt="...", media=[MediaAttachment, ...])
      ↓
3. SpringAiAgentExecutor가 커맨드 수신
   MediaConverter가 MediaAttachment → Spring AI Media 객체로 변환
      ↓
4. UserMessage에 미디어 첨부
   UserMessage(text="이 이미지에 뭐가 있어?", media=[Media(...)])
      ↓
5. LLM이 텍스트 + 미디어를 함께 처리
   멀티모달 LLM이 텍스트 프롬프트와 미디어를 동시에 봄
      ↓
6. 에이전트가 응답 반환
   "이미지에는 바다 위로 지는 석양과 요트가 보입니다..."
```

---

## 지원 미디어 타입

| 종류 | 주요 MIME 타입 | 비고 |
|------|--------------|------|
| **이미지** | `image/png`, `image/jpeg`, `image/gif`, `image/webp` | 가장 넓게 지원됨 |
| **오디오** | `audio/mp3`, `audio/wav`, `audio/ogg` | 오디오 지원 모델 필요 |
| **비디오** | `video/mp4`, `video/webm` | 비디오 지원 모델 필요 |
| **PDF** | `application/pdf` | 모델마다 지원 여부 다름 |

실제 지원 범위는 사용하는 LLM에 따라 다릅니다. 예를 들어:
- **Gemini 2.0 Flash**: 이미지, 오디오, 비디오, PDF
- **GPT-4o**: 이미지
- **Claude 3.5 Sonnet**: 이미지, PDF

---

## 설정

별도 설정이 필요 없습니다. 멀티모달 LLM을 사용하면 바로 동작합니다.

LLM이 해당 미디어 타입을 지원하지 않으면 프로바이더에서 에러가 반환됩니다.
Arc Reactor는 프레임워크 레벨에서 미디어 타입을 차단하지 않습니다.

---

## 멀티에이전트에서의 미디어

Supervisor 패턴을 통해 미디어가 전달됩니다. Supervisor가 워커에게 위임할 때, 미디어 첨부 파일이 워커의 컨텍스트에 포함됩니다:

```kotlin
MultiAgent.supervisor()
    .node("visual-qa") {
        systemPrompt = "이미지에 대한 질문에 답합니다"
        description = "시각적 질의응답"
        tools = listOf(imageAnalysisTool)
    }
    .node("general") {
        systemPrompt = "일반 질문을 처리합니다"
        description = "일반 Q&A"
    }
    .execute(
        command = AgentCommand(
            systemPrompt = "적절한 워커에게 라우팅하세요",
            userPrompt = "이 강아지 품종이 뭐야?",
            media = listOf(
                MediaAttachment(
                    mimeType = MimeType("image", "jpeg"),
                    uri = URI("https://example.com/dog.jpg")
                )
            )
        ),
        agentFactory = { node -> /* ... */ }
    )
```

---

## 참고 코드

| 파일 | 설명 |
|------|------|
| [`AgentModels.kt`](../../src/main/kotlin/com/arc/reactor/agent/model/AgentModels.kt) | MediaAttachment 데이터 클래스, AgentCommand.media 필드 |
| [`MediaConverter.kt`](../../src/main/kotlin/com/arc/reactor/agent/model/MediaConverter.kt) | MediaAttachment를 Spring AI Media로 변환 |
| [`ChatController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ChatController.kt) | JSON 엔드포인트(mediaUrls)와 멀티파트 엔드포인트 |
