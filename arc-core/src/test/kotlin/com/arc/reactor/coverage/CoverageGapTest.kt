package com.arc.reactor.coverage

import com.arc.reactor.agent.impl.defaultTransientErrorClassifier
import com.arc.reactor.guard.impl.ClassificationRule
import com.arc.reactor.guard.impl.RuleBasedClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.rag.search.Bm25Scorer
import com.arc.reactor.tool.WorkspaceMutationIntentDetector
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 커버리지 갭 보완 테스트.
 *
 * 기존 테스트가 없거나 부족한 영역을 집중 검증한다:
 * - [defaultTransientErrorClassifier] null/빈 예외 메시지 경계 케이스
 * - [RuleBasedClassificationStage] minMatchCount > 1 다중 키워드 임계값 경로
 * - [Bm25Scorer] ReentrantReadWriteLock 동시 읽기/쓰기 안전성
 * - [WorkspaceMutationIntentDetector] null/blank 입력 및 영어 동사 변형
 */
class CoverageGapTest {

    // =========================================================================
    // 1. defaultTransientErrorClassifier — null/빈 메시지 경계 케이스
    // =========================================================================

    @Nested
    inner class DefaultTransientErrorClassifierBoundary {

        @Test
        fun `null 메시지 예외는 일시적 오류가 아니어야 한다`() {
            // message?.lowercase() ?: return false 경로를 검증한다
            val ex = RuntimeException(null as String?)
            assertFalse(defaultTransientErrorClassifier(ex)) {
                "null 메시지를 가진 예외는 일시적 오류로 분류되어서는 안 된다"
            }
        }

        @Test
        fun `빈 메시지 예외는 일시적 오류가 아니어야 한다`() {
            val ex = RuntimeException("")
            assertFalse(defaultTransientErrorClassifier(ex)) {
                "빈 메시지를 가진 예외는 일시적 오류로 분류되어서는 안 된다"
            }
        }

        @Test
        fun `공백만 있는 메시지 예외는 일시적 오류가 아니어야 한다`() {
            val ex = RuntimeException("   ")
            assertFalse(defaultTransientErrorClassifier(ex)) {
                "공백만 있는 메시지 예외는 일시적 오류로 분류되어서는 안 된다"
            }
        }

        @Test
        fun `HTTP 코드 패턴에 일치하는 예외는 일시적 오류여야 한다`() {
            // httpStatusPattern = "(status|http|error|code)[^a-z0-9]*(429|500|502|503|504)"
            val ex429 = RuntimeException("status 429 Too Many Requests")
            assertTrue(defaultTransientErrorClassifier(ex429)) {
                "HTTP 429 상태 코드 패턴은 일시적 오류로 분류되어야 한다"
            }

            val ex500 = RuntimeException("HTTP 500 Internal Server Error")
            assertTrue(defaultTransientErrorClassifier(ex500)) {
                "HTTP 500 상태 코드 패턴은 일시적 오류로 분류되어야 한다"
            }

            val ex503 = RuntimeException("error code 503 service down")
            assertTrue(defaultTransientErrorClassifier(ex503)) {
                "HTTP 503 에러 코드 패턴은 일시적 오류로 분류되어야 한다"
            }
        }

        @Test
        fun `HTTP 코드 패턴에서 200 같은 비일시적 코드는 일시적 오류가 아니어야 한다`() {
            // 200, 404, 400 등은 httpStatusPattern에 없음
            val ex = RuntimeException("status 200 OK")
            assertFalse(defaultTransientErrorClassifier(ex)) {
                "HTTP 200은 일시적 오류 패턴이 아니어야 한다"
            }
        }
    }

    // =========================================================================
    // 2. RuleBasedClassificationStage — minMatchCount > 1 다중 키워드 임계값
    // =========================================================================

    @Nested
    inner class RuleBasedClassificationMinMatchCount {

        private val multiKeywordRule = ClassificationRule(
            category = "coordinated_fraud",
            keywords = listOf("wire transfer", "account number", "urgent", "secret"),
            minMatchCount = 3
        )

        private val stage = RuleBasedClassificationStage(
            blockedCategories = setOf("coordinated_fraud"),
            customRules = listOf(multiKeywordRule)
        )

        @Test
        fun `키워드 수가 임계값 미만이면 허용되어야 한다`() = runTest {
            // 2개 매칭, threshold=3 → 허용
            val result = stage.enforce(
                GuardCommand(userId = "u1", text = "please provide your wire transfer and account number")
            )
            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "매칭 키워드 수(2)가 minMatchCount(3)보다 적으면 허용되어야 한다"
            }
        }

        @Test
        fun `키워드 수가 임계값과 같으면 거부되어야 한다`() = runTest {
            // 3개 매칭, threshold=3 → 거부
            val result = stage.enforce(
                GuardCommand(userId = "u1", text = "urgent: wire transfer your account number now")
            )
            val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "매칭 키워드 수(3)가 minMatchCount(3)와 같으면 거부되어야 한다"
            }
            assertEquals(RejectionCategory.OFF_TOPIC, rejected.category) {
                "거부 카테고리는 OFF_TOPIC이어야 한다"
            }
        }

        @Test
        fun `키워드 수가 임계값을 초과하면 거부되어야 한다`() = runTest {
            // 4개 모두 매칭, threshold=3 → 거부
            val result = stage.enforce(
                GuardCommand(userId = "u1", text = "urgent secret wire transfer account number instructions")
            )
            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "매칭 키워드 수(4)가 minMatchCount(3)를 초과하면 거부되어야 한다"
            }
        }

        @Test
        fun `대소문자가 달라도 키워드 매칭이 동작해야 한다`() = runTest {
            // 소문자 변환 후 비교하므로 대문자 입력도 매칭 가능
            val result = stage.enforce(
                GuardCommand(userId = "u1", text = "URGENT: WIRE TRANSFER your ACCOUNT NUMBER secretly")
            )
            // 4개 모두 매칭 (urgent, wire transfer, account number, secret)
            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "대문자 입력도 소문자 변환 후 정확히 매칭되어 거부되어야 한다"
            }
        }

        @Test
        fun `minMatchCount=1인 기본 규칙은 단일 키워드만으로 거부되어야 한다`() = runTest {
            // 기본 규칙: malware, keywords=["write malware", ...], minMatchCount=1
            val defaultStage = RuleBasedClassificationStage()
            val result = defaultStage.enforce(
                GuardCommand(userId = "u1", text = "I need to write malware for testing")
            )
            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "minMatchCount=1일 때 단일 키워드 매칭만으로 거부되어야 한다"
            }
        }

        @Test
        fun `블락 카테고리에 없는 규칙 카테고리는 매칭되어도 허용되어야 한다`() = runTest {
            // 규칙이 있어도 blockedCategories에 없으면 건너뜀
            val stageWithExclusion = RuleBasedClassificationStage(
                blockedCategories = setOf("weapons"), // coordinated_fraud는 없음
                customRules = listOf(multiKeywordRule)
            )
            val result = stageWithExclusion.enforce(
                GuardCommand(userId = "u1", text = "urgent secret wire transfer account number")
            )
            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "blockedCategories에 없는 카테고리 규칙은 매칭되어도 허용되어야 한다"
            }
        }
    }

    // =========================================================================
    // 3. Bm25Scorer — ReentrantReadWriteLock 동시 읽기/쓰기 안전성
    // =========================================================================

    @Nested
    inner class Bm25ScorerConcurrency {

        private lateinit var scorer: Bm25Scorer

        @BeforeEach
        fun setup() {
            scorer = Bm25Scorer()
        }

        @Test
        fun `동시 인덱싱과 검색이 예외 없이 완료되어야 한다`() = runTest {
            // 100개 문서를 사전 인덱싱
            for (i in 0..99) {
                scorer.index("doc-$i", "keyword-$i content for testing concurrent access pattern")
            }

            val indexCount = AtomicInteger(0)
            val searchCount = AtomicInteger(0)

            // 10개 인덱서와 10개 검색자를 동시에 실행
            val tasks = (0..9).map { i ->
                async {
                    scorer.index("concurrent-doc-$i", "concurrent keyword-$i content batch write")
                    indexCount.incrementAndGet()
                }
            } + (0..9).map { i ->
                async {
                    val results = scorer.search("keyword-$i", topK = 5)
                    assertNotNull(results) {
                        "동시 검색 결과는 null이 아니어야 한다 (i=$i)"
                    }
                    searchCount.incrementAndGet()
                }
            }

            tasks.awaitAll()

            assertEquals(10, indexCount.get()) { "10개 인덱서가 모두 완료되어야 한다" }
            assertEquals(10, searchCount.get()) { "10개 검색자가 모두 완료되어야 한다" }
        }

        @Test
        fun `동시 재인덱싱이 인덱스 크기를 초과 증가시키지 않아야 한다`() = runTest {
            // 같은 docId를 여러 코루틴이 동시에 재인덱싱
            val tasks = (0..19).map { i ->
                async {
                    // 모두 같은 docId "shared-doc"로 인덱싱
                    scorer.index("shared-doc", "shared content iteration $i with extra keywords")
                }
            }
            tasks.awaitAll()

            assertEquals(1, scorer.size) {
                "동일 docId에 대한 동시 재인덱싱은 크기를 1 이상으로 증가시켜서는 안 된다"
            }
        }

        @RepeatedTest(value = 3, name = "동시 clear와 검색 반복 {currentRepetition}/{totalRepetitions}")
        fun `동시 clear와 검색이 예외 없이 동작해야 한다`() = runTest {
            for (i in 0..49) {
                scorer.index("doc-$i", "content for concurrent clear test $i")
            }

            var caughtException: Throwable? = null

            val clearTask = async {
                try {
                    scorer.clear()
                } catch (e: Throwable) {
                    caughtException = e
                }
            }

            val searchTask = async {
                try {
                    scorer.search("content", 10)
                } catch (e: Throwable) {
                    caughtException = e
                }
            }

            listOf(clearTask, searchTask).awaitAll()

            // clear와 검색이 동시에 실행되어도 예외가 발생하지 않아야 한다
            // (결과는 경합 조건에 따라 다를 수 있으나 예외는 없어야 함)
            assertEquals(null, caughtException) {
                "동시 clear와 검색은 예외를 발생시켜서는 안 된다: $caughtException"
            }
        }

        @Test
        fun `동시 score 읽기는 항상 동일한 결과를 반환해야 한다`() = runTest {
            // 읽기는 동시에 실행 가능 (read lock)
            scorer.index("read-doc", "platform team alpha bravo charlie")

            val scores = (0..19).map {
                async { scorer.score("platform team", "read-doc") }
            }.awaitAll()

            val first = scores[0]
            assertTrue(first > 0.0) { "score는 0보다 커야 한다" }
            scores.forEachIndexed { i, score ->
                assertEquals(first, score, 0.000001) {
                    "동시 읽기 ${i}번째 score는 첫 번째 결과($first)와 같아야 한다"
                }
            }
        }
    }

    // =========================================================================
    // 4. WorkspaceMutationIntentDetector — null/blank 및 영어 동사 변형
    // =========================================================================

    @Nested
    inner class WorkspaceMutationIntentDetectorEdgeCases {

        @Test
        fun `null 입력은 변경 의도가 없어야 한다`() {
            assertFalse(WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(null)) {
                "null 프롬프트는 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `빈 문자열은 변경 의도가 없어야 한다`() {
            assertFalse(WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("")) {
                "빈 문자열은 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `공백만 있는 문자열은 변경 의도가 없어야 한다`() {
            assertFalse(WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("   ")) {
                "공백만 있는 문자열은 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `영어 create 동사로 Jira 이슈 생성 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("create a jira issue for this bug")
            ) {
                "영어 'create' + 'jira' + 'issue' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `영어 update 동사로 Confluence 페이지 수정 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("update the confluence page with new documentation")
            ) {
                "영어 'update' + 'confluence' + 'page' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `영어 delete 동사로 Bitbucket 브랜치 삭제 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("delete the feature branch in bitbucket repository")
            ) {
                "영어 'delete' + 'bitbucket' + 'branch' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `영어 comment 동사로 Jira 이슈 코멘트 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("comment on the jira issue DEV-42 with status update")
            ) {
                "영어 'comment' + 'jira' + 'issue' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `영어 assign 동사로 이슈 할당 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("assign this jira ticket to the backend developer")
            ) {
                "영어 'assign' + 'jira' + 'ticket' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `단순 조회는 변경 의도가 아니어야 한다`() {
            assertFalse(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("show me all jira issues in DEV project")
            ) {
                "단순 조회 요청은 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `워크스페이스 힌트 없이 변경 동사만 있는 경우는 의도 없어야 한다`() {
            // workspace hint 없음 → 3가지 조건 중 1개 실패 → false
            assertFalse(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("please create and delete the document")
            ) {
                "워크스페이스 힌트 없이 변경 동사만 있는 경우 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `변경 동사 없이 워크스페이스 힌트만 있는 경우는 의도 없어야 한다`() {
            // mutation hint 없음
            assertFalse(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("show me the jira issue list for DEV project")
            ) {
                "변경 동사 없이 워크스페이스 힌트만 있는 경우 변경 의도로 판단되어서는 안 된다"
            }
        }

        @Test
        fun `영어 write 동사로 Confluence 페이지 작성 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("write a new confluence page about the architecture")
            ) {
                "영어 'write' + 'confluence' + 'page' 조합은 변경 의도로 감지되어야 한다"
            }
        }

        @Test
        fun `approve 동사로 Pull Request 승인 의도를 감지해야 한다`() {
            assertTrue(
                WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt("approve the pull request in bitbucket")
            ) {
                "영어 'approve' + 'bitbucket' + 'pull request' 조합은 변경 의도로 감지되어야 한다"
            }
        }
    }
}
