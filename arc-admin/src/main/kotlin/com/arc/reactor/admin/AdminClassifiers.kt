package com.arc.reactor.admin

/**
 * 가드/도구 오류 분류를 위한 공통 유틸리티.
 *
 * 여러 모듈(MetricCollectorAgentMetrics, MetricGuardAuditPublisher, MetricCollectionHook,
 * AgentTracingHooks)에서 중복되던 분류 로직을 통합한다.
 */
object AdminClassifiers {

    /** 가드 스테이지 이름 기반으로 거부 카테고리를 분류한다. */
    fun classifyGuardStage(stage: String): String = when {
        stage.contains("RateLimit", ignoreCase = true) -> "rate_limit"
        stage.contains("Injection", ignoreCase = true) -> "prompt_injection"
        stage.contains("Classification", ignoreCase = true) -> "classification"
        stage.contains("Permission", ignoreCase = true) -> "permission"
        stage.contains("InputValidation", ignoreCase = true) -> "input_validation"
        stage.contains("Unicode", ignoreCase = true) -> "unicode_normalization"
        stage.contains("TopicDrift", ignoreCase = true) -> "topic_drift"
        else -> "other"
    }

    /** 도구 오류 메시지를 분류한다. null 입력 시 null을 반환한다. */
    fun classifyToolError(errorMessage: String?): String? {
        if (errorMessage == null) return null
        return when {
            errorMessage.contains("timeout", ignoreCase = true) -> "timeout"
            errorMessage.contains("connection", ignoreCase = true) -> "connection_error"
            errorMessage.contains("permission", ignoreCase = true) -> "permission_denied"
            errorMessage.contains("not found", ignoreCase = true) -> "not_found"
            else -> "unknown"
        }
    }

    /** 도구 오류 메시지를 예외 타입명으로 분류한다. */
    fun classifyErrorType(errorMessage: String): String = when {
        errorMessage.contains("timeout", ignoreCase = true) -> "TimeoutException"
        errorMessage.contains("connection", ignoreCase = true) -> "ConnectionException"
        errorMessage.contains("permission", ignoreCase = true) -> "PermissionDenied"
        else -> "RuntimeException"
    }

    /** 모델명에서 provider를 추론한다. */
    fun deriveProvider(model: String): String = when {
        model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3") -> "openai"
        model.startsWith("claude-") -> "anthropic"
        model.startsWith("gemini-") -> "google"
        model.startsWith("mistral") || model.startsWith("codestral") -> "mistral"
        model.startsWith("command") -> "cohere"
        model.contains("llama") -> "meta"
        else -> "unknown"
    }
}
