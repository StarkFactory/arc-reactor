package com.arc.reactor.persona

import com.arc.reactor.prompt.InMemoryPromptTemplateStore
import com.arc.reactor.prompt.PromptTemplate
import com.arc.reactor.prompt.VersionStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [Persona.resolveEffectivePrompt] 확장 함수에 대한 테스트.
 *
 * 대상 시나리오:
 * - promptTemplateId 없음 → systemPrompt 반환
 * - promptTemplateStore null → systemPrompt 폴백
 * - 연결된 템플릿에 활성 버전 있음 → 버전 콘텐츠 반환
 * - 활성 버전 없음(DRAFT만 존재) → systemPrompt 폴백
 * - 활성 버전 콘텐츠가 공백 → systemPrompt 폴백
 * - 템플릿 조회 중 예외 → systemPrompt 폴백 (경고 로깅)
 * - responseGuideline 있음 → 기본 프롬프트 끝에 추가
 * - responseGuideline 공백/null → 추가 없음
 */
class PersonaResolveEffectivePromptTest {

    // ─────────────────────────────────────────────────────────────────────
    // 기본 동작 — promptTemplateId 없음 또는 store null
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class NoTemplateLink {

        @Test
        fun `promptTemplateId가 없으면 systemPrompt를 그대로 반환한다`() {
            val persona = Persona(id = "p1", name = "테스트", systemPrompt = "직접 설정된 프롬프트")

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("직접 설정된 프롬프트", result) {
                "promptTemplateId가 없을 때 resolveEffectivePrompt는 systemPrompt를 반환해야 한다"
            }
        }

        @Test
        fun `promptTemplateStore가 null이면 systemPrompt 폴백이 반환된다`() {
            val persona = Persona(
                id = "p2",
                name = "링크된 페르소나",
                systemPrompt = "폴백 프롬프트",
                promptTemplateId = "template-x"
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("폴백 프롬프트", result) {
                "promptTemplateStore가 null이면 systemPrompt를 반환해야 한다"
            }
        }

        @Test
        fun `promptTemplateId가 빈 문자열이면 systemPrompt를 반환한다`() {
            val persona = Persona(
                id = "p3",
                name = "빈 템플릿 ID",
                systemPrompt = "직접 프롬프트",
                promptTemplateId = ""
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("직접 프롬프트", result) {
                "빈 문자열 promptTemplateId는 null과 동일하게 처리되어야 한다"
            }
        }

        @Test
        fun `promptTemplateId가 공백 문자열이면 systemPrompt를 반환한다`() {
            val persona = Persona(
                id = "p4",
                name = "공백 템플릿 ID",
                systemPrompt = "직접 프롬프트",
                promptTemplateId = "   "
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("직접 프롬프트", result) {
                "공백 문자열 promptTemplateId는 null과 동일하게 처리되어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 활성 버전 조회 성공
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActiveVersionResolution {

        @Test
        fun `활성 버전이 있으면 해당 버전의 콘텐츠를 반환한다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "template-1"))

            val version = store.createVersion(templateId, "버전에서 로드된 프롬프트")!!
            store.activateVersion(templateId, version.id)

            val persona = Persona(
                id = "p5",
                name = "템플릿 연결 페르소나",
                systemPrompt = "폴백 프롬프트",
                promptTemplateId = templateId
            )

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("버전에서 로드된 프롬프트", result) {
                "활성 버전의 콘텐츠가 systemPrompt보다 우선해야 한다"
            }
        }

        @Test
        fun `활성 버전이 systemPrompt와 달라도 활성 버전이 반환된다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "template-2"))

            val v1 = store.createVersion(templateId, "버전 1")!!
            store.activateVersion(templateId, v1.id)
            val v2 = store.createVersion(templateId, "버전 2 — 업데이트됨")!!
            store.activateVersion(templateId, v2.id)

            val persona = Persona(
                id = "p6",
                name = "최신 버전 페르소나",
                systemPrompt = "구버전 폴백",
                promptTemplateId = templateId
            )

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("버전 2 — 업데이트됨", result) {
                "가장 최근에 활성화된 버전의 콘텐츠를 반환해야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 활성 버전 없음 → systemPrompt 폴백
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class FallbackWhenNoActiveVersion {

        @Test
        fun `DRAFT 버전만 있으면 systemPrompt 폴백이 반환된다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "draft-only"))
            // 버전 생성하지만 활성화하지 않음 (DRAFT 상태 유지)
            store.createVersion(templateId, "초안 콘텐츠")

            val persona = Persona(
                id = "p7",
                name = "초안 연결 페르소나",
                systemPrompt = "활성 버전 없을 때 폴백",
                promptTemplateId = templateId
            )

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("활성 버전 없을 때 폴백", result) {
                "DRAFT 버전만 있는 경우 systemPrompt를 반환해야 한다"
            }
        }

        @Test
        fun `활성 버전의 content가 공백이면 systemPrompt 폴백이 반환된다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "blank-content"))

            val version = store.createVersion(templateId, "   ")!! // 공백 콘텐츠
            store.activateVersion(templateId, version.id)

            val persona = Persona(
                id = "p8",
                name = "공백 버전 페르소나",
                systemPrompt = "공백 버전 폴백",
                promptTemplateId = templateId
            )

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("공백 버전 폴백", result) {
                "활성 버전 콘텐츠가 공백이면 systemPrompt를 반환해야 한다"
            }
        }

        @Test
        fun `ARCHIVED 버전만 남아있으면 systemPrompt 폴백이 반환된다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "archived-only"))

            val version = store.createVersion(templateId, "아카이브된 콘텐츠")!!
            store.activateVersion(templateId, version.id)
            store.archiveVersion(version.id)

            // 아카이브 후 활성 버전이 없음을 확인
            val activeVersion = store.getActiveVersion(templateId)
            assertTrue(activeVersion == null || activeVersion.status != VersionStatus.ACTIVE) {
                "아카이브 후 활성 버전이 없어야 한다 (테스트 전제조건)"
            }

            val persona = Persona(
                id = "p9",
                name = "아카이브 후 페르소나",
                systemPrompt = "아카이브 후 폴백",
                promptTemplateId = templateId
            )

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("아카이브 후 폴백", result) {
                "모든 버전이 ARCHIVED 상태이면 systemPrompt를 반환해야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 예외 발생 → systemPrompt 폴백 (경고 로깅, 예외 전파 없음)
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ExceptionHandling {

        @Test
        fun `템플릿 조회 중 예외가 발생해도 systemPrompt를 반환한다`() {
            val faultyStore = mockk<com.arc.reactor.prompt.PromptTemplateStore>()
            every { faultyStore.getActiveVersion(any()) } throws RuntimeException("DB 연결 실패")

            val persona = Persona(
                id = "p10",
                name = "오류 허용 페르소나",
                systemPrompt = "예외 발생 시 폴백",
                promptTemplateId = "template-fail"
            )

            val result = persona.resolveEffectivePrompt(faultyStore)

            assertEquals("예외 발생 시 폴백", result) {
                "templateStore 조회 실패 시 예외를 전파하지 않고 systemPrompt를 반환해야 한다"
            }
        }

        @Test
        fun `템플릿 조회 중 예외가 호출자에게 전파되지 않는다`() {
            val faultyStore = mockk<com.arc.reactor.prompt.PromptTemplateStore>()
            every { faultyStore.getActiveVersion(any()) } throws IllegalStateException("예상치 못한 오류")

            val persona = Persona(
                id = "p11",
                name = "예외 테스트 페르소나",
                systemPrompt = "폴백",
                promptTemplateId = "template-error"
            )

            var threw = false
            try {
                persona.resolveEffectivePrompt(faultyStore)
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "resolveEffectivePrompt는 내부 예외를 삼키고 절대 throw해서는 안 된다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // responseGuideline 추가 동작
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ResponseGuidelineAppending {

        @Test
        fun `responseGuideline이 있으면 기본 프롬프트 끝에 추가된다`() {
            val persona = Persona(
                id = "p12",
                name = "가이드라인 페르소나",
                systemPrompt = "기본 프롬프트",
                responseGuideline = "항상 글머리 기호로 응답하세요."
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertTrue(result.startsWith("기본 프롬프트")) {
                "결과 시작 부분에 systemPrompt가 포함되어야 한다"
            }
            assertTrue(result.contains("항상 글머리 기호로 응답하세요.")) {
                "responseGuideline이 결과에 포함되어야 한다"
            }
            assertTrue(result.contains("\n\n")) {
                "기본 프롬프트와 가이드라인 사이에 이중 개행이 있어야 한다"
            }
        }

        @Test
        fun `responseGuideline이 null이면 기본 프롬프트만 반환된다`() {
            val persona = Persona(
                id = "p13",
                name = "가이드라인 없는 페르소나",
                systemPrompt = "단순 프롬프트",
                responseGuideline = null
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("단순 프롬프트", result) {
                "responseGuideline이 null이면 systemPrompt만 반환해야 한다"
            }
        }

        @Test
        fun `responseGuideline이 공백이면 기본 프롬프트만 반환된다`() {
            val persona = Persona(
                id = "p14",
                name = "공백 가이드라인 페르소나",
                systemPrompt = "단순 프롬프트",
                responseGuideline = "   "
            )

            val result = persona.resolveEffectivePrompt(promptTemplateStore = null)

            assertEquals("단순 프롬프트", result) {
                "공백 responseGuideline은 추가되지 않아야 한다"
            }
        }

        @Test
        fun `활성 버전 + responseGuideline이 모두 있으면 버전 콘텐츠에 가이드라인이 추가된다`() {
            val store = InMemoryPromptTemplateStore()
            val templateId = UUID.randomUUID().toString()
            store.saveTemplate(PromptTemplate(id = templateId, name = "guideline-template"))

            val version = store.createVersion(templateId, "버전 콘텐츠")!!
            store.activateVersion(templateId, version.id)

            val persona = Persona(
                id = "p15",
                name = "버전+가이드라인 페르소나",
                systemPrompt = "폴백",
                promptTemplateId = templateId,
                responseGuideline = "간결하게 응답하세요."
            )

            val result = persona.resolveEffectivePrompt(store)

            assertTrue(result.startsWith("버전 콘텐츠")) {
                "활성 버전 콘텐츠가 기본 부분으로 사용되어야 한다"
            }
            assertTrue(result.endsWith("간결하게 응답하세요.")) {
                "responseGuideline이 버전 콘텐츠 뒤에 추가되어야 한다"
            }
        }
    }
}
