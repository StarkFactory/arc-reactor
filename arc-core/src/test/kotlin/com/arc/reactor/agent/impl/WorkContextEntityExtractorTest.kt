package com.arc.reactor.agent.impl

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WorkContextEntityExtractor에 대한 테스트.
 *
 * 사용자 프롬프트에서 이슈 키, 프로젝트 키, URL, 서비스명 등
 * 다양한 엔티티 추출 정규식의 정확성을 검증한다.
 */
class WorkContextEntityExtractorTest {

    @Nested
    inner class ExtractIssueKey {

        @Test
        fun `유효한 이슈 키를 추출해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("PAY-123 이슈를 확인해줘")
                .shouldBe("PAY-123")
        }

        @Test
        fun `소문자 입력도 대문자 변환 후 추출해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("pay-123 상태 알려줘")
                .shouldBe("PAY-123")
        }

        @Test
        fun `숫자가 1로 시작하는 단일 자릿수도 추출해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("JIRA-1 수정해줘")
                .shouldBe("JIRA-1")
        }

        @Test
        fun `프로젝트 키에 숫자가 포함된 경우도 추출해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("A2B-456")
                .shouldBe("A2B-456")
        }

        @Test
        fun `하이픈 뒤에 0으로 시작하는 키는 추출하지 않아야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("PAY-0123")
                .shouldBeNull()
        }

        @Test
        fun `하이픈만 있는 경우 추출하지 않아야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("PAY-")
                .shouldBeNull()
        }

        @Test
        fun `숫자만 있는 경우 추출하지 않아야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("-123")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서는 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("")
                .shouldBeNull()
        }

        @Test
        fun `공백만 있는 경우 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("   ")
                .shouldBeNull()
        }

        @Test
        fun `여러 이슈 키가 있으면 첫 번째를 추출해야 한다`() {
            WorkContextEntityExtractor.extractIssueKey("PAY-100과 PAY-200을 비교해줘")
                .shouldBe("PAY-100")
        }
    }

    @Nested
    inner class ExtractProjectKey {

        @Test
        fun `프로젝트 키워드와 함께 있는 프로젝트 키를 추출해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("PAY 프로젝트 상태를 알려줘")
                .shouldBe("PAY")
        }

        @Test
        fun `팀 키워드와 함께 있는 프로젝트 키를 추출해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("CORE 팀 이슈를 보여줘")
                .shouldBe("CORE")
        }

        @Test
        fun `릴리즈 키워드와 함께 있는 프로젝트 키를 추출해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("PAY 릴리즈 준비 상태")
                .shouldBe("PAY")
        }

        @Test
        fun `영문 project 키워드와 함께 있는 키를 추출해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("project PAY status")
                .shouldBe("PAY")
        }

        @Test
        fun `프로젝트 키워드 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("오늘 날씨가 좋습니다")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractProjectKey("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractLooseProjectKey {

        @Test
        fun `대문자 단어를 느슨하게 프로젝트 키로 추출해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("PAY 관련 이슈 정리")
                .shouldBe("PAY")
        }

        @Test
        fun `stop word는 제외해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("API 문서를 확인해줘")
                .shouldBeNull()
        }

        @Test
        fun `숫자만으로 이루어진 문자열은 제외해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("12345 상태")
                .shouldBeNull()
        }

        @Test
        fun `12자 초과 문자열은 제외해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("ABCDEFGHIJKLM 이슈")
                .shouldBeNull()
        }

        @Test
        fun `1자 문자열은 제외해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("A 프로젝트")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractLooseProjectKey("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractUrl {

        @Test
        fun `HTTP URL을 추출해야 한다`() {
            WorkContextEntityExtractor.extractUrl("http://example.com/api 확인")
                .shouldBe("http://example.com/api")
        }

        @Test
        fun `HTTPS URL을 추출해야 한다`() {
            WorkContextEntityExtractor.extractUrl("https://api.example.com/v1/docs 분석")
                .shouldBe("https://api.example.com/v1/docs")
        }

        @Test
        fun `URL이 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractUrl("그냥 텍스트입니다")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractUrl("")
                .shouldBeNull()
        }

        @Test
        fun `쿼리 파라미터가 포함된 URL을 추출해야 한다`() {
            val url = "https://example.com/api?key=val&foo=bar"
            WorkContextEntityExtractor.extractUrl(url)
                .shouldBe(url)
        }
    }

    @Nested
    inner class ExtractQuotedKeyword {

        @Test
        fun `작은따옴표로 감싼 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractQuotedKeyword("'payment-service' 정보를 알려줘")
                .shouldBe("payment-service")
        }

        @Test
        fun `큰따옴표로 감싼 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractQuotedKeyword("\"billing-api\" 스펙을 보여줘")
                .shouldBe("billing-api")
        }

        @Test
        fun `1자 따옴표 내용은 추출하지 않아야 한다`() {
            WorkContextEntityExtractor.extractQuotedKeyword("'x' 만")
                .shouldBeNull()
        }

        @Test
        fun `따옴표 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractQuotedKeyword("따옴표 없는 문장")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractQuotedKeyword("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractServiceName {

        @Test
        fun `한글 서비스 키워드로 서비스명을 추출해야 한다`() {
            WorkContextEntityExtractor.extractServiceName("payment 서비스 상태")
                .shouldBe("payment")
        }

        @Test
        fun `영문 service 키워드로 서비스명을 추출해야 한다`() {
            WorkContextEntityExtractor.extractServiceName("billing service status")
                .shouldBe("billing")
        }

        @Test
        fun `하이픈이 포함된 서비스명을 추출해야 한다`() {
            WorkContextEntityExtractor.extractServiceName("payment-gateway 서비스 장애")
                .shouldBe("payment-gateway")
        }

        @Test
        fun `서비스 키워드 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractServiceName("오늘 배포합니다")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractServiceName("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractRepository {

        @Test
        fun `workspace-repo 형식을 추출해야 한다`() {
            val result = WorkContextEntityExtractor.extractRepository("myteam/my-repo 저장소 확인")
            result.shouldNotBeNull()
            result.first shouldBe "myteam"
            result.second shouldBe "my-repo"
        }

        @Test
        fun `레포지토리 패턴이 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractRepository("저장소 확인해줘")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractRepository("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractRepositorySlug {

        @Test
        fun `저장소 키워드와 함께 slug를 추출해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("my-repo 저장소 확인해줘")
                .shouldBe("my-repo")
        }

        @Test
        fun `레포 키워드와 함께 slug를 추출해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("web-labs 레포의 열린 PR")
                .shouldBe("web-labs")
        }

        @Test
        fun `리포지토리 키워드와 함께 slug를 추출해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("web-labs 리포지토리 확인")
                .shouldBe("web-labs")
        }

        @Test
        fun `리포 키워드와 함께 slug를 추출해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("payment-api 리포 현황")
                .shouldBe("payment-api")
        }

        @Test
        fun `레포지토리 키워드와 함께 slug를 추출해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("core-service 레포지토리 브랜치")
                .shouldBe("core-service")
        }

        @Test
        fun `저장소 키워드 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractRepositorySlug("repo 확인")
                .shouldBeNull()
        }
    }

    @Nested
    inner class StripEmoji {

        @Test
        fun `이모지를 제거해야 한다`() {
            val result = WorkContextEntityExtractor.stripEmoji("안녕하세요 \uD83D\uDE00 테스트")
            result shouldBe "안녕하세요 테스트"
        }

        @Test
        fun `이모지 없으면 원본을 반환해야 한다`() {
            WorkContextEntityExtractor.stripEmoji("plain text")
                .shouldBe("plain text")
        }

        @Test
        fun `연속 공백을 단일 공백으로 줄여야 한다`() {
            WorkContextEntityExtractor.stripEmoji("hello   world")
                .shouldBe("hello world")
        }

        @Test
        fun `빈 문자열은 빈 문자열을 반환해야 한다`() {
            WorkContextEntityExtractor.stripEmoji("")
                .shouldBe("")
        }

        @Test
        fun `앞뒤 공백을 trim해야 한다`() {
            WorkContextEntityExtractor.stripEmoji("  hello  ")
                .shouldBe("hello")
        }
    }

    @Nested
    inner class IsPersonalPrompt {

        @Test
        fun `내가 포함된 프롬프트는 개인화로 판별해야 한다`() {
            WorkContextEntityExtractor.isPersonalPrompt("내가 담당한 이슈를 알려줘")
                .shouldBe(true)
        }

        @Test
        fun `내 기준 포함된 프롬프트는 개인화로 판별해야 한다`() {
            WorkContextEntityExtractor.isPersonalPrompt("내 기준으로 오늘 할 일")
                .shouldBe(true)
        }

        @Test
        fun `개인 키워드 없으면 false를 반환해야 한다`() {
            WorkContextEntityExtractor.isPersonalPrompt("프로젝트 상태를 알려줘")
                .shouldBe(false)
        }

        @Test
        fun `빈 문자열은 false를 반환해야 한다`() {
            WorkContextEntityExtractor.isPersonalPrompt("")
                .shouldBe(false)
        }
    }

    @Nested
    inner class ExtractOwnershipKeyword {

        @Test
        fun `레포지토리에서 소유 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("myteam/billing-api 관련 owner")
                .shouldBe("billing-api")
        }

        @Test
        fun `저장소 slug에서 소유 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("my-repo 저장소 owner")
                .shouldBe("my-repo")
        }

        @Test
        fun `서비스명에서 소유 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("payment 서비스 담당자")
                .shouldBe("payment")
        }

        @Test
        fun `폴백 키워드를 추출해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("release note 담당자")
                .shouldBe("release note")
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("")
                .shouldBeNull()
        }

        @Test
        fun `공백만 있으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractOwnershipKeyword("   ")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractSwaggerSpecName {

        @Test
        fun `따옴표로 감싼 스펙 이름을 추출해야 한다`() {
            WorkContextEntityExtractor.extractSwaggerSpecName("'payment-api' swagger 분석해줘")
                .shouldBe("payment-api")
        }

        @Test
        fun `하이픈 포함 스펙 이름을 추출해야 한다`() {
            WorkContextEntityExtractor.extractSwaggerSpecName("billing-service swagger 분석")
                .shouldBe("billing-service")
        }

        @Test
        fun `stop word만 있으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractSwaggerSpecName("swagger spec 보여줘")
                .shouldBeNull()
        }

        @Test
        fun `URL 형태는 스펙 이름으로 추출하지 않아야 한다`() {
            WorkContextEntityExtractor.extractSwaggerSpecName("'http://example.com' spec")
                .shouldBeNull()
        }

        @Test
        fun `빈 문자열에서 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractSwaggerSpecName("")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ExtractSearchKeyword {

        @Test
        fun `따옴표 키워드를 우선 추출해야 한다`() {
            WorkContextEntityExtractor.extractSearchKeyword("'billing-api' 키워드로 검색")
                .shouldBe("billing-api")
        }

        @Test
        fun `키워드 패턴으로 추출해야 한다`() {
            WorkContextEntityExtractor.extractSearchKeyword("payment 키워드 검색")
                .shouldBe("payment")
        }

        @Test
        fun `키워드 없으면 null을 반환해야 한다`() {
            WorkContextEntityExtractor.extractSearchKeyword("그냥 검색해줘")
                .shouldBeNull()
        }
    }

    @Nested
    inner class ParsePrompt {

        @Test
        fun `이슈 키와 프로젝트 키를 포함한 프롬프트를 파싱해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("PAY-123 이슈 PAY 프로젝트 상태 알려줘")

            parsed.issueKey shouldBe "PAY-123"
            parsed.projectKey shouldBe "PAY"
            parsed.normalized.shouldContain("pay-123")
        }

        @Test
        fun `개인화 프롬프트를 올바르게 판별해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("내가 담당한 PAY-100 상태")

            parsed.isPersonal shouldBe true
            parsed.issueKey shouldBe "PAY-100"
        }

        @Test
        fun `URL 포함 프롬프트에서 specUrl을 추출해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt(
                "https://api.example.com/v1/spec.json swagger 분석해줘"
            )

            parsed.specUrl.shouldNotBeNull()
            parsed.specUrl!! shouldContain "example.com"
        }

        @Test
        fun `빈 프롬프트를 안전하게 파싱해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("")

            parsed.issueKey.shouldBeNull()
            parsed.projectKey.shouldBeNull()
            parsed.serviceName.shouldBeNull()
            parsed.isPersonal shouldBe false
        }

        @Test
        fun `inferredProjectKey는 projectKey가 없을 때 loose 추출을 사용해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("CORE 관련 작업 목록")

            parsed.projectKey.shouldBeNull()
            parsed.inferredProjectKey shouldBe "CORE"
        }

        @Test
        fun `projectKey가 있으면 inferredProjectKey와 동일해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("PAY 프로젝트 상태")

            parsed.projectKey shouldBe "PAY"
            parsed.inferredProjectKey shouldBe "PAY"
        }

        @Test
        fun `특수문자가 포함된 프롬프트를 안전하게 파싱해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("!@#\$%^&*() 테스트")

            parsed.issueKey.shouldBeNull()
        }

        @Test
        fun `한국어 레포 키워드에서 repositorySlug를 추출해야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("web-labs 레포의 열린 PR")

            parsed.repository.shouldBeNull()
            parsed.repositorySlug shouldBe "web-labs"
        }

        @Test
        fun `workspace-repo 형식이면 repositorySlug도 설정되어야 한다`() {
            val parsed = WorkContextEntityExtractor.parsePrompt("acme/payments 저장소 확인")

            parsed.repository.shouldNotBeNull()
            parsed.repositorySlug shouldBe "payments"
        }
    }
}
