package com.arc.reactor.memory.summary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * ConversationSummaryModels의 단위 테스트.
 *
 * [StructuredFact], [FactCategory], [ConversationSummary], [SummarizationResult]
 * 의 불변 조건과 기본값을 검증한다.
 */
class ConversationSummaryModelsTest {

    @Nested
    inner class StructuredFactDefaults {

        @Test
        fun `기본 카테고리는 GENERAL이다`() {
            val fact = StructuredFact(key = "order_number", value = "#1234")

            assertEquals(
                FactCategory.GENERAL,
                fact.category,
                "카테고리를 명시하지 않으면 GENERAL이 기본값이어야 한다"
            )
        }

        @Test
        fun `extractedAt은 생성 시각으로 자동 설정된다`() {
            val before = Instant.now()
            val fact = StructuredFact(key = "k", value = "v")
            val after = Instant.now()

            assertNotNull(fact.extractedAt, "extractedAt은 null이 아니어야 한다")
            assertTrue(
                !fact.extractedAt.isBefore(before) && !fact.extractedAt.isAfter(after),
                "extractedAt은 생성 전후 사이의 시각이어야 한다: ${fact.extractedAt}"
            )
        }

        @Test
        fun `명시된 카테고리가 보존된다`() {
            val categories = FactCategory.entries.toList()

            categories.forEach { category ->
                val fact = StructuredFact(key = "k", value = "v", category = category)
                assertEquals(
                    category,
                    fact.category,
                    "카테고리 $category 가 저장 후 그대로 반환되어야 한다"
                )
            }
        }

        @Test
        fun `key와 value가 정확히 저장된다`() {
            val fact = StructuredFact(key = "agreed_price", value = "50,000 KRW")

            assertEquals("agreed_price", fact.key, "key가 일치해야 한다")
            assertEquals("50,000 KRW", fact.value, "value가 일치해야 한다")
        }

        @Test
        fun `데이터 클래스 동등성은 모든 필드를 비교한다`() {
            val instant = Instant.parse("2025-01-01T00:00:00Z")
            val a = StructuredFact(key = "k", value = "v", category = FactCategory.ENTITY, extractedAt = instant)
            val b = StructuredFact(key = "k", value = "v", category = FactCategory.ENTITY, extractedAt = instant)
            val c = StructuredFact(key = "k", value = "different", category = FactCategory.ENTITY, extractedAt = instant)

            assertEquals(a, b, "동일 필드를 가진 두 StructuredFact는 동등해야 한다")
            assertNotEquals(a, c, "value가 다른 StructuredFact는 동등하지 않아야 한다")
        }

        @Test
        fun `copy로 일부 필드만 변경할 수 있다`() {
            val original = StructuredFact(key = "k", value = "v", category = FactCategory.NUMERIC)
            val updated = original.copy(value = "updated-v")

            assertEquals("k", updated.key, "copy 후 key는 변경되지 않아야 한다")
            assertEquals("updated-v", updated.value, "copy로 변경된 value가 반영되어야 한다")
            assertEquals(FactCategory.NUMERIC, updated.category, "copy 후 category는 변경되지 않아야 한다")
        }
    }

    @Nested
    inner class FactCategoryEnum {

        @Test
        fun `모든 카테고리 열거값이 존재한다`() {
            val expected = setOf("ENTITY", "DECISION", "CONDITION", "STATE", "NUMERIC", "GENERAL")
            val actual = FactCategory.entries.map { it.name }.toSet()

            assertEquals(
                expected,
                actual,
                "FactCategory는 정확히 6개의 값(ENTITY, DECISION, CONDITION, STATE, NUMERIC, GENERAL)을 가져야 한다"
            )
        }

        @Test
        fun `valueOf로 이름에서 카테고리를 조회할 수 있다`() {
            assertEquals(
                FactCategory.ENTITY,
                FactCategory.valueOf("ENTITY"),
                "valueOf('ENTITY')는 FactCategory.ENTITY를 반환해야 한다"
            )
            assertEquals(
                FactCategory.DECISION,
                FactCategory.valueOf("DECISION"),
                "valueOf('DECISION')는 FactCategory.DECISION을 반환해야 한다"
            )
            assertEquals(
                FactCategory.GENERAL,
                FactCategory.valueOf("GENERAL"),
                "valueOf('GENERAL')는 FactCategory.GENERAL을 반환해야 한다"
            )
        }

        @Test
        fun `ordinal 순서가 정의된 선언 순서와 일치한다`() {
            assertEquals(0, FactCategory.ENTITY.ordinal, "ENTITY는 첫 번째 열거값이어야 한다")
            assertEquals(5, FactCategory.GENERAL.ordinal, "GENERAL는 마지막(6번째) 열거값이어야 한다")
        }
    }

    @Nested
    inner class ConversationSummaryDefaults {

        @Test
        fun `createdAt과 updatedAt은 생성 시각으로 자동 설정된다`() {
            val before = Instant.now()
            val summary = ConversationSummary(
                sessionId = "s1",
                narrative = "테스트 요약",
                facts = emptyList(),
                summarizedUpToIndex = 5
            )
            val after = Instant.now()

            assertTrue(
                !summary.createdAt.isBefore(before) && !summary.createdAt.isAfter(after),
                "createdAt은 생성 전후 사이이어야 한다: ${summary.createdAt}"
            )
            assertTrue(
                !summary.updatedAt.isBefore(before) && !summary.updatedAt.isAfter(after),
                "updatedAt은 생성 전후 사이이어야 한다: ${summary.updatedAt}"
            )
        }

        @Test
        fun `모든 필드가 정확히 저장된다`() {
            val instant = Instant.parse("2025-06-01T12:00:00Z")
            val facts = listOf(
                StructuredFact(key = "order_number", value = "#999", category = FactCategory.ENTITY)
            )
            val summary = ConversationSummary(
                sessionId = "session-abc",
                narrative = "사용자가 환불을 요청했다",
                facts = facts,
                summarizedUpToIndex = 20,
                createdAt = instant,
                updatedAt = instant
            )

            assertEquals("session-abc", summary.sessionId, "sessionId가 일치해야 한다")
            assertEquals("사용자가 환불을 요청했다", summary.narrative, "narrative가 일치해야 한다")
            assertEquals(1, summary.facts.size, "facts 목록 크기가 일치해야 한다")
            assertEquals("#999", summary.facts[0].value, "facts 내 값이 일치해야 한다")
            assertEquals(20, summary.summarizedUpToIndex, "summarizedUpToIndex가 일치해야 한다")
            assertEquals(instant, summary.createdAt, "createdAt이 일치해야 한다")
            assertEquals(instant, summary.updatedAt, "updatedAt이 일치해야 한다")
        }

        @Test
        fun `빈 facts 목록을 허용한다`() {
            val summary = ConversationSummary(
                sessionId = "s2",
                narrative = "짧은 대화",
                facts = emptyList(),
                summarizedUpToIndex = 2
            )

            assertTrue(summary.facts.isEmpty(), "facts가 빈 목록이어야 한다")
        }

        @Test
        fun `summarizedUpToIndex가 0을 허용한다`() {
            val summary = ConversationSummary(
                sessionId = "s3",
                narrative = "",
                facts = emptyList(),
                summarizedUpToIndex = 0
            )

            assertEquals(0, summary.summarizedUpToIndex, "summarizedUpToIndex가 0이어야 한다")
        }

        @Test
        fun `copy로 updatedAt만 변경한 갱신 패턴이 동작한다`() {
            val original = ConversationSummary(
                sessionId = "s4",
                narrative = "원본 요약",
                facts = emptyList(),
                summarizedUpToIndex = 5
            )
            val newInstant = Instant.parse("2026-01-01T00:00:00Z")
            val updated = original.copy(narrative = "갱신된 요약", updatedAt = newInstant)

            assertEquals("s4", updated.sessionId, "copy 후 sessionId는 유지되어야 한다")
            assertEquals("갱신된 요약", updated.narrative, "copy로 변경된 narrative가 반영되어야 한다")
            assertEquals(newInstant, updated.updatedAt, "copy로 변경된 updatedAt이 반영되어야 한다")
            assertEquals(original.createdAt, updated.createdAt, "createdAt은 copy 후에도 변경되지 않아야 한다")
        }

        @Test
        fun `동일 필드의 두 인스턴스가 동등하다`() {
            val instant = Instant.parse("2025-01-01T00:00:00Z")
            val a = ConversationSummary("s1", "narrative", emptyList(), 5, instant, instant)
            val b = ConversationSummary("s1", "narrative", emptyList(), 5, instant, instant)

            assertEquals(a, b, "동일 필드를 가진 두 ConversationSummary는 동등해야 한다")
        }
    }

    @Nested
    inner class SummarizationResultDefaults {

        @Test
        fun `tokenCost의 기본값은 0이다`() {
            val result = SummarizationResult(narrative = "요약", facts = emptyList())

            assertEquals(0, result.tokenCost, "tokenCost를 명시하지 않으면 기본값 0이어야 한다")
        }

        @Test
        fun `narrative와 facts가 정확히 저장된다`() {
            val facts = listOf(
                StructuredFact(key = "price", value = "10000", category = FactCategory.NUMERIC)
            )
            val result = SummarizationResult(narrative = "가격 협의 완료", facts = facts, tokenCost = 150)

            assertEquals("가격 협의 완료", result.narrative, "narrative가 일치해야 한다")
            assertEquals(1, result.facts.size, "facts 크기가 일치해야 한다")
            assertEquals("10000", result.facts[0].value, "facts 내 value가 일치해야 한다")
            assertEquals(150, result.tokenCost, "tokenCost가 일치해야 한다")
        }

        @Test
        fun `빈 facts 목록을 허용한다`() {
            val result = SummarizationResult(narrative = "요약 없음", facts = emptyList())

            assertTrue(result.facts.isEmpty(), "facts가 빈 목록이어야 한다")
        }

        @Test
        fun `데이터 클래스 동등성이 올바르게 동작한다`() {
            val facts = listOf(StructuredFact(key = "k", value = "v", extractedAt = Instant.EPOCH))
            val a = SummarizationResult(narrative = "n", facts = facts, tokenCost = 100)
            val b = SummarizationResult(narrative = "n", facts = facts, tokenCost = 100)
            val c = SummarizationResult(narrative = "n", facts = facts, tokenCost = 999)

            assertEquals(a, b, "동일 필드를 가진 두 SummarizationResult는 동등해야 한다")
            assertNotEquals(a, c, "tokenCost가 다른 SummarizationResult는 동등하지 않아야 한다")
        }

        @Test
        fun `copy로 tokenCost만 변경할 수 있다`() {
            val original = SummarizationResult(narrative = "요약", facts = emptyList(), tokenCost = 50)
            val updated = original.copy(tokenCost = 200)

            assertEquals("요약", updated.narrative, "copy 후 narrative는 변경되지 않아야 한다")
            assertEquals(200, updated.tokenCost, "copy로 변경된 tokenCost가 반영되어야 한다")
        }

        @Test
        fun `복수의 구조화 팩트를 포함할 수 있다`() {
            val facts = listOf(
                StructuredFact(key = "entity", value = "홍길동", category = FactCategory.ENTITY),
                StructuredFact(key = "decision", value = "환불 승인", category = FactCategory.DECISION),
                StructuredFact(key = "amount", value = "30000", category = FactCategory.NUMERIC)
            )
            val result = SummarizationResult(narrative = "환불 처리 완료", facts = facts)

            assertEquals(3, result.facts.size, "3개의 팩트가 모두 포함되어야 한다")
            assertEquals(FactCategory.ENTITY, result.facts[0].category, "첫 번째 팩트 카테고리가 ENTITY이어야 한다")
            assertEquals(FactCategory.DECISION, result.facts[1].category, "두 번째 팩트 카테고리가 DECISION이어야 한다")
            assertEquals(FactCategory.NUMERIC, result.facts[2].category, "세 번째 팩트 카테고리가 NUMERIC이어야 한다")
        }
    }
}
