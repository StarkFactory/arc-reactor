package com.arc.reactor.guard.canary

/**
 * 시스템 프롬프트 후처리기
 *
 * 시스템 프롬프트가 LLM에 전송되기 전에 수정할 수 있는 확장 포인트이다.
 * Canary 토큰 주입이나 기타 보안 마커 삽입에 사용된다.
 *
 * 왜 함수형 인터페이스인가: 구현이 단순한 단일 메서드이므로
 * 람다로도 구현할 수 있도록 `fun interface`로 정의한다.
 *
 * @see CanarySystemPromptPostProcessor Canary 토큰 주입 구현체
 * @see CanaryTokenProvider Canary 토큰 생성기
 */
fun interface SystemPromptPostProcessor {
    /**
     * 시스템 프롬프트를 후처리한다.
     *
     * @param systemPrompt 원본 시스템 프롬프트
     * @return 수정된 시스템 프롬프트
     */
    fun process(systemPrompt: String): String
}
