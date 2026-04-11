package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.summarize.DefaultToolResponseSummarizer
import com.arc.reactor.tool.summarize.NoOpToolResponseSummarizer
import com.arc.reactor.tool.summarize.SummaryKind
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizerHook
import com.arc.reactor.tool.summarize.ToolResponseSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * 사용자 정의 [ToolResponseSummarizer] 구현체 — `DefaultToolResponseSummarizer`가 아님.
 *
 * 클래스 외부에 정의: Kotlin은 inner class 안에서 일반 class 선언을 허용하지 않으며,
 * Spring `@Configuration` 클래스도 top-level이거나 static nested여야 한다.
 */
private class CustomSummarizer : ToolResponseSummarizer {
    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? = ToolResponseSummary(
        text = "custom: ${rawPayload.take(20)}",
        kind = SummaryKind.TEXT_FULL,
        originalLength = rawPayload.length
    )
}

@Configuration
private class CustomBeanConfig {
    @Bean
    fun userCustomSummarizer(): ToolResponseSummarizer = CustomSummarizer()
}

@Configuration
private class PrimaryCustomBeanConfig {
    @Bean
    @Primary
    fun userCustomSummarizer(): ToolResponseSummarizer = CustomSummarizer()
}

@Configuration
private class UserDefaultBeanConfig {
    @Bean
    fun userDefaultSummarizer(): DefaultToolResponseSummarizer = DefaultToolResponseSummarizer()
}

@Configuration
private class CustomHookConfig {
    @Bean
    fun userHook(): ToolResponseSummarizerHook =
        ToolResponseSummarizerHook(NoOpToolResponseSummarizer)
}

/**
 * R267: [ToolResponseSummarizerConfiguration] R267 KDoc 활성화 매트릭스 검증.
 *
 * R232 `@Primary` 충돌 사례에서 발견한 미묘한 동작들 — 특히
 * `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)`이
 * **인터페이스가 아닌 구체 클래스를 검사**한다는 점 — 을 잠근다.
 *
 * R263(EvalMetrics) → R266(SemanticCache) → R267(Summarizer) 패턴의 세 번째 적용.
 *
 * ## 검증 매트릭스 매핑
 *
 * R267 KDoc 매트릭스 6행 ↔ 본 테스트의 nested 클래스:
 *
 * | KDoc 행 | 시나리오 | nested 클래스 |
 * |---|---|---|
 * | 1행 | enabled 미설정 + 사용자 빈 없음 | [DefaultDeactivated] |
 * | 4행 | enabled=true + 사용자 빈 없음 | [DefaultActivation] |
 * | 6행 | enabled=true + 사용자 다른 구현체 | [UserCustomConflictRisk] |
 * | 5행 | enabled=true + 사용자 Default 인스턴스 | [UserDefaultInstance] |
 * | Hook 동작 | summarizerHook 등록 조건 | [HookRegistration] |
 */
class ToolResponseSummarizerConfigurationTest {

    private val baseContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ToolResponseSummarizerConfiguration::class.java)
        )

    @Nested
    inner class DefaultDeactivated {

        @Test
        fun `R267 매트릭스 1행 - enabled 미설정 시 NoOp이 fallback으로 등록`() {
            baseContextRunner.run { context ->
                val summarizer = context.getBean(ToolResponseSummarizer::class.java)
                assertEquals(NoOpToolResponseSummarizer, summarizer) {
                    "enabled 프로퍼티 없음 → noOpToolResponseSummarizer fallback. " +
                        "actual=${summarizer::class.java.simpleName}"
                }

                // Hook은 등록되지 않아야 한다
                assertTrue(
                    context.getBeansOfType(ToolResponseSummarizerHook::class.java).isEmpty()
                ) { "enabled 미설정 → Hook 미등록" }

                // Default도 등록되지 않아야 한다
                assertTrue(
                    context.getBeansOfType(DefaultToolResponseSummarizer::class.java).isEmpty()
                ) { "enabled 미설정 → Default 미등록" }
            }
        }

        @Test
        fun `R267 매트릭스 1행 - enabled false 명시 시 NoOp 등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=false")
                .run { context ->
                    val summarizer = context.getBean(ToolResponseSummarizer::class.java)
                    assertEquals(NoOpToolResponseSummarizer, summarizer) {
                        "enabled=false → NoOp"
                    }
                }
        }
    }

    @Nested
    inner class DefaultActivation {

        @Test
        fun `R267 매트릭스 4행 - enabled true + 사용자 빈 없음 → DefaultToolResponseSummarizer 등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val summarizer = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(DefaultToolResponseSummarizer::class.java, summarizer) {
                        "enabled=true → DefaultToolResponseSummarizer. " +
                            "actual=${summarizer::class.java.simpleName}"
                    }

                    // NoOp은 등록되지 않아야 한다 (Default가 인터페이스 매칭하므로)
                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(1, all.size) {
                        "Default가 등록되었으므로 NoOp은 conditional로 차단되어 단일 빈"
                    }
                }
        }

        @Test
        fun `R267 매트릭스 4행 - enabled true 시 Hook도 자동 등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val hook = context.getBean(ToolResponseSummarizerHook::class.java)
                    assertEquals(ToolResponseSummarizerHook::class.java, hook::class.java) {
                        "enabled=true → Hook 등록"
                    }
                }
        }
    }

    /**
     * R267 KDoc 매트릭스 6행: 가장 미묘한 silent 동작.
     *
     * 사용자가 `DefaultToolResponseSummarizer`가 **아닌** 다른 [ToolResponseSummarizer] 구현체를
     * 등록하면, `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)` 조건이
     * 만족되어 `defaultToolResponseSummarizer()` 빈도 함께 등록된다. 결과는 다중 빈 후보.
     */
    @Nested
    inner class UserCustomConflictRisk {

        @Test
        fun `R268 fix 검증 - 사용자 비-Default 구현체 + enabled true 시 사용자 빈만 등록되고 컨텍스트 정상 기동`() {
            // R267에서는 이 시나리오가 NoUniqueBeanDefinitionException으로 컨텍스트 기동 실패였다.
            // R268에서 @ConditionalOnMissingBean을 인터페이스로 변경하여 사용자 빈이 있으면
            // Configuration default 빈을 등록하지 않도록 fix.
            baseContextRunner
                .withUserConfiguration(CustomBeanConfig::class.java)
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    assertTrue(context.startupFailure == null) {
                        "R268 fix: 사용자 비-Default 구현체 + enabled=true → 컨텍스트 정상 기동. " +
                            "startupFailure=${context.startupFailure?.message}"
                    }

                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(1, all.size) {
                        "R268 fix: 사용자 빈만 등록 (Configuration default 빈은 미등록). " +
                            "actual=${all.keys}"
                    }
                    assertTrue(
                        all.values.first() is CustomSummarizer
                    ) { "R268 fix: 단일 빈은 사용자 구현체" }
                    assertFalse(
                        all.values.any { it is DefaultToolResponseSummarizer }
                    ) { "R268 fix: Configuration default 빈은 등록되지 않아야 함" }

                    // Hook도 정상 등록되며 사용자 빈을 주입받는다
                    val hook = context.getBean(ToolResponseSummarizerHook::class.java)
                    assertEquals(ToolResponseSummarizerHook::class.java, hook::class.java) {
                        "R268 fix: Hook도 정상 등록 (다중 빈 후보 없음)"
                    }
                }
        }

        @Test
        fun `R268 fix - 사용자 빈에 @Primary가 있어도 Configuration default 빈은 미등록 (단일 빈)`() {
            // R267 이전: 사용자 @Primary + Default 2개 빈 공존, @Primary가 conflict 해소
            // R268 fix: 인터페이스 수준 @ConditionalOnMissingBean으로 사용자 빈만 단독 등록
            //   → @Primary 여부와 무관하게 항상 단일 빈
            baseContextRunner
                .withUserConfiguration(PrimaryCustomBeanConfig::class.java)
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val hook = context.getBean(ToolResponseSummarizerHook::class.java)
                    assertEquals(ToolResponseSummarizerHook::class.java, hook::class.java) {
                        "@Primary 사용자 빈 + Hook 모두 등록 가능"
                    }

                    // R268 이후: 단일 빈 (Configuration default 미등록)
                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(1, all.size) {
                        "R268 fix: 사용자 빈만 단독 등록, @Primary 여부와 무관"
                    }
                    assertTrue(
                        all.values.first() is CustomSummarizer
                    ) { "단일 빈은 사용자 @Primary 구현체" }
                }
        }
    }

    @Nested
    inner class UserDefaultInstance {

        /**
         * R267 KDoc 매트릭스 5행: 사용자가 직접 [DefaultToolResponseSummarizer] 인스턴스를
         * 빈으로 제공하면 `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)`이
         * false가 되어 Configuration 빈은 등록되지 않는다.
         *
         * (DefaultToolResponseSummarizer가 final 클래스이므로 서브클래스는 만들 수 없지만
         * 인스턴스를 직접 등록하는 것으로 동일한 효과를 검증.)
         */
        @Test
        fun `R267 매트릭스 5행 - 사용자 Default 인스턴스 시 Configuration 빈 미등록`() {
            baseContextRunner
                .withUserConfiguration(UserDefaultBeanConfig::class.java)
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val defaults = context.getBeansOfType(DefaultToolResponseSummarizer::class.java)
                    assertEquals(1, defaults.size) {
                        "사용자 Default 인스턴스만 등록 (Configuration의 default 빈 미등록). " +
                            "actual=${defaults.keys}"
                    }
                    // 사용자가 등록한 인스턴스인지 확인 (이름으로 식별)
                    assertTrue(
                        defaults.containsKey("userDefaultSummarizer")
                    ) { "사용자 빈 이름으로 등록" }
                    assertFalse(
                        defaults.containsKey("defaultToolResponseSummarizer")
                    ) { "Configuration의 default 빈은 미등록" }
                }
        }
    }

    @Nested
    inner class HookRegistration {

        @Test
        fun `R267 enabled false 시 Hook 미등록`() {
            baseContextRunner.run { context ->
                assertTrue(
                    context.getBeansOfType(ToolResponseSummarizerHook::class.java).isEmpty()
                ) { "enabled 미설정 → Hook 미등록" }
            }
        }

        @Test
        fun `R267 enabled true + 단일 빈 시 Hook 등록`() {
            baseContextRunner
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val hooks = context.getBeansOfType(ToolResponseSummarizerHook::class.java)
                    assertEquals(1, hooks.size) {
                        "enabled=true + 단일 빈 → Hook 1개 등록"
                    }
                }
        }

        @Test
        fun `R267 사용자 Hook 빈이 있으면 Configuration Hook 미등록`() {
            // 사용자가 자체 Hook을 등록하면 @ConditionalOnMissingBean(ToolResponseSummarizerHook::class)로 차단
            baseContextRunner
                .withUserConfiguration(CustomHookConfig::class.java)
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val hooks = context.getBeansOfType(ToolResponseSummarizerHook::class.java)
                    assertEquals(1, hooks.size) {
                        "사용자 Hook 우선 — @ConditionalOnMissingBean으로 Configuration Hook 차단"
                    }
                }
        }
    }

    @Nested
    inner class R268UserCustomWithPiiRedactionCompatibility {

        /**
         * R268 fix가 R232 PII Redaction과 호환되는지 검증.
         *
         * R232는 `@AutoConfiguration(after = [ToolResponseSummarizerConfiguration::class])`로 평가되며
         * `List<ToolResponseSummarizer>` 주입을 통해 첫 non-Redacted 베이스를 wrapping한다.
         *
         * R268 fix 이후 사용자 비-Default 구현체가 등록된 환경에서 R232가 정상 동작하는지 확인 —
         * R268의 호환성 가설 검증.
         */
        @Test
        fun `R268+R232 호환 - 사용자 비-Default 구현체 + PII redaction enabled 시 user bean이 wrap됨`() {
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        ToolResponseSummarizerConfiguration::class.java,
                        ToolResponseSummarizerPiiRedactionConfiguration::class.java
                    )
                )
                .withUserConfiguration(CustomBeanConfig::class.java)
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    assertTrue(context.startupFailure == null) {
                        "R268+R232: 컨텍스트 정상 기동. " +
                            "startupFailure=${context.startupFailure?.message}"
                    }

                    // 2개 빈: 사용자 base + Redacted wrap (R232 @Primary)
                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(2, all.size) {
                        "사용자 base + Redacted wrap = 2개 빈. actual=${all.keys}"
                    }
                    assertTrue(
                        all.values.any { it is CustomSummarizer }
                    ) { "사용자 base 존재" }
                    assertTrue(
                        all.values.any {
                            it::class.java.simpleName == "RedactedToolResponseSummarizer"
                        }
                    ) { "Redacted wrap 존재" }

                    // Configuration default는 등록되지 않음 (R268 fix)
                    assertFalse(
                        all.values.any { it is DefaultToolResponseSummarizer }
                    ) { "R268 fix: Configuration default 빈 미등록" }

                    // Hook 정상 등록 (Redacted @Primary가 단일 후보로 선택됨)
                    val hook = context.getBean(ToolResponseSummarizerHook::class.java)
                    assertEquals(ToolResponseSummarizerHook::class.java, hook::class.java)
                }
        }
    }

    @Nested
    inner class HistoricalContext {

        @Test
        fun `R267 R232 회귀 잠금 - defaultToolResponseSummarizer는 단일 빈으로 주입되어야 한다`() {
            // R232 이전에는 @Primary였으나 PII Redaction과 충돌하여 제거됨
            // 만약 누군가 @Primary를 다시 추가하면 PII Redaction 자동 wrap이 깨진다
            // 이 회귀를 잠그기 위해 enabled=true 시 단일 빈이 등록되는 것을 확인
            baseContextRunner
                .withPropertyValues("arc.reactor.tool.response.summarizer.enabled=true")
                .run { context ->
                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(1, all.size) {
                        "@Primary 없이도 단일 빈 (R232 회귀 잠금: noOp는 @ConditionalOnMissingBean으로 차단)"
                    }
                    assertInstanceOf(DefaultToolResponseSummarizer::class.java, all.values.first())

                    // NoOp이 차단되었는지 확인 (@ConditionalOnMissingBean으로 Default가 우선)
                    assertFalse(
                        all.values.any { it === NoOpToolResponseSummarizer }
                    ) { "Default가 등록되었으므로 NoOp은 차단" }
                }
        }
    }
}
