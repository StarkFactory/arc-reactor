package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.summarize.DefaultToolResponseSummarizer
import com.arc.reactor.tool.summarize.NoOpToolResponseSummarizer
import com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer
import com.arc.reactor.tool.summarize.SummaryKind
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [ToolResponseSummarizerPiiRedactionConfiguration] 자동 구성 테스트.
 *
 * R232: R229 `ApprovalPiiRedactionConfigurationTest`와 평행 패턴.
 * `pii-redaction.enabled=true` 속성 설정 시 베이스 summarizer가
 * [RedactedToolResponseSummarizer]로 자동 래핑되는지 검증한다.
 */
class ToolResponseSummarizerPiiRedactionConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ToolResponseSummarizerConfiguration::class.java,
                ToolResponseSummarizerPiiRedactionConfiguration::class.java
            )
        )

    @Nested
    inner class WithDefaultBase {

        @Test
        fun `summarizer enabled + redaction enabled → Redacted 래핑`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        RedactedToolResponseSummarizer::class.java,
                        primary
                    ) { "Primary 빈은 Redacted여야 한다" }
                }
        }

        @Test
        fun `래핑된 Primary는 실제 PII를 마스킹해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    val result = primary.summarize(
                        "jira_search",
                        """[{"title":"user@company.com 담당 이슈"}]""",
                        true
                    )
                    assertNotNull(result)
                    assertFalse(result!!.text.contains("user@company.com")) {
                        "text의 이메일이 마스킹되어야 한다: ${result.text}"
                    }
                    val primaryKey = result.primaryKey
                    if (primaryKey != null) {
                        assertFalse(primaryKey.contains("user@company.com")) {
                            "primaryKey의 이메일이 마스킹되어야 한다: $primaryKey"
                        }
                    }
                }
        }

        @Test
        fun `두 빈 모두 컨텍스트에 존재해야 한다 (원본 Default + Redacted)`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertEquals(2, all.size) {
                        "원본 Default + Redacted = 2개 빈"
                    }
                    assertTrue(
                        all.values.any { it is DefaultToolResponseSummarizer }
                    ) { "원본 Default 존재" }
                    assertTrue(
                        all.values.any { it is RedactedToolResponseSummarizer }
                    ) { "Redacted 존재" }
                }
        }
    }

    @Nested
    inner class WithoutRedaction {

        @Test
        fun `summarizer enabled만 설정 → 원본 Default 그대로`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        DefaultToolResponseSummarizer::class.java,
                        primary
                    ) { "Redaction 미설정 시 원본 Default" }
                }
        }

        @Test
        fun `pii-redaction=false → 원본 유지`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.enabled=true",
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=false"
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        DefaultToolResponseSummarizer::class.java,
                        primary
                    ) { "pii-redaction=false는 원본 유지" }
                }
        }
    }

    @Nested
    inner class WithoutBaseSummarizer {

        @Test
        fun `summarizer enabled 없이 redaction만 true → NoOp 빈이 래핑됨`() {
            // ToolResponseSummarizerConfiguration이 NoOp을 기본 등록하므로
            // summarizer.enabled가 false/미설정이어도 NoOp 빈이 있을 수 있다.
            // 이 경우 redaction이 NoOp을 래핑한다.
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        RedactedToolResponseSummarizer::class.java,
                        primary
                    ) { "NoOp 베이스도 래핑 대상" }
                }
        }

        @Test
        fun `아무 속성도 없어도 NoOp은 기본 등록됨`() {
            contextRunner.run { context ->
                val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                assertEquals(1, all.size) {
                    "NoOp 기본 등록 → 빈 1개"
                }
                assertTrue(
                    all.values.first() === NoOpToolResponseSummarizer
                ) { "NoOp 싱글톤" }
            }
        }
    }

    @Nested
    inner class WithUserCustomBean {

        @Test
        fun `사용자 커스텀 summarizer + pii-redaction=true → 커스텀이 래핑됨`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .withUserConfiguration(CustomSummarizerConfig::class.java)
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        RedactedToolResponseSummarizer::class.java,
                        primary
                    ) { "Primary는 Redacted" }

                    // 실제로 커스텀 summarizer의 이메일이 마스킹되는지 검증
                    val result = primary.summarize("any_tool", "any_payload", true)
                    assertNotNull(result)
                    assertFalse(result!!.text.contains("sensitive@example.com")) {
                        "커스텀 summarizer의 text에 이메일이 남아있으면 안 된다"
                    }
                }
        }

        @Test
        fun `사용자 커스텀 summarizer 단독 (redaction 미설정)`() {
            contextRunner
                .withUserConfiguration(CustomSummarizerConfig::class.java)
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        StubCustomSummarizer::class.java,
                        primary
                    ) { "원본 커스텀 빈이 Primary" }
                }
        }
    }

    @Nested
    inner class SelfWrappingPrevention {

        @Test
        fun `이미 Redacted 빈만 등록된 상태 + redaction=true → startup 실패`() {
            // 이 시나리오: 사용자가 @Bean UserRedactedBean을 등록 → ToolResponseSummarizerConfiguration의
            // noOpToolResponseSummarizer는 @ConditionalOnMissingBean으로 등록되지 않음
            // → 컨텍스트에 ToolResponseSummarizer 타입 빈은 UserRedacted(RedactedToolResponseSummarizer) 1개뿐
            // → R232의 redactedToolResponseSummarizer가 non-redacted 베이스를 찾지 못함
            // → IllegalStateException 발생 → startup 실패
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .withUserConfiguration(UserRedactedBeanConfig::class.java)
                .run { context ->
                    val startupFailure = context.startupFailure
                    assertNotNull(startupFailure) {
                        "non-redacted 베이스가 없으면 startup 실패해야 한다"
                    }
                    val message = collectCauseMessages(startupFailure!!)
                    assertTrue(
                        message.contains("non-redacted") || message.contains("베이스")
                    ) { "에러 메시지가 명확해야 한다: $message" }
                }
        }

        @Test
        fun `UserRedacted + CustomBase + redaction=true → CustomBase가 베이스로 선택되어 정상 동작`() {
            // 이 시나리오: 사용자가 두 개 빈 등록 — UserRedacted + StubCustomSummarizer
            // → non-redacted는 StubCustomSummarizer이므로 R232가 이를 베이스로 선택
            // → startup 정상, Primary는 Redacted(StubCustomSummarizer)
            contextRunner
                .withPropertyValues(
                    "arc.reactor.tool.response.summarizer.pii-redaction.enabled=true"
                )
                .withUserConfiguration(
                    UserRedactedBeanConfig::class.java,
                    CustomSummarizerConfig::class.java
                )
                .run { context ->
                    val primary = context.getBean(ToolResponseSummarizer::class.java)
                    assertInstanceOf(
                        RedactedToolResponseSummarizer::class.java,
                        primary
                    ) { "Primary는 Redacted여야 한다" }

                    val all = context.getBeansOfType(ToolResponseSummarizer::class.java)
                    assertTrue(all.size >= 3) {
                        "StubCustom + UserRedacted + auto Redacted = 3개 이상: size=${all.size}"
                    }
                }
        }
    }

    /** 예외 체인 전체의 메시지를 수집. */
    private fun collectCauseMessages(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 20) {
            cur.message?.let { sb.append(it).append(" | ") }
            if (cur.cause === cur) break
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    /** 테스트용 커스텀 summarizer (이메일 포함 결과 반환). */
    class StubCustomSummarizer : ToolResponseSummarizer {
        override fun summarize(
            toolName: String,
            rawPayload: String,
            success: Boolean
        ): ToolResponseSummary = ToolResponseSummary(
            text = "custom summary with sensitive@example.com",
            kind = SummaryKind.TEXT_FULL,
            originalLength = 50,
            primaryKey = "sensitive@example.com"
        )
    }

    @Configuration
    class CustomSummarizerConfig {
        @Bean
        fun customSummarizer(): ToolResponseSummarizer = StubCustomSummarizer()
    }

    @Configuration
    class UserRedactedBeanConfig {
        @Bean
        fun userRedacted(): ToolResponseSummarizer =
            RedactedToolResponseSummarizer(StubCustomSummarizer())
    }
}
