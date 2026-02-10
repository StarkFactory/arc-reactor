# Multimodal Support

## One-Line Summary

**Allow agents to process images, audio, and video alongside text in a single request.**

---

## Why Multimodal?

Text-only agents are limited. Many real-world tasks require visual or audio understanding:

```
User: "What's wrong with this error screenshot?"
       + [screenshot.png attached]

[Agent]
  LLM sees both text AND image
  -> "The screenshot shows a NullPointerException at line 42..."
```

Without multimodal support, the user would have to manually transcribe the error message.
With multimodal support, the agent **sees the image directly** and responds accordingly.

---

## Core Concepts

### MediaAttachment

A data class that wraps media content with its MIME type. Supports two modes:

```kotlin
// Mode 1: URI-based (reference to an external resource)
MediaAttachment(
    mimeType = MimeType("image", "png"),
    uri = URI("https://example.com/photo.png")
)

// Mode 2: Byte array (inline content, e.g., from file upload)
MediaAttachment(
    mimeType = MimeType("image", "jpeg"),
    data = fileBytes
)
```

### AgentCommand.media

The `media` field on `AgentCommand` carries media attachments for the current request:

```kotlin
data class AgentCommand(
    val systemPrompt: String? = null,
    val userPrompt: String,
    val media: List<MediaAttachment> = emptyList(),
    // ... other fields
)
```

### MediaConverter

A utility that converts `MediaAttachment` objects to Spring AI `Media` objects.
This is handled internally -- you do not need to call it directly.

---

## Usage

### 1. URL-Based Media via JSON Endpoint

Send media URLs alongside your message. The agent fetches and processes them.

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Describe this image",
    "mediaUrls": [
      { "url": "https://example.com/photo.png", "mimeType": "image/png" }
    ]
  }'
```

Multiple media items are supported:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Compare these two diagrams",
    "mediaUrls": [
      { "url": "https://example.com/diagram-v1.png", "mimeType": "image/png" },
      { "url": "https://example.com/diagram-v2.png", "mimeType": "image/png" }
    ]
  }'
```

### 2. File Upload via Multipart Endpoint

Upload files directly from disk. MIME types are auto-detected from the file extension.

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=What's in this image?" \
  -F "files=@photo.png"
```

Multiple files:

```bash
curl -X POST http://localhost:8080/api/chat/multipart \
  -F "message=Summarize these documents" \
  -F "files=@slide1.png" \
  -F "files=@slide2.png" \
  -F "files=@slide3.png"
```

### 3. Programmatic (Kotlin)

Use `AgentCommand.media` to pass media directly from code:

```kotlin
val result = agentExecutor.execute(
    AgentCommand(
        systemPrompt = "You are a visual analysis assistant",
        userPrompt = "What's in this image?",
        media = listOf(
            MediaAttachment(
                mimeType = MimeType("image", "png"),
                uri = URI("https://example.com/photo.png")
            )
        )
    )
)
println(result.content)  // "The image shows a sunset over the ocean..."
```

With byte array data (e.g., from a file upload in your own controller):

```kotlin
val imageBytes = file.inputStream().readBytes()

val result = agentExecutor.execute(
    AgentCommand(
        userPrompt = "Describe this photo",
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

## How It Works

```
1. User sends request with media
   JSON endpoint: mediaUrls field -> MediaAttachment(uri=...)
   Multipart endpoint: files -> MediaAttachment(data=byte[])
      |
2. ChatController builds AgentCommand
   AgentCommand(userPrompt="...", media=[MediaAttachment, ...])
      |
3. SpringAiAgentExecutor receives the command
   MediaConverter converts MediaAttachment -> Spring AI Media objects
      |
4. Media is attached to the UserMessage
   UserMessage(text="What's in this image?", media=[Media(...)])
      |
5. LLM processes text + media together
   The multimodal LLM sees both the text prompt and the media content
      |
6. Agent returns the response
   "The image shows a sunset over the ocean with a sailboat..."
```

---

## Supported Media Types

| Type | Common MIME Types | Notes |
|------|------------------|-------|
| **Image** | `image/png`, `image/jpeg`, `image/gif`, `image/webp` | Most widely supported |
| **Audio** | `audio/mp3`, `audio/wav`, `audio/ogg` | Requires audio-capable model |
| **Video** | `video/mp4`, `video/webm` | Requires video-capable model |
| **PDF** | `application/pdf` | Model-dependent support |

Actual support depends on the underlying LLM. For example:
- **Gemini 2.0 Flash**: Images, audio, video, PDF
- **GPT-4o**: Images
- **Claude 3.5 Sonnet**: Images, PDF

---

## Configuration

No configuration is needed. Multimodal support works out of the box with any multimodal LLM.

If your LLM does not support a given media type, it will return an error from the provider -- Arc Reactor does not block any media types at the framework level.

---

## Multi-Agent with Media

Media is passed through the Supervisor pattern. When the Supervisor delegates to a worker, the media attachments are included in the worker's context:

```kotlin
MultiAgent.supervisor()
    .node("visual-qa") {
        systemPrompt = "Answer questions about images"
        description = "Visual question answering"
        tools = listOf(imageAnalysisTool)
    }
    .node("general") {
        systemPrompt = "Handle general questions"
        description = "General Q&A"
    }
    .execute(
        command = AgentCommand(
            systemPrompt = "Route to the appropriate worker",
            userPrompt = "What breed is this dog?",
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

## Reference Code

| File | Description |
|------|-------------|
| [`AgentModels.kt`](../../src/main/kotlin/com/arc/reactor/agent/model/AgentModels.kt) | MediaAttachment data class, AgentCommand.media field |
| [`MediaConverter.kt`](../../src/main/kotlin/com/arc/reactor/agent/model/MediaConverter.kt) | Converts MediaAttachment to Spring AI Media |
| [`ChatController.kt`](../../src/main/kotlin/com/arc/reactor/controller/ChatController.kt) | JSON endpoint (mediaUrls) and multipart endpoint |
