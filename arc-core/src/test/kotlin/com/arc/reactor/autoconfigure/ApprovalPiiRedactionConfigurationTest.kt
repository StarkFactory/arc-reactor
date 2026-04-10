package com.arc.reactor.autoconfigure

import com.arc.reactor.approval.ApprovalContext
import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.AtlassianApprovalContextResolver
import com.arc.reactor.approval.ChainedApprovalContextResolver
import com.arc.reactor.approval.RedactedApprovalContextResolver
import com.arc.reactor.approval.Reversibility
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
 * [ApprovalPiiRedactionConfiguration] 자동 구성 테스트.
 *
 * R229: `pii-redaction.enabled=true` 속성이 설정되면 기존 베이스 리졸버를
 * [RedactedApprovalContextResolver]로 자동 래핑하는 동작을 검증한다.
 */
class ApprovalPiiRedactionConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AtlassianApprovalResolverConfiguration::class.java,
                ApprovalPiiRedactionConfiguration::class.java
            )
        )

    @Nested
    inner class WithAtlassianBase {

        @Test
        fun `pii-redaction enabled이면 Atlassian 리졸버가 Redacted로 래핑되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        RedactedApprovalContextResolver::class.java,
                        primary
                    ) { "Primary 빈은 RedactedApprovalContextResolver여야 한다" }
                }
        }

        @Test
        fun `래핑된 Primary는 PII를 마스킹해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    val result = primary.resolve(
                        "jira_my_open_issues",
                        mapOf("requesterEmail" to "alice@company.com")
                    )
                    assertNotNull(result) { "결과가 반환되어야 한다" }
                    val allText = "${result!!.reason} ${result.action} ${result.impactScope}"
                    assertFalse(allText.contains("alice@company.com")) {
                        "이메일이 마스킹되어야 한다: $allText"
                    }
                }
        }

        @Test
        fun `두 빈 모두 컨텍스트에 존재해야 한다 (원본과 Redacted)`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val all = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertEquals(2, all.size) {
                        "원본 (Atlassian) + Redacted = 2개 빈이 존재해야 한다"
                    }
                    val types = all.values.map { it::class.java.simpleName }
                    assertTrue(
                        types.any { it == "RedactedApprovalContextResolver" }
                    ) { "Redacted가 있어야 한다" }
                    assertTrue(
                        types.any { it == "AtlassianApprovalContextResolver" }
                    ) { "원본 Atlassian도 남아있어야 한다" }
                }
        }
    }

    @Nested
    inner class WithChainedBase {

        @Test
        fun `체인 베이스도 Redacted로 래핑되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        RedactedApprovalContextResolver::class.java,
                        primary
                    ) { "Primary 빈은 Redacted여야 한다" }

                    val all = context.getBeansOfType(ApprovalContextResolver::class.java)
                    // Chain 빈 + Redacted 빈 = 2개
                    assertEquals(2, all.size) {
                        "원본 Chain + Redacted = 2개"
                    }
                    assertTrue(
                        all.values.any { it is ChainedApprovalContextResolver }
                    ) { "원본 Chained가 남아있어야 한다" }
                }
        }

        @Test
        fun `체인 + 마스킹 조합이 Jira 이메일을 마스킹해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    val result = primary.resolve(
                        "jira_search_my_issues_by_text",
                        mapOf(
                            "requesterEmail" to "bob@company.com",
                            "text" to "백로그"
                        )
                    )
                    assertNotNull(result)
                    val allText = "${result!!.reason} ${result.action} ${result.impactScope}"
                    assertFalse(allText.contains("bob@company.com"))
                }
        }
    }

    @Nested
    inner class WithoutRedaction {

        @Test
        fun `pii-redaction 미설정이면 원본 베이스 빈이 그대로 사용되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        AtlassianApprovalContextResolver::class.java,
                        primary
                    ) { "Redaction 미설정 시 원본 Atlassian 리졸버가 그대로" }

                    val all = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertEquals(1, all.size) {
                        "Redacted 빈이 등록되지 않아 빈 개수는 1"
                    }
                }
        }

        @Test
        fun `pii-redaction이 false이면 원본 빈이 유지되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.pii-redaction.enabled=false"
                )
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        AtlassianApprovalContextResolver::class.java,
                        primary
                    ) { "pii-redaction=false는 래핑하지 않음" }
                }
        }
    }

    @Nested
    inner class WithoutBaseResolver {

        @Test
        fun `베이스 빈이 없으면 redaction만으로는 빈이 등록되지 않아야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .run { context ->
                    val all = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertTrue(all.isEmpty()) {
                        "베이스 빈이 없으면 @ConditionalOnBean 실패로 Redacted도 등록 안 됨"
                    }
                }
        }

        @Test
        fun `아무 속성도 없으면 빈이 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                val all = context.getBeansOfType(ApprovalContextResolver::class.java)
                assertTrue(all.isEmpty()) {
                    "모든 속성이 미설정이면 빈 없음"
                }
            }
        }
    }

    @Nested
    inner class WithUserCustomBean {

        @Test
        fun `사용자 커스텀 베이스 + pii-redaction true → 커스텀 빈이 래핑되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .withUserConfiguration(CustomBaseResolverConfig::class.java)
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        RedactedApprovalContextResolver::class.java,
                        primary
                    ) { "Primary는 Redacted" }

                    // 이메일 마스킹 확인 — CustomBaseResolver가 이메일을 포함한 결과 반환
                    val result = primary.resolve("any_tool", emptyMap())
                    assertNotNull(result)
                    assertFalse(
                        result!!.impactScope?.contains("sensitive@example.com") ?: false
                    ) { "커스텀 빈의 이메일도 마스킹되어야 한다" }
                }
        }

        @Test
        fun `사용자 커스텀 빈만 있고 redaction 미설정이면 커스텀 그대로`() {
            contextRunner
                .withUserConfiguration(CustomBaseResolverConfig::class.java)
                .run { context ->
                    val primary = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        StubCustomResolver::class.java,
                        primary
                    ) { "원본 커스텀 빈이 Primary" }
                }
        }
    }

    @Nested
    inner class SelfWrappingPrevention {

        @Test
        fun `이미 Redacted인 베이스 빈이 있다면 이중 래핑하지 않아야 한다`() {
            // 사용자가 직접 RedactedApprovalContextResolver를 @Bean으로 등록한 경우
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.pii-redaction.enabled=true"
                )
                .withUserConfiguration(UserRedactedBeanConfig::class.java)
                .run { context ->
                    // ApprovalPiiRedactionConfiguration이 "non-redacted base"를 못 찾아
                    // IllegalStateException이 발생해야 한다
                    val startup = context.startupFailure
                    assertNotNull(startup) {
                        "자동 래핑 대상 non-redacted 베이스 없으면 startup 실패"
                    }
                    val message = collectCauseMessages(startup!!)
                    assertTrue(
                        message.contains("non-redacted") || message.contains("베이스")
                    ) { "에러 메시지가 명확해야 한다: $message" }
                }
        }
    }

    /** 예외 체인 전체의 메시지를 수집 (null-safe). */
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

    /** 테스트용 커스텀 베이스 리졸버 (이메일 포함 결과 반환). */
    class StubCustomResolver : ApprovalContextResolver {
        override fun resolve(toolName: String, arguments: Map<String, Any?>) =
            ApprovalContext(
                reason = "사용자 정의 리졸버 호출: $toolName",
                action = "call $toolName",
                impactScope = "sensitive@example.com",
                reversibility = Reversibility.REVERSIBLE
            )
    }

    @Configuration
    class CustomBaseResolverConfig {
        @Bean
        fun customBase(): ApprovalContextResolver = StubCustomResolver()
    }

    @Configuration
    class UserRedactedBeanConfig {
        @Bean
        fun userRedacted(): ApprovalContextResolver =
            RedactedApprovalContextResolver(StubCustomResolver())
    }
}
