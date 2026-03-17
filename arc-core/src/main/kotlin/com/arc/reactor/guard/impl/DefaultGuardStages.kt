package com.arc.reactor.guard.impl

import com.arc.reactor.agent.config.TenantRateLimit
import com.arc.reactor.guard.InjectionDetectionStage
import com.arc.reactor.guard.InjectionPatterns
import com.arc.reactor.guard.InputValidationStage
import com.arc.reactor.guard.RateLimitStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.formatBoundaryRuleViolation
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 기본 속도 제한 구현체 (1단계)
 *
 * Caffeine 캐시 기반으로 사용자별 분당/시간당 요청 수를 제한한다.
 * [tenantRateLimits] 맵을 통해 테넌트별 차등 제한을 지원한다.
 *
 * ## 원자성 보장
 * 분당·시간당 카운터를 synchronized 블록 안에서 동시에 검사 및 증가시킨다.
 * 이는 두 캐시를 독립적으로 접근할 때 발생하는 TOCTOU(Time-Of-Check-To-Time-Of-Use)
 * 경쟁 조건을 제거한다.
 *
 * ## 캐시 TTL 전략
 * 분당 캐시(1분 TTL)와 시간당 캐시(1시간 TTL)를 분리 운영한다.
 * 각 캐시 엔트리가 만료되면 카운터가 자동 초기화되어 슬라이딩 윈도우 효과를 낸다.
 *
 * @param requestsPerMinute 분당 최대 요청 수 (기본값: 10)
 * @param requestsPerHour 시간당 최대 요청 수 (기본값: 100)
 * @param tenantRateLimits 테넌트별 차등 속도 제한 설정
 *
 * @see com.arc.reactor.guard.RateLimitStage 인터페이스
 */
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100,
    private val tenantRateLimits: Map<String, TenantRateLimit> = emptyMap()
) : RateLimitStage {

    /**
     * 사용자별 분당/시간당 카운터를 보유하는 데이터 클래스.
     * synchronized 블록으로 두 카운터의 원자적 조작을 보장한다.
     */
    private data class RateCounters(
        val minute: AtomicInteger = AtomicInteger(0),
        val hour: AtomicInteger = AtomicInteger(0)
    )

    // 분당 윈도우: 1분 후 엔트리 만료 (카운터 자동 초기화)
    private val minuteCache: Cache<String, RateCounters> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build()

    // 시간당 윈도우: 1시간 후 엔트리 만료 (카운터 자동 초기화)
    private val hourCache: Cache<String, RateCounters> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build()

    override suspend fun check(command: GuardCommand): GuardResult {
        // ── 단계 1: 캐시 키 및 제한값 결정 ──
        val tenantId = command.metadata["tenantId"]?.toString()
        val userId = command.userId
        // 테넌트가 있으면 "tenantId:userId" 형태로 키를 구성하여 테넌트 간 격리
        val cacheKey = if (tenantId != null) "$tenantId:$userId" else userId

        // 테넌트별 제한 → 전역 기본값 순서로 폴백
        val tenantLimit = if (tenantId != null) tenantRateLimits[tenantId] else null
        val perMinute = tenantLimit?.perMinute ?: requestsPerMinute
        val perHour = tenantLimit?.perHour ?: requestsPerHour

        // ── 단계 2: 각 윈도우의 카운터 객체 조회 (없으면 생성) ──
        val minuteCounters = minuteCache.get(cacheKey) { RateCounters() }
        val hourCounters = hourCache.get(cacheKey) { RateCounters() }

        // ── 단계 3: 원자적으로 카운터 증가 및 제한 확인 ──
        // 두 카운터를 synchronized로 묶어 TOCTOU 경쟁 조건을 방지한다.
        // 제한 초과 시 롤백(decrement)하여 카운터 정합성을 유지한다.
        synchronized(minuteCounters) {
            synchronized(hourCounters) {
                val m = minuteCounters.minute.incrementAndGet()
                val h = hourCounters.hour.incrementAndGet()

                if (m > perMinute) {
                    // 롤백: 제한 초과한 요청은 카운트하지 않음
                    minuteCounters.minute.decrementAndGet()
                    hourCounters.hour.decrementAndGet()
                    return GuardResult.Rejected(
                        reason = "Rate limit exceeded: $perMinute requests per minute",
                        category = RejectionCategory.RATE_LIMITED
                    )
                }

                if (h > perHour) {
                    // 롤백: 제한 초과한 요청은 카운트하지 않음
                    minuteCounters.minute.decrementAndGet()
                    hourCounters.hour.decrementAndGet()
                    return GuardResult.Rejected(
                        reason = "Rate limit exceeded: $perHour requests per hour",
                        category = RejectionCategory.RATE_LIMITED
                    )
                }
            }
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 입력 검증 구현체 (2단계)
 *
 * 입력 텍스트의 길이와 시스템 프롬프트 길이를 검증한다.
 * 너무 짧거나 긴 입력을 조기에 차단하여 후속 단계의 불필요한 처리를 방지한다.
 *
 * @param maxLength 입력 최대 길이 (기본값: 10000자)
 * @param minLength 입력 최소 길이 (기본값: 1자)
 * @param systemPromptMaxChars 시스템 프롬프트 최대 길이 (0이면 검사 안 함)
 *
 * @see com.arc.reactor.guard.InputValidationStage 인터페이스
 */
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1,
    private val systemPromptMaxChars: Int = 0
) : InputValidationStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text.trim()

        // ── 최소 길이 검증 ──
        if (text.length < minLength) {
            val reason = formatBoundaryRuleViolation("input.min_chars", text.length, minLength)
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        // ── 최대 길이 검증 ──
        if (text.length > maxLength) {
            val reason = formatBoundaryRuleViolation("input.max_chars", text.length, maxLength)
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        // ── 시스템 프롬프트 길이 검증 ──
        // systemPromptMaxChars > 0일 때만 검사. 사용자가 제공한 시스템 프롬프트가
        // 과도하게 길면 토큰 낭비와 컨텍스트 오버플로우를 방지하기 위해 차단
        if (systemPromptMaxChars > 0 && command.systemPrompt != null &&
            command.systemPrompt.length > systemPromptMaxChars
        ) {
            val reason = formatBoundaryRuleViolation(
                "system_prompt.max_chars",
                command.systemPrompt.length,
                systemPromptMaxChars
            )
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 Prompt Injection 탐지 구현체 (3단계)
 *
 * 규칙 기반(정규식) 탐지를 수행한다. LLM 기반 탐지로 확장 가능하다.
 *
 * ## 탐지 패턴 카테고리
 * - **공유 패턴** ([InjectionPatterns.SHARED]): 입력/출력 공통 — 역할 재정의, 시스템 구분자, 프롬프트 재정의
 * - **입력 전용 패턴**: 역할 변경, 시스템 프롬프트 추출, 출력 조작, 인코딩 우회,
 *   구분자 주입, 모델 형식 주입, 권한 상승, 안전장치 무력화, 다중 예시 탈옥 등
 *
 * 왜 정규식인가: 속도가 빠르고 LLM 호출 비용이 없다. 정교한 탐지가 필요하면
 * [LlmClassificationStage]를 추가로 활성화할 수 있다.
 *
 * @see com.arc.reactor.guard.InjectionDetectionStage 인터페이스
 * @see com.arc.reactor.guard.InjectionPatterns 공유 패턴
 */
class DefaultInjectionDetectionStage : InjectionDetectionStage {

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text

        // 모든 패턴을 순회하며 매칭 여부 확인
        for (pattern in SUSPICIOUS_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                logger.warn { "Injection pattern detected: ${pattern.pattern}" }
                return GuardResult.Rejected(
                    reason = "Suspicious pattern detected",
                    category = RejectionCategory.PROMPT_INJECTION
                )
            }
        }

        return GuardResult.Allowed.DEFAULT
    }

    companion object {
        /**
         * Injection 탐지 패턴 목록.
         * 공유 패턴 + 입력 전용 패턴으로 구성된다.
         * companion object에 정의하여 hot path에서의 재컴파일을 방지한다.
         */
        private val SUSPICIOUS_PATTERNS: List<Regex> =
            InjectionPatterns.SHARED.map { it.regex } + listOf(
                // ── 역할 변경 (입력 전용) ──
                // 공격자가 LLM에게 다른 역할을 맡으라고 지시하는 패턴
                Regex("(?i)pretend (to be|you're|you are)"),
                Regex("(?i)new (role|persona|character|identity)"),

                // ── 시스템 프롬프트 추출 ──
                // 공격자가 시스템 프롬프트 내용을 노출시키려는 패턴
                Regex("(?i)(show|reveal|print|display|output).*(system|initial|original).*(prompt|instruction)"),
                Regex("(?i)what (are|were) your (instructions|rules)"),

                // ── 출력 조작 ──
                // 공격자가 LLM의 출력 형태를 강제하려는 패턴
                Regex("(?i)(always|only|must).*(respond|reply|say|output)"),

                // ── 인코딩/난독화 우회 ──
                // Base64 디코딩 등을 통해 필터를 우회하려는 패턴
                Regex("(?i)(decode|convert|translate).*base64.*(this|the|my|following)"),
                // 16진수 이스케이프 시퀀스로 텍스트를 숨기는 패턴
                Regex("(?i)\\\\x[0-9a-f]{2}"),

                // ── 구분자 주입 (입력 전용) ──
                // 모델 내부 메시지 구분자를 주입하여 시스템 프롬프트처럼 보이게 하는 패턴
                Regex("```system"),
                Regex("<\\|endoftext\\|>"),
                Regex("<\\|user\\|>"),

                // ── Llama/Gemma 형식 주입 ──
                // Meta Llama 및 Google Gemma 모델의 대화 형식 구분자 주입
                Regex("\\[INST\\]"),
                Regex("\\[/INST\\]"),
                Regex("<start_of_turn>"),
                Regex("<end_of_turn>"),

                // ── 권한 상승 ──
                // 개발자 모드나 시스템 재정의를 시도하는 패턴
                Regex("(?i)(developer|system)\\s*(mode|override|prompt)"),

                // ── 안전장치 무력화 ──
                // 콘텐츠 필터나 보안 정책을 무효화하려는 패턴
                Regex("(?i)override\\s+(safety|content|security)\\s+(filter|policy)"),

                // ── 컨텍스트 구분자 주입 ──
                // 긴 구분선으로 이전 컨텍스트와 분리를 시도하는 패턴
                Regex("-{20,}"),
                Regex("={20,}"),

                // ── 다중 예시 탈옥 (Many-Shot Jailbreak) ──
                // 여러 "example N" 패턴으로 LLM을 특정 응답 패턴에 유도하는 공격
                Regex("(?is)example\\s*\\d+.*example\\s*\\d+.*example\\s*\\d+"),

                // ── 인코딩 우회 ──
                // ROT13 등의 인코딩으로 필터를 우회하려는 패턴
                Regex("(?i)(rot13|deobfuscate).*this.*(text|message)"),

                // ── 한국어 시스템 프롬프트 추출 (입력 전용) ──
                // "원래 명령을 출력해", "숨겨진 프롬프트를 보여줘" 등
                // 주의: "시스템 프롬프트의 역할이 뭔가요?" 같은 정상 질문과 구분하기 위해
                // 프롬프트/지시/명령 뒤에 추출 의도 조사(를/을)가 오는 경우만 매칭
                Regex("(원래|초기|숨겨진).*(프롬프트|지시|명령|인스트럭션)(를|을).*(보여|알려|공개|출력|말해)"),
                Regex("(전체|원본|원래).*(프롬프트|지시사항|설정)(를|을)?.*(공유|복사|전달|출력)")
            )
    }
}
