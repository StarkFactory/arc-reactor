package com.arc.reactor.agent.impl

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [WorkContextPatterns] 및 [matchesAnyHint] 확장 함수 단위 테스트.
 *
 * 대상: 정규식 패턴(이슈 키, OpenAPI URL), 힌트 셋 매칭,
 *       경계값(빈 문자열, 부분 일치, 대소문자) 처리.
 */
class WorkContextPatternsTest {

    @Nested
    inner class ISSUE_KEY_REGEX {

        @Test
        fun `표준 Jira 이슈 키를 매칭한다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("PAY-123")

            result shouldBe true
        }

        @Test
        fun `한 자리 이슈 번호를 매칭한다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("DEV-1")

            result shouldBe true
        }

        @Test
        fun `숫자 포함 프로젝트 키를 매칭한다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("PAY2-456")

            result shouldBe true
        }

        @Test
        fun `언더스코어 포함 프로젝트 키를 매칭한다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("MY_PROJECT-99")

            result shouldBe true
        }

        @Test
        fun `문장 중간의 이슈 키를 매칭한다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX
                .containsMatchIn("PROD-789 관련하여 문의드립니다")

            result shouldBe true
        }

        @Test
        fun `소문자 프로젝트 키는 매칭하지 않는다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("pay-123")

            result shouldBe false
        }

        @Test
        fun `이슈 번호가 0이면 매칭하지 않는다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("PAY-0")

            result shouldBe false
        }

        @Test
        fun `하이픈 없는 문자열은 매칭하지 않는다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("PAY123")

            result shouldBe false
        }

        @Test
        fun `빈 문자열은 매칭하지 않는다`() {
            val result = WorkContextPatterns.ISSUE_KEY_REGEX.containsMatchIn("")

            result shouldBe false
        }

        @Test
        fun `find로 캡처된 이슈 키 값이 정확하다`() {
            val match = WorkContextPatterns.ISSUE_KEY_REGEX.find("이슈 PROJ-42 확인해주세요")

            match!!.value shouldBe "PROJ-42"
        }
    }

    @Nested
    inner class OPENAPI_URL_REGEX {

        @Test
        fun `openapi를 포함하는 http URL을 매칭한다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("http://example.com/openapi.json")

            result shouldBe true
        }

        @Test
        fun `swagger를 포함하는 https URL을 매칭한다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("https://api.example.com/swagger-ui.html")

            result shouldBe true
        }

        @Test
        fun `대문자 Swagger URL도 매칭한다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("https://example.com/Swagger/v1")

            result shouldBe true
        }

        @Test
        fun `openapi와 swagger가 없는 URL은 매칭하지 않는다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("https://example.com/api/v1/health")

            result shouldBe false
        }

        @Test
        fun `http가 아닌 ftp 프로토콜은 매칭하지 않는다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("ftp://example.com/openapi.yaml")

            result shouldBe false
        }

        @Test
        fun `문장 내 OpenAPI URL을 매칭한다`() {
            val result = WorkContextPatterns.OPENAPI_URL_REGEX
                .containsMatchIn("스펙은 https://internal.corp/openapi/v3 참고하세요")

            result shouldBe true
        }
    }

    @Nested
    inner class matchesAnyHint_확장_함수 {

        @Test
        fun `힌트 중 하나가 포함되면 true를 반환한다`() {
            val result = "review queue 확인 부탁해".matchesAnyHint(
                WorkContextPatterns.REVIEW_QUEUE_HINTS
            )

            result shouldBe true
        }

        @Test
        fun `힌트가 하나도 없으면 false를 반환한다`() {
            val result = "오늘 날씨 알려줘".matchesAnyHint(
                WorkContextPatterns.REVIEW_QUEUE_HINTS
            )

            result shouldBe false
        }

        @Test
        fun `빈 힌트 셋이면 false를 반환한다`() {
            val result = "review queue".matchesAnyHint(emptySet())

            result shouldBe false
        }

        @Test
        fun `빈 문자열은 빈 힌트만 매칭한다`() {
            val result = "".matchesAnyHint(setOf(""))

            result shouldBe true
        }

        @Test
        fun `부분 문자열도 매칭한다`() {
            val result = "이 이슈는 blocker입니다".matchesAnyHint(
                WorkContextPatterns.BLOCKER_HINTS
            )

            result shouldBe true
        }
    }

    @Nested
    inner class 힌트셋_내용_검증 {

        @Test
        fun `BLOCKER_HINTS에 blocker가 포함된다`() {
            WorkContextPatterns.BLOCKER_HINTS.contains("blocker") shouldBe true
        }

        @Test
        fun `REVIEW_QUEUE_HINTS에 queue가 포함된다`() {
            WorkContextPatterns.REVIEW_QUEUE_HINTS.contains("queue") shouldBe true
        }

        @Test
        fun `SUMMARY_HINTS에 summary와 요약이 포함된다`() {
            WorkContextPatterns.SUMMARY_HINTS.contains("summary") shouldBe true
            WorkContextPatterns.SUMMARY_HINTS.contains("요약") shouldBe true
        }

        @Test
        fun `VALIDATE_HINTS에 validate가 포함된다`() {
            WorkContextPatterns.VALIDATE_HINTS.contains("validate") shouldBe true
        }

        @Test
        fun `JIRA_BRIEFING_HINTS에 daily briefing이 포함된다`() {
            WorkContextPatterns.JIRA_BRIEFING_HINTS.contains("daily briefing") shouldBe true
        }

        @Test
        fun `HYBRID_PRIORITY_HINTS에 priority가 포함된다`() {
            WorkContextPatterns.HYBRID_PRIORITY_HINTS.contains("priority") shouldBe true
        }

        @Test
        fun `PRE_DEPLOY_READINESS_HINTS에 pre-release가 포함된다`() {
            WorkContextPatterns.PRE_DEPLOY_READINESS_HINTS.contains("pre-release") shouldBe true
        }

        @Test
        fun `WORK_PERSONAL_FOCUS_HINTS에 focus plan이 포함된다`() {
            WorkContextPatterns.WORK_PERSONAL_FOCUS_HINTS.contains("focus plan") shouldBe true
        }

        @Test
        fun `MISSING_ASSIGNEE_HINTS에 unassigned가 포함된다`() {
            WorkContextPatterns.MISSING_ASSIGNEE_HINTS.contains("unassigned") shouldBe true
        }

        @Test
        fun `HYBRID_RELEASE_RISK_HINTS에 release risk가 포함된다`() {
            WorkContextPatterns.HYBRID_RELEASE_RISK_HINTS.contains("release risk") shouldBe true
        }
    }

    @Nested
    inner class 실제_쿼리_시나리오 {

        @Test
        fun `우선순위 관련 쿼리가 HYBRID_PRIORITY_HINTS와 매칭된다`() {
            val query = "오늘 우선순위 어떻게 돼?"
            val result = query.matchesAnyHint(WorkContextPatterns.HYBRID_PRIORITY_HINTS)

            result shouldBe true
        }

        @Test
        fun `차단 이슈 쿼리가 BLOCKER_HINTS와 매칭된다`() {
            val query = "이 작업이 차단되어 있어서 진행 불가"
            val result = query.matchesAnyHint(WorkContextPatterns.BLOCKER_HINTS)

            result shouldBe true
        }

        @Test
        fun `릴리즈 준비 쿼리가 WORK_RELEASE_READINESS_HINTS와 매칭된다`() {
            val query = "다음 주 릴리즈 준비 상태 알려줘"
            val result = query.matchesAnyHint(WorkContextPatterns.WORK_RELEASE_READINESS_HINTS)

            result shouldBe true
        }

        @Test
        fun `마감 정리 쿼리가 WORK_PERSONAL_WRAPUP_HINTS와 매칭된다`() {
            val query = "오늘 하루 마감 정리 해줘"
            val result = query.matchesAnyHint(WorkContextPatterns.WORK_PERSONAL_WRAPUP_HINTS)

            result shouldBe true
        }

        @Test
        fun `전혀 관계없는 쿼리는 어떤 힌트셋과도 매칭되지 않는다`() {
            val query = "지금 몇 시야"
            val allHintSets = listOf(
                WorkContextPatterns.BLOCKER_HINTS,
                WorkContextPatterns.REVIEW_QUEUE_HINTS,
                WorkContextPatterns.SUMMARY_HINTS,
                WorkContextPatterns.VALIDATE_HINTS
            )
            val anyMatch = allHintSets.any { query.matchesAnyHint(it) }

            anyMatch shouldBe false
        }
    }
}
