package com.arc.reactor.agent.model

import java.time.Instant
import org.springframework.util.MimeType

/**
 * 구조화 출력을 위한 응답 형식 열거형.
 *
 * @see com.arc.reactor.agent.impl.StructuredOutputValidator 형식 검증
 * @see com.arc.reactor.agent.impl.StructuredResponseRepairer 형식 복구
 */
enum class ResponseFormat {
    /** 일반 텍스트 응답 (기본값) */
    TEXT,

    /** JSON 응답 (시스템 프롬프트가 LLM에 유효한 JSON 출력을 지시) */
    JSON,

    /** YAML 응답 (시스템 프롬프트가 LLM에 유효한 YAML 출력을 지시) */
    YAML
}

/**
 * 에이전트 실행 모드 열거형.
 *
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor 모드에 따른 실행 분기
 */
enum class AgentMode {
    /** 표준 모드 (도구 없이 단일 LLM 호출) */
    STANDARD,

    /** ReAct 모드 (사고-행동-관찰 루프) */
    REACT,

    /** 스트리밍 모드 — executeStream()은 AgentMode에 관계없이 동작. 향후 모드 분기를 위해 예약 */
    STREAMING,

    /** 계획-실행 모드: 먼저 도구 호출 계획을 세운 후, 계획대로 순차 실행한다. */
    PLAN_EXECUTE
}

/**
 * 멀티모달 입력을 위한 미디어 첨부 파일 (이미지, 오디오, 비디오 등).
 *
 * 멀티모달 LLM에 텍스트 프롬프트와 함께 전송할 수 있는 미디어 리소스를 래핑한다.
 * URI 기반 참조와 원시 바이트 데이터를 모두 지원한다.
 *
 * ## 사용 예시
 * ```kotlin
 * // URL에서
 * val imageUrl = MediaAttachment(
 *     mimeType = MimeTypeUtils.IMAGE_PNG,
 *     uri = URI("https://example.com/photo.png")
 * )
 *
 * // 원시 바이트에서
 * val imageBytes = MediaAttachment(
 *     mimeType = MimeTypeUtils.IMAGE_JPEG,
 *     data = fileBytes,
 *     name = "photo.jpg"
 * )
 * ```
 *
 * @param mimeType 미디어 MIME 타입
 * @param data 원시 바이트 데이터 (uri 또는 data 중 하나 필수)
 * @param uri 미디어 URI (uri 또는 data 중 하나 필수)
 * @param name 파일 이름 (선택)
 */
data class MediaAttachment(
    val mimeType: MimeType,
    val data: ByteArray? = null,
    val uri: java.net.URI? = null,
    val name: String? = null
) {
    init {
        require(data != null || uri != null) { "Either data or uri must be provided" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaAttachment) return false
        return mimeType == other.mimeType &&
            (data?.contentEquals(other.data ?: byteArrayOf()) ?: (other.data == null)) &&
            uri == other.uri && name == other.name
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (uri?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }
}

/**
 * 에이전트 실행 명령.
 *
 * @param systemPrompt 에이전트 동작을 정의하는 시스템 프롬프트
 * @param userPrompt 사용자의 입력 메시지
 * @param mode 실행 모드 (기본: REACT)
 * @param model 사용할 LLM 모델 이름 (null이면 기본 프로바이더 사용)
 * @param conversationHistory 대화 히스토리
 * @param temperature LLM temperature (null이면 기본값 사용)
 * @param maxToolCalls 최대 도구 호출 횟수
 * @param userId 사용자 ID (Guard/메트릭에서 식별용)
 * @param metadata 요청 메타데이터 (tenantId, channel, sessionId 등)
 * @param responseFormat 응답 형식 (TEXT, JSON, YAML)
 * @param responseSchema 응답 스키마 (JSON/YAML 형식 지정 시)
 * @param media 멀티모달 미디어 첨부 파일 목록
 * @see AgentExecutor 이 명령을 받아 실행
 */
data class AgentCommand(
    val systemPrompt: String,
    val userPrompt: String,
    val mode: AgentMode = AgentMode.REACT,
    val model: String? = null,
    val conversationHistory: List<Message> = emptyList(),
    val temperature: Double? = null,
    val maxToolCalls: Int = 10,
    val userId: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val responseFormat: ResponseFormat = ResponseFormat.TEXT,
    val responseSchema: String? = null,
    val media: List<MediaAttachment> = emptyList()
)

/**
 * 대화 히스토리용 메시지.
 *
 * @param role 메시지 역할 (SYSTEM, USER, ASSISTANT, TOOL)
 * @param content 메시지 내용
 * @param timestamp 메시지 생성 시각
 * @param media 멀티모달 미디어 첨부 파일 목록
 */
data class Message(
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val media: List<MediaAttachment> = emptyList()
)

/**
 * 메시지 역할 열거형.
 */
enum class MessageRole {
    /** 시스템 메시지 */
    SYSTEM,
    /** 사용자 메시지 */
    USER,
    /** 어시스턴트(LLM) 메시지 */
    ASSISTANT,
    /** 도구 응답 메시지 */
    TOOL
}

/**
 * 에이전트 실행 결과.
 *
 * @param success 실행 성공 여부
 * @param content 응답 내용 (성공 시)
 * @param errorCode 에러 코드 (실패 시)
 * @param errorMessage 에러 메시지 (실패 시)
 * @param toolsUsed 사용된 도구 이름 목록
 * @param tokenUsage 토큰 사용량
 * @param durationMs 실행 소요 시간 (밀리초)
 * @param metadata 응답 메타데이터 (grounded, answerMode, verifiedSources 등)
 * @see AgentExecutor 이 결과를 반환
 */
data class AgentResult(
    val success: Boolean,
    val content: String?,
    val errorCode: AgentErrorCode? = null,
    val errorMessage: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    val durationMs: Long = 0,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        /** 성공 결과를 생성한다. */
        fun success(
            content: String,
            toolsUsed: List<String> = emptyList(),
            tokenUsage: TokenUsage? = null,
            durationMs: Long = 0
        ) = AgentResult(
            success = true,
            content = content,
            toolsUsed = toolsUsed,
            tokenUsage = tokenUsage,
            durationMs = durationMs
        )

        /** 실패 결과를 생성한다. */
        fun failure(
            errorMessage: String,
            errorCode: AgentErrorCode? = null,
            durationMs: Long = 0
        ) = AgentResult(
            success = false,
            content = null,
            errorCode = errorCode,
            errorMessage = errorMessage,
            durationMs = durationMs
        )
    }
}

/**
 * LLM 토큰 사용량.
 *
 * @param promptTokens 프롬프트에 사용된 토큰 수
 * @param completionTokens 응답 생성에 사용된 토큰 수
 * @param totalTokens 총 토큰 수
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens
)
