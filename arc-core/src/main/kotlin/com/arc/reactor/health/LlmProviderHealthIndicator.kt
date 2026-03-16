package com.arc.reactor.health

import mu.KotlinLogging
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

/**
 * 최소 하나의 LLM 프로바이더가 설정되어 있는지 확인하는 헬스 인디케이터.
 *
 * 유효한 API 키가 설정된 프로바이더가 하나 이상이면 UP,
 * 아무 프로바이더도 설정되지 않았으면 DOWN을 보고한다.
 * 실제 API 호출은 하지 않는다 (헬스 체크가 빈번하게 실행되므로 API 호출은 비용 발생).
 *
 * 프로바이더 상세 정보를 포함하여 운영자가 어떤 프로바이더가 사용 가능한지 확인할 수 있다:
 * ```json
 * {
 *   "status": "UP",
 *   "details": {
 *     "gemini": "configured",
 *     "openai": "not configured",
 *     "anthropic": "not configured",
 *     "defaultProvider": "gemini",
 *     "configuredCount": 1
 *   }
 * }
 * ```
 *
 * WHY: LLM 프로바이더 없이는 에이전트가 동작할 수 없다.
 * 배포 후 API 키 미설정을 조기에 감지하기 위해 헬스 체크에 포함한다.
 * API 호출 대신 환경 변수 존재 여부만 확인하여 비용을 발생시키지 않는다.
 *
 * @param environment Spring 환경 설정
 * @see com.arc.reactor.config.ChatModelProvider 프로바이더 레지스트리
 */
class LlmProviderHealthIndicator(
    private val environment: Environment
) : HealthIndicator {

    override fun health(): Health {
        // 각 프로바이더의 API 키 설정 여부를 확인한다
        val providerStatuses = PROVIDER_ENV_KEYS.map { (name, envKey) ->
            val configured = isProviderConfigured(envKey)
            name to configured
        }

        val details = linkedMapOf<String, Any>()
        for ((name, configured) in providerStatuses) {
            details[name] = if (configured) "configured" else "not configured"
        }

        val configuredCount = providerStatuses.count { (_, configured) -> configured }
        val defaultProvider = environment.getProperty(
            "arc.reactor.llm.default-provider",
            "gemini"
        )
        details["defaultProvider"] = defaultProvider
        details["configuredCount"] = configuredCount

        return if (configuredCount > 0) {
            logger.debug { "LLM 헬스: UP ($configuredCount 프로바이더 설정됨)" }
            Health.up().withDetails(details).build()
        } else {
            logger.warn { "LLM 헬스: DOWN (설정된 프로바이더 없음)" }
            Health.down()
                .withDetails(details)
                .withDetail("reason", "LLM 프로바이더 API 키가 설정되지 않음")
                .build()
        }
    }

    /**
     * 환경 변수/프로퍼티 값이 비어있지 않은지 확인한다.
     *
     * @param envKey 확인할 환경 변수 키
     * @return 프로바이더가 설정되어 있으면 true
     */
    private fun isProviderConfigured(envKey: String): Boolean {
        val value = environment.getProperty(envKey)?.trim().orEmpty()
        return value.isNotBlank()
    }

    private companion object {
        /**
         * 프로바이더 이름과 API 키를 보유하는 환경 변수/프로퍼티의 매핑.
         * Spring Boot는 `GEMINI_API_KEY`를 `gemini.api.key`로 자동 바인딩하지만(완화된 바인딩),
         * 실제 환경 변수 이름이 정식 소스이다.
         *
         * WHY: CLAUDE.md의 Spring AI 프로바이더 규칙에 따라 application.yml에 빈 기본값으로
         * 프로바이더 키를 선언하지 않고, 환경 변수로만 제공해야 한다.
         */
        val PROVIDER_ENV_KEYS = listOf(
            "gemini" to "gemini.api.key",
            "openai" to "spring.ai.openai.api-key",
            "anthropic" to "spring.ai.anthropic.api-key"
        )
    }
}
