package com.arc.reactor.guard.canary

/**
 * Canary 시스템 프롬프트 후처리기
 *
 * 시스템 프롬프트 끝에 Canary 토큰 지시문을 추가한다.
 * 이를 통해 [com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard]가
 * LLM 출력에서 시스템 프롬프트 유출을 탐지할 수 있다.
 *
 * ## 왜 후처리기인가
 * 시스템 프롬프트 생성과 Canary 토큰 주입을 분리하여
 * 단일 책임 원칙(SRP)을 지킨다. Canary 기능을 비활성화해도
 * 시스템 프롬프트 로직에는 영향이 없다.
 *
 * @param canaryTokenProvider Canary 토큰 제공자
 *
 * @see SystemPromptPostProcessor 시스템 프롬프트 후처리 인터페이스
 * @see CanaryTokenProvider Canary 토큰 생성 및 검증
 */
class CanarySystemPromptPostProcessor(
    private val canaryTokenProvider: CanaryTokenProvider
) : SystemPromptPostProcessor {

    /**
     * 시스템 프롬프트 끝에 Canary 토큰 지시문을 추가한다.
     * 결과 형태: "{원본 시스템 프롬프트}\n\n{Canary 지시문}"
     */
    override fun process(systemPrompt: String): String {
        return "$systemPrompt\n\n${canaryTokenProvider.getInjectionClause()}"
    }
}
