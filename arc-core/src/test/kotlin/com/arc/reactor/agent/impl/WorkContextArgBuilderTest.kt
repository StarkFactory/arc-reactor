package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WorkContextArgBuilder에 대한 테스트.
 *
 * 강제 도구 호출 인자 맵 생성과 추론 로직을 검증한다.
 */
class WorkContextArgBuilderTest {

    @Nested
    inner class InferReleaseName {

        @Test
        fun `따옴표 키워드가 있으면 릴리즈 이름으로 사용해야 한다`() {
            val result = WorkContextArgBuilder.inferReleaseName(
                prompt = "'v2.0.0' 릴리즈 준비",
                projectKey = "PAY",
                repository = "team" to "repo"
            )
            result shouldBe "v2.0.0"
        }

        @Test
        fun `따옴표 없으면 projectKey를 사용해야 한다`() {
            val result = WorkContextArgBuilder.inferReleaseName(
                prompt = "릴리즈 준비해줘",
                projectKey = "PAY",
                repository = "team" to "repo"
            )
            result shouldBe "PAY"
        }

        @Test
        fun `projectKey도 없으면 repository 이름을 사용해야 한다`() {
            val result = WorkContextArgBuilder.inferReleaseName(
                prompt = "릴리즈 준비",
                projectKey = null,
                repository = "team" to "my-service"
            )
            result shouldBe "my-service"
        }

        @Test
        fun `모두 없으면 기본값을 반환해야 한다`() {
            val result = WorkContextArgBuilder.inferReleaseName(
                prompt = "릴리즈 준비",
                projectKey = null,
                repository = null
            )
            result shouldBe "release-readiness"
        }

        @Test
        fun `빈 프롬프트에서도 기본값을 반환해야 한다`() {
            val result = WorkContextArgBuilder.inferReleaseName(
                prompt = "",
                projectKey = null,
                repository = null
            )
            result shouldBe "release-readiness"
        }
    }

    @Nested
    inner class InferSpecName {

        @Test
        fun `URL에서 파일명 기반 스펙 이름을 추론해야 한다`() {
            WorkContextArgBuilder.inferSpecName("https://example.com/api/payment-api.yaml")
                .shouldBe("payment-api")
        }

        @Test
        fun `쿼리 파라미터를 제거해야 한다`() {
            WorkContextArgBuilder.inferSpecName("https://example.com/spec.json?version=2")
                .shouldBe("spec")
        }

        @Test
        fun `해시 프래그먼트를 제거해야 한다`() {
            // substringAfterLast('/') → "paths" (해시 내 슬래시 포함), 해시 없는 URL로 테스트
            WorkContextArgBuilder.inferSpecName("https://example.com/billing-api.yml#section")
                .shouldBe("billing-api")
        }

        @Test
        fun `퍼센트 인코딩 문자를 하이픈으로 치환해야 한다`() {
            // substringAfterLast('/') → "My%20Api" (공백 이전), 결과: my-20api
            WorkContextArgBuilder.inferSpecName("https://example.com/My%20Api.json")
                .shouldBe("my-20api")
        }

        @Test
        fun `파일명이 없는 URL은 기본값을 반환해야 한다`() {
            WorkContextArgBuilder.inferSpecName("https://example.com/")
                .shouldBe("openapi-spec")
        }

        @Test
        fun `대문자를 소문자로 변환해야 한다`() {
            WorkContextArgBuilder.inferSpecName("https://example.com/PaymentAPI.yaml")
                .shouldBe("paymentapi")
        }

        @Test
        fun `빈 문자열에서 기본값을 반환해야 한다`() {
            WorkContextArgBuilder.inferSpecName("")
                .shouldBe("openapi-spec")
        }
    }

    @Nested
    inner class BuildReleaseRiskArgs {

        @Test
        fun `기본 필드가 포함된 인자 맵을 생성해야 한다`() {
            val args = WorkContextArgBuilder.buildReleaseRiskArgs(
                prompt = "릴리즈 리스크",
                projectKey = "PAY",
                repository = "team" to "repo"
            )

assertTrue(            args.containsKey("releaseName"))
assertTrue(            args.containsKey("stalePrDays"))
assertTrue(            args.containsKey("reviewSlaHours"))
assertTrue(            args.containsKey("jiraMaxResults"))
            args["stalePrDays"] shouldBe 3
            args["reviewSlaHours"] shouldBe 24
            args["jiraMaxResults"] shouldBe 20
        }

        @Test
        fun `projectKey가 있으면 jiraProject를 포함해야 한다`() {
            val args = WorkContextArgBuilder.buildReleaseRiskArgs(
                prompt = "리스크 분석",
                projectKey = "PAY",
                repository = null
            )
            args["jiraProject"] shouldBe "PAY"
        }

        @Test
        fun `repository가 있으면 bitbucket 필드를 포함해야 한다`() {
            val args = WorkContextArgBuilder.buildReleaseRiskArgs(
                prompt = "리스크 분석",
                projectKey = null,
                repository = "myteam" to "my-repo"
            )
            args["bitbucketWorkspace"] shouldBe "myteam"
            args["bitbucketRepo"] shouldBe "my-repo"
        }

        @Test
        fun `projectKey와 repository 모두 null이면 해당 필드를 제외해야 한다`() {
            val args = WorkContextArgBuilder.buildReleaseRiskArgs(
                prompt = "리스크",
                projectKey = null,
                repository = null
            )
assertFalse(            args.containsKey("jiraProject"))
assertFalse(            args.containsKey("bitbucketWorkspace"))
assertFalse(            args.containsKey("bitbucketRepo"))
        }
    }

    @Nested
    inner class BuildReadinessPackArgs {

        @Test
        fun `모든 기본 필드가 포함되어야 한다`() {
            val args = WorkContextArgBuilder.buildReadinessPackArgs(
                prompt = "릴리즈 준비",
                projectKey = "PAY",
                repository = "team" to "repo"
            )

assertTrue(            args.containsKey("releaseName"))
assertTrue(            args.containsKey("stalePrDays"))
assertTrue(            args.containsKey("reviewSlaHours"))
assertTrue(            args.containsKey("daysLookback"))
assertTrue(            args.containsKey("dryRunActionItems"))
assertTrue(            args.containsKey("blockerWeight"))
assertTrue(            args.containsKey("highRiskThreshold"))
            args["dryRunActionItems"] shouldBe true
            args["autoExecuteActionItems"] shouldBe false
            args["highRiskThreshold"] shouldBe 18
            args["mediumRiskThreshold"] shouldBe 10
        }

        @Test
        fun `projectKey null이면 jiraProject를 제외해야 한다`() {
            val args = WorkContextArgBuilder.buildReadinessPackArgs(
                prompt = "준비 상태",
                projectKey = null,
                repository = null
            )
assertFalse(            args.containsKey("jiraProject"))
        }
    }

    @Nested
    inner class BuildMorningBriefingArgs {

        @Test
        fun `기본 confluenceKeyword로 인자 맵을 생성해야 한다`() {
            val args = WorkContextArgBuilder.buildMorningBriefingArgs("PAY")

            args["jiraProject"] shouldBe "PAY"
            args["confluenceKeyword"] shouldBe "status"
            args["reviewSlaHours"] shouldBe 24
            args["dueSoonDays"] shouldBe 7
            args["jiraMaxResults"] shouldBe 20
        }

        @Test
        fun `커스텀 confluenceKeyword를 사용해야 한다`() {
            val args = WorkContextArgBuilder.buildMorningBriefingArgs("PAY", "release")

            args["confluenceKeyword"] shouldBe "release"
        }
    }

    @Nested
    inner class BuildStandupArgs {

        @Test
        fun `projectKey가 있으면 jiraProject를 포함해야 한다`() {
            val args = WorkContextArgBuilder.buildStandupArgs("PAY")

            args["jiraProject"] shouldBe "PAY"
            args["daysLookback"] shouldBe 7
            args["jiraMaxResults"] shouldBe 20
        }

        @Test
        fun `projectKey가 null이면 jiraProject를 제외해야 한다`() {
            val args = WorkContextArgBuilder.buildStandupArgs(null)

assertFalse(            args.containsKey("jiraProject"))
assertTrue(            args.containsKey("daysLookback"))
        }
    }

    @Nested
    inner class BuildLearningDigestArgs {

        @Test
        fun `기본 학습 다이제스트 인자를 반환해야 한다`() {
            val args = WorkContextArgBuilder.buildLearningDigestArgs()

            args["lookbackDays"] shouldBe 14
            args["topTopics"] shouldBe 4
            args["docsPerTopic"] shouldBe 2
        }
    }
}
