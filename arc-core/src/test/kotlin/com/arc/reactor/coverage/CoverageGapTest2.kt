package com.arc.reactor.coverage

import com.arc.reactor.agent.config.ToolPolicyDynamicProperties
import com.arc.reactor.agent.config.ToolPolicyProperties
import com.arc.reactor.guard.impl.ClassificationRule
import com.arc.reactor.guard.impl.RuleBasedClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import com.arc.reactor.rag.ingestion.InMemoryRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionPolicy
import com.arc.reactor.rag.ingestion.toDocument
import com.arc.reactor.rag.ingestion.toDocuments
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 커버리지 갭 테스트 — 2차 라운드.
 *
 * 이전에 테스트되지 않은 다음 4개 컴포넌트를 검증한다:
 *
 * 1. [ToolPolicyProvider] — 마스터 스위치, 동적/정적 분기, TTL 캐시, 정책 정규화, 실패 폴백
 * 2. [InMemoryToolPolicyStore] — 저장/조회/삭제, createdAt 불변성, null 반환
 * 3. [RuleBasedClassificationStage] — minMatchCount > 1 경로 (기존 테스트에서 누락)
 * 4. [RagIngestionDocumentSupport] 확장 함수 — toDocument(), toDocuments(), Q&A 콘텐츠 형식
 */
class CoverageGapTest2 {

    // ════════════════════════════════════════════════════════════
    // 1. InMemoryToolPolicyStore
    // ════════════════════════════════════════════════════════════

    @Nested
    inner class InMemoryToolPolicyStore저장소 {

        private fun defaultPolicy(enabled: Boolean = true) = ToolPolicy(
            enabled = enabled,
            writeToolNames = setOf("jira_create"),
            denyWriteChannels = setOf("slack"),
            allowWriteToolNamesInDenyChannels = emptySet(),
            allowWriteToolNamesByChannel = emptyMap(),
            denyWriteMessage = "거부됨"
        )

        @Test
        fun `초기값 없이 생성하면 null을 반환한다`() {
            val store = InMemoryToolPolicyStore()
            assertNull(store.getOrNull()) { "초기 정책이 없으면 null이어야 한다" }
        }

        @Test
        fun `초기값을 주입하면 getOrNull이 반환한다`() {
            val policy = defaultPolicy()
            val store = InMemoryToolPolicyStore(initial = policy)
            val result = store.getOrNull()
            assertNotNull(result) { "초기값이 주입된 경우 null이 아니어야 한다" }
            assertEquals(policy.enabled, result!!.enabled) { "enabled 값이 일치해야 한다" }
        }

        @Test
        fun `save 후 getOrNull로 조회할 수 있다`() {
            val store = InMemoryToolPolicyStore()
            val policy = defaultPolicy()
            store.save(policy)
            val loaded = store.getOrNull()
            assertNotNull(loaded) { "저장 후 조회할 수 있어야 한다" }
            assertEquals("jira_create", loaded!!.writeToolNames.first()) { "writeToolNames가 보존되어야 한다" }
        }

        @Test
        fun `두 번 save하면 createdAt이 최초 생성 시각을 유지한다`() {
            val store = InMemoryToolPolicyStore()
            val first = defaultPolicy()
            val saved1 = store.save(first)
            val firstCreatedAt = saved1.createdAt

            // 두 번째 저장 시 내용 변경
            val second = first.copy(writeToolNames = setOf("confluence_update"))
            val saved2 = store.save(second)

            assertEquals(firstCreatedAt, saved2.createdAt) { "두 번째 저장에서도 createdAt은 최초값을 유지해야 한다" }
            assertNotEquals(saved1.updatedAt, saved2.updatedAt) { "updatedAt은 새로운 시각이어야 한다" }
        }

        @Test
        fun `delete 후 getOrNull이 null을 반환한다`() {
            val store = InMemoryToolPolicyStore(initial = defaultPolicy())
            val deleted = store.delete()
            assertTrue(deleted) { "기존 정책 삭제 시 true를 반환해야 한다" }
            assertNull(store.getOrNull()) { "삭제 후 getOrNull은 null이어야 한다" }
        }

        @Test
        fun `빈 저장소에서 delete하면 false를 반환한다`() {
            val store = InMemoryToolPolicyStore()
            val deleted = store.delete()
            assertFalse(deleted) { "정책이 없는 경우 delete는 false를 반환해야 한다" }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 2. ToolPolicyProvider
    // ════════════════════════════════════════════════════════════

    @Nested
    inner class ToolPolicyProvider정책제공 {

        private fun properties(
            enabled: Boolean = true,
            dynamicEnabled: Boolean = false,
            writeTools: Set<String> = setOf("jira_create"),
            denyChannels: Set<String> = setOf("slack"),
            denyMessage: String = "거부됨"
        ) = ToolPolicyProperties(
            enabled = enabled,
            dynamic = ToolPolicyDynamicProperties(enabled = dynamicEnabled),
            writeToolNames = writeTools,
            denyWriteChannels = denyChannels,
            denyWriteMessage = denyMessage
        )

        @Nested
        inner class 마스터스위치비활성화 {

            @Test
            fun `enabled=false이면 비활성화된 빈 정책을 반환한다`() {
                val store = mockk<ToolPolicyStore>()
                val provider = ToolPolicyProvider(properties(enabled = false), store)
                val policy = provider.current()
                assertFalse(policy.enabled) { "마스터 스위치가 꺼지면 enabled=false 정책이 반환되어야 한다" }
                assertTrue(policy.writeToolNames.isEmpty()) { "비활성화 정책은 빈 writeToolNames를 가져야 한다" }
                assertTrue(policy.denyWriteChannels.isEmpty()) { "비활성화 정책은 빈 denyWriteChannels를 가져야 한다" }
            }
        }

        @Nested
        inner class 정적정책분기 {

            @Test
            fun `dynamic=false이면 application yml 속성에서 정책을 반환한다`() {
                val store = mockk<ToolPolicyStore>()
                val props = properties(dynamicEnabled = false, writeTools = setOf("  my_tool  "))
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertTrue(policy.enabled) { "정적 정책에서 enabled가 true이어야 한다" }
                // normalize: 공백 제거
                assertTrue(policy.writeToolNames.contains("my_tool")) { "공백이 제거된 도구 이름이 있어야 한다" }
                assertFalse(policy.writeToolNames.contains("  my_tool  ")) { "공백이 있는 원본 이름이 없어야 한다" }
            }

            @Test
            fun `denyWriteChannels는 소문자로 정규화된다`() {
                val store = mockk<ToolPolicyStore>()
                val props = properties(dynamicEnabled = false, denyChannels = setOf("  SLACK  ", "Teams"))
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertTrue(policy.denyWriteChannels.contains("slack")) { "SLACK이 소문자 slack으로 정규화되어야 한다" }
                assertTrue(policy.denyWriteChannels.contains("teams")) { "Teams가 소문자 teams로 정규화되어야 한다" }
                assertFalse(policy.denyWriteChannels.contains("SLACK")) { "대문자 SLACK이 존재해서는 안 된다" }
            }

            @Test
            fun `denyWriteMessage가 blank이면 속성 기본값을 사용한다`() {
                val store = mockk<ToolPolicyStore>()
                val props = properties(dynamicEnabled = false, denyMessage = "기본거부메시지")
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertEquals("기본거부메시지", policy.denyWriteMessage) { "denyWriteMessage가 속성값과 일치해야 한다" }
            }

            @Test
            fun `빈 문자열 도구 이름은 정규화에서 필터링된다`() {
                val store = mockk<ToolPolicyStore>()
                val props = properties(dynamicEnabled = false, writeTools = setOf("valid_tool", "  ", ""))
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertEquals(1, policy.writeToolNames.size) { "빈 문자열 도구 이름은 필터링되어야 한다" }
                assertTrue(policy.writeToolNames.contains("valid_tool")) { "유효한 도구 이름은 보존되어야 한다" }
            }
        }

        @Nested
        inner class 동적정책분기 {

            @Test
            fun `dynamic=true이고 저장소에 정책이 있으면 저장소 정책을 반환한다`() {
                val storedPolicy = ToolPolicy(
                    enabled = true,
                    writeToolNames = setOf("confluence_update"),
                    denyWriteChannels = setOf("slack"),
                    allowWriteToolNamesInDenyChannels = emptySet(),
                    allowWriteToolNamesByChannel = emptyMap(),
                    denyWriteMessage = "동적거부",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH
                )
                val store = mockk<ToolPolicyStore>()
                every { store.getOrNull() } returns storedPolicy
                val props = properties(dynamicEnabled = true)
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertTrue(policy.writeToolNames.contains("confluence_update")) {
                    "저장소 정책의 writeToolNames가 반영되어야 한다"
                }
                assertEquals("동적거부", policy.denyWriteMessage) { "저장소 정책의 denyWriteMessage가 반영되어야 한다" }
            }

            @Test
            fun `dynamic=true이고 저장소가 null을 반환하면 속성 기반 정책을 반환한다`() {
                val store = mockk<ToolPolicyStore>()
                every { store.getOrNull() } returns null
                val props = properties(dynamicEnabled = true, writeTools = setOf("fallback_tool"))
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertTrue(policy.writeToolNames.contains("fallback_tool")) {
                    "저장소가 null 반환 시 속성 기반 폴백이 적용되어야 한다"
                }
            }

            @Test
            fun `dynamic=true이고 저장소 호출이 실패하면 속성 기반 폴백을 반환한다`() {
                val store = mockk<ToolPolicyStore>()
                every { store.getOrNull() } throws RuntimeException("DB 연결 실패")
                val props = properties(dynamicEnabled = true, writeTools = setOf("fallback_tool"))
                val provider = ToolPolicyProvider(props, store)
                val policy = provider.current()
                assertTrue(policy.writeToolNames.contains("fallback_tool")) {
                    "저장소 예외 시 속성 기반 폴백이 적용되어야 한다"
                }
            }

            @Test
            fun `invalidate 후 다음 호출에서 저장소를 다시 조회한다`() {
                var callCount = 0
                val store = mockk<ToolPolicyStore>()
                every { store.getOrNull() } answers {
                    callCount++
                    ToolPolicy(
                        enabled = true,
                        writeToolNames = setOf("tool_$callCount"),
                        denyWriteChannels = emptySet(),
                        allowWriteToolNamesInDenyChannels = emptySet(),
                        allowWriteToolNamesByChannel = emptyMap(),
                        denyWriteMessage = "거부",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH
                    )
                }
                val props = properties(dynamicEnabled = true)
                val provider = ToolPolicyProvider(props, store)

                provider.current() // 첫 번째 호출 → 저장소 조회
                provider.invalidate() // 캐시 무효화
                val policy2 = provider.current() // 두 번째 호출 → 다시 저장소 조회

                assertEquals(2, callCount) { "invalidate 후 저장소를 다시 조회해야 한다" }
                assertTrue(policy2.writeToolNames.contains("tool_2")) { "두 번째 조회에서 최신 정책이 반영되어야 한다" }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 3. RuleBasedClassificationStage — minMatchCount > 1 경로
    // ════════════════════════════════════════════════════════════

    @Nested
    inner class RuleBasedClassificationStage_다중매칭 {

        /**
         * minMatchCount > 1인 경우의 로직 검증.
         * 기존 ClassificationStageTest는 모두 minMatchCount=1만 테스트한다.
         */
        private fun makeStageWithCountRule(minCount: Int) = RuleBasedClassificationStage(
            blockedCategories = setOf("test_category"),
            customRules = listOf(
                ClassificationRule(
                    category = "test_category",
                    keywords = listOf("alpha", "beta", "gamma"),
                    minMatchCount = minCount
                )
            )
        )

        @Test
        fun `minMatchCount=2일 때 1개 키워드 매칭은 허용된다`() = runTest {
            val stage = makeStageWithCountRule(minCount = 2)
            val result = stage.enforce(GuardCommand(userId = "u1", text = "alpha is fine here"))
            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "키워드 1개만 매칭되면 minMatchCount=2 규칙에서 Allowed이어야 한다"
            }
        }

        @Test
        fun `minMatchCount=2일 때 2개 키워드 매칭은 거부된다`() = runTest {
            val stage = makeStageWithCountRule(minCount = 2)
            val result = stage.enforce(GuardCommand(userId = "u1", text = "alpha and beta together"))
            assertNotNull(result) { "결과가 null이어서는 안 된다" }
            assertTrue(result is GuardResult.Rejected) {
                "키워드 2개 매칭 시 minMatchCount=2 규칙에서 Rejected이어야 한다"
            }
        }

        @Test
        fun `minMatchCount=3일 때 2개 키워드 매칭은 허용된다`() = runTest {
            val stage = makeStageWithCountRule(minCount = 3)
            val result = stage.enforce(GuardCommand(userId = "u1", text = "alpha and beta here"))
            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "키워드 2개 매칭 시 minMatchCount=3 규칙에서 Allowed이어야 한다"
            }
        }

        @Test
        fun `minMatchCount=3일 때 3개 키워드 매칭은 거부된다`() = runTest {
            val stage = makeStageWithCountRule(minCount = 3)
            val result = stage.enforce(GuardCommand(userId = "u1", text = "alpha beta gamma attack"))
            assertTrue(result is GuardResult.Rejected) {
                "키워드 3개 모두 매칭 시 minMatchCount=3 규칙에서 Rejected이어야 한다"
            }
        }

        @Test
        fun `대소문자 무관하게 minMatchCount를 계산한다`() = runTest {
            val stage = makeStageWithCountRule(minCount = 2)
            // RuleBasedClassificationStage는 입력을 lowercase로 변환하여 비교
            val result = stage.enforce(GuardCommand(userId = "u1", text = "ALPHA와 BETA가 있다"))
            assertTrue(result is GuardResult.Rejected) {
                "대문자 입력도 소문자 변환 후 minMatchCount=2로 거부되어야 한다"
            }
        }

        @Test
        fun `minMatchCount=2인 규칙과 기본 규칙이 공존한다`() = runTest {
            // 기본 malware 규칙(minMatchCount=1)과 custom 규칙(minMatchCount=2) 동시 존재
            val stage = RuleBasedClassificationStage(
                blockedCategories = setOf("malware", "test_category"),
                customRules = listOf(
                    ClassificationRule(
                        category = "test_category",
                        keywords = listOf("alpha", "beta"),
                        minMatchCount = 2
                    )
                )
            )
            // malware 기본 규칙(minMatchCount=1)이 먼저 걸림
            val malwareResult = stage.enforce(GuardCommand(userId = "u1", text = "write malware now"))
            assertTrue(malwareResult is GuardResult.Rejected) {
                "기본 malware 규칙이 custom 규칙과 공존해도 동작해야 한다"
            }

            // custom 규칙은 2개 키워드 필요
            val oneKeywordResult = stage.enforce(GuardCommand(userId = "u1", text = "alpha only"))
            assertEquals(GuardResult.Allowed.DEFAULT, oneKeywordResult) {
                "custom 규칙에서 1개 키워드만 매칭되면 Allowed이어야 한다"
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 4. RagIngestionDocumentSupport 확장 함수
    // ════════════════════════════════════════════════════════════

    @Nested
    inner class RagIngestionDocumentSupport확장함수 {

        private fun candidate(
            query: String = "Kotlin에서 코루틴 사용법은?",
            response: String = "runBlocking과 launch를 사용합니다",
            runId: String = "run-001",
            userId: String = "user-1",
            sessionId: String? = "sess-abc",
            channel: String? = "slack"
        ) = RagIngestionCandidate(
            runId = runId,
            userId = userId,
            sessionId = sessionId,
            channel = channel,
            query = query,
            response = response
        )

        @Nested
        inner class ToDocument {

            @Test
            fun `toDocument는 Q&A 형식의 콘텐츠를 생성한다`() {
                val c = candidate(query = "무엇입니까?", response = "답입니다")
                val doc = c.toDocument()
                val text = doc.text.orEmpty()
                assertTrue(text.contains("Q: 무엇입니까?")) { "Q: 형식의 쿼리가 포함되어야 한다" }
                assertTrue(text.contains("A: 답입니다")) { "A: 형식의 응답이 포함되어야 한다" }
            }

            @Test
            fun `toDocument는 메타데이터에 source를 포함한다`() {
                val doc = candidate().toDocument()
                assertEquals("rag_ingestion_candidate", doc.metadata["source"]) {
                    "메타데이터에 source=rag_ingestion_candidate가 있어야 한다"
                }
            }

            @Test
            fun `toDocument는 메타데이터에 runId, userId, capturedAt을 포함한다`() {
                val c = candidate(runId = "run-xyz", userId = "alice")
                val doc = c.toDocument()
                assertEquals("run-xyz", doc.metadata["runId"]) { "runId가 메타데이터에 있어야 한다" }
                assertEquals("alice", doc.metadata["userId"]) { "userId가 메타데이터에 있어야 한다" }
                assertNotNull(doc.metadata["capturedAt"]) { "capturedAt이 메타데이터에 있어야 한다" }
            }

            @Test
            fun `toDocument는 sessionId가 있으면 메타데이터에 포함한다`() {
                val c = candidate(sessionId = "session-123")
                val doc = c.toDocument()
                assertEquals("session-123", doc.metadata["sessionId"]) {
                    "sessionId가 있으면 메타데이터에 포함되어야 한다"
                }
            }

            @Test
            fun `toDocument는 sessionId가 null이면 메타데이터에 포함하지 않는다`() {
                val c = candidate(sessionId = null)
                val doc = c.toDocument()
                assertFalse(doc.metadata.containsKey("sessionId")) {
                    "sessionId가 null이면 메타데이터에 없어야 한다"
                }
            }

            @Test
            fun `toDocument는 channel이 있으면 메타데이터에 포함한다`() {
                val c = candidate(channel = "web")
                val doc = c.toDocument()
                assertEquals("web", doc.metadata["channel"]) { "channel이 있으면 메타데이터에 포함되어야 한다" }
            }

            @Test
            fun `toDocument는 channel이 null이면 메타데이터에 포함하지 않는다`() {
                val c = candidate(channel = null)
                val doc = c.toDocument()
                assertFalse(doc.metadata.containsKey("channel")) {
                    "channel이 null이면 메타데이터에 없어야 한다"
                }
            }

            @Test
            fun `toDocument는 지정된 documentId를 사용한다`() {
                val doc = candidate().toDocument(documentId = "doc-fixed-id")
                assertEquals("doc-fixed-id", doc.id.orEmpty()) { "지정된 documentId가 Document.id로 사용되어야 한다" }
            }

            @Test
            fun `toDocument는 documentId가 없으면 UUID를 자동 생성한다`() {
                val doc = candidate().toDocument()
                assertNotNull(doc.id) { "documentId를 지정하지 않으면 자동 생성되어야 한다" }
                assertTrue(doc.id.orEmpty().isNotBlank()) { "자동 생성된 documentId는 비어있지 않아야 한다" }
            }

            @Test
            fun `toDocument는 쿼리와 응답의 공백을 trim한다`() {
                val c = candidate(query = "  질문  ", response = "  응답  ")
                val doc = c.toDocument()
                val trimText = doc.text.orEmpty()
                assertTrue(trimText.contains("Q: 질문")) { "쿼리 앞뒤 공백이 제거되어야 한다" }
                assertTrue(trimText.contains("A: 응답")) { "응답 앞뒤 공백이 제거되어야 한다" }
            }
        }

        @Nested
        inner class ToDocuments {

            @Test
            fun `toDocuments는 chunker 없이 단일 문서 리스트를 반환한다`() {
                val docs = candidate().toDocuments(chunker = null)
                assertEquals(1, docs.size) { "chunker가 없으면 단일 문서 리스트여야 한다" }
            }

            @Test
            fun `toDocuments의 단일 문서는 toDocument와 동일한 콘텐츠를 가진다`() {
                val c = candidate(query = "동일 쿼리", response = "동일 응답")
                val docId = "fixed-id"
                val fromDocuments = c.toDocuments(documentId = docId, chunker = null).first()
                val fromDocument = c.toDocument(documentId = docId)
                assertEquals(fromDocument.text.orEmpty(), fromDocuments.text.orEmpty()) {
                    "toDocuments와 toDocument의 콘텐츠가 같아야 한다"
                }
                assertEquals(fromDocument.id.orEmpty(), fromDocuments.id.orEmpty()) {
                    "toDocuments와 toDocument의 id가 같아야 한다"
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 5. InMemoryRagIngestionPolicyStore
    // ════════════════════════════════════════════════════════════

    @Nested
    inner class InMemoryRagIngestionPolicyStore저장소 {

        private fun policy(enabled: Boolean = true) = RagIngestionPolicy(
            enabled = enabled,
            requireReview = true,
            allowedChannels = setOf("web"),
            minQueryChars = 10,
            minResponseChars = 20,
            blockedPatterns = emptySet()
        )

        @Test
        fun `초기값 없이 생성하면 null을 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore()
            assertNull(store.getOrNull()) { "초기 정책이 없으면 null이어야 한다" }
        }

        @Test
        fun `초기값 주입 후 getOrNull이 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore(initial = policy())
            assertNotNull(store.getOrNull()) { "초기값이 있으면 null이 아니어야 한다" }
        }

        @Test
        fun `두 번 save 시 createdAt이 최초 시각을 유지한다`() {
            val store = InMemoryRagIngestionPolicyStore()
            val saved1 = store.save(policy())
            val firstCreatedAt = saved1.createdAt

            val saved2 = store.save(policy(enabled = false))
            assertEquals(firstCreatedAt, saved2.createdAt) {
                "두 번째 저장에서도 createdAt은 최초값을 유지해야 한다"
            }
        }

        @Test
        fun `delete 후 getOrNull은 null을 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore(initial = policy())
            val deleted = store.delete()
            assertTrue(deleted) { "정책이 있을 때 delete는 true를 반환해야 한다" }
            assertNull(store.getOrNull()) { "삭제 후 null이어야 한다" }
        }

        @Test
        fun `빈 저장소에서 delete는 false를 반환한다`() {
            val store = InMemoryRagIngestionPolicyStore()
            assertFalse(store.delete()) { "정책이 없을 때 delete는 false를 반환해야 한다" }
        }
    }
}
