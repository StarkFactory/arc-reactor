package com.arc.reactor.support

/**
 * PII(Personally Identifiable Information) 마스킹용 공통 정규식 모음.
 *
 * R228 [com.arc.reactor.approval.RedactedApprovalContextResolver]와
 * R231 [com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer]가
 * 공유하는 기본 패턴을 한 곳에 모은다. R233에서 추출되었다.
 *
 * ## 설계 원칙
 *
 * 1. **Single source of truth**: 두 데코레이터가 동일한 리스트를 참조하여
 *    패턴 drift(한쪽만 업데이트되어 두 리스트가 어긋나는 문제) 방지
 * 2. **Immutability**: `val` + `listOf` → 런타임 변경 불가
 * 3. **Backward compat**: R228/R231의 `DEFAULT_PATTERNS` 상수는 유지하되
 *    내부적으로 이 객체를 참조 → 사용자 코드에 영향 없음
 * 4. **확장 가능**: 각 패턴을 개별 상수로도 노출 → 사용자가 일부만 선택하거나
 *    커스텀 리스트를 조합할 수 있다
 *
 * ## 사용 예
 *
 * ### 전체 기본 리스트 사용
 * ```kotlin
 * val redacted = RedactedApprovalContextResolver(
 *     delegate = myResolver
 *     // additionalPatterns 없이 → PiiPatterns.DEFAULT만 사용
 * )
 * ```
 *
 * ### 개별 패턴 재사용
 * ```kotlin
 * val customList = listOf(
 *     PiiPatterns.EMAIL,
 *     PiiPatterns.ATLASSIAN_GRANULAR,
 *     Regex("""INTERNAL-\d{8}""")  // 사내 추가
 * )
 * val redacted = RedactedApprovalContextResolver(
 *     delegate = myResolver,
 *     additionalPatterns = customList  // DEFAULT를 무시하고 커스텀만
 * )
 * ```
 *
 * ## 패턴 상세
 *
 * | 상수 | 정규식 | 매칭 예 |
 * |------|--------|---------|
 * | [EMAIL] | `[\w.+-]+@[\w-]+(?:\.[\w-]+)+` | `alice@company.com` |
 * | [BEARER_TOKEN] | `Bearer\s+[A-Za-z0-9\-_.=]+` (IGNORE_CASE) | `Bearer eyJ...` |
 * | [ATLASSIAN_GRANULAR] | `ATATT3xFfGF0[A-Za-z0-9\-_=]+` | R220 gotcha granular 토큰 |
 * | [SLACK_TOKEN] | `xox[baprs]-[A-Za-z0-9-]+` | `xoxb-...`, `xoxp-...` |
 * | [KOREAN_PHONE_DOMESTIC] | `01[0-9]-\d{3,4}-\d{4}` | `010-1234-5678` |
 * | [KOREAN_PHONE_INTERNATIONAL] | `\+?82-10-\d{3,4}-\d{4}` | `+82-10-1234-5678` |
 * | [KOREAN_RRN] | `\d{6}-[1-4]\d{6}` | 첫 자리 1-4만 (오탐 최소화) |
 *
 * ## 주민등록번호 오탐 방지 설계
 *
 * [KOREAN_RRN] 정규식은 뒷 7자리의 첫 자리를 `[1-4]`로 제한한다. 이는:
 * - **정당한 주민번호**: 1900년대 남(1), 여(2), 2000년대 남(3), 여(4) — 모두 매칭
 * - **오탐 배제**: 일반 ID 형태 `YYYYMM-NNNNNNN`처럼 첫 자리가 5 이상인 경우는
 *   주민번호가 아닐 확률이 높아 매칭 안 함
 *
 * 이는 완벽하지 않지만 대부분의 일반 ID 오탐을 제거하면서 진짜 주민번호는 포착한다.
 *
 * ## 왜 공통 유틸이 support 패키지에 있는가
 *
 * PII 마스킹은 Approval(`com.arc.reactor.approval`)과 ACI(`com.arc.reactor.tool.summarize`)
 * 양쪽에서 사용된다. 두 패키지 중 한쪽에 두면 의존 방향이 어색해지므로, 중립적
 * `com.arc.reactor.support` 패키지에 배치한다. 다른 support 유틸
 * ([CancellationSupport], [BoundaryViolationSupport])과 같은 위치.
 *
 * @see com.arc.reactor.approval.RedactedApprovalContextResolver R228 Approval 축 사용처
 * @see com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer R231 ACI 축 사용처
 */
object PiiPatterns {

    /** 이메일 — 일반적인 RFC 완전하지는 않지만 실용적. */
    val EMAIL: Regex = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    /** Bearer 토큰 (JWT 포함). `bearer`/`Bearer` 대소문자 무관. */
    val BEARER_TOKEN: Regex = Regex(
        """Bearer\s+[A-Za-z0-9\-_.=]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Atlassian granular API 토큰.
     * R220 `atlassian_api_auth.md` 메모에서 식별된 prefix 기반.
     */
    val ATLASSIAN_GRANULAR: Regex = Regex("""ATATT3xFfGF0[A-Za-z0-9\-_=]+""")

    /** Slack 토큰 — bot/user/app/refresh/service 모든 prefix. */
    val SLACK_TOKEN: Regex = Regex("""xox[baprs]-[A-Za-z0-9-]+""")

    /** 한국 휴대폰 번호 국내 표기 (하이픈 포함). */
    val KOREAN_PHONE_DOMESTIC: Regex = Regex("""01[0-9]-\d{3,4}-\d{4}""")

    /** 한국 휴대폰 번호 국제 표기 (+82-10-xxxx-xxxx). */
    val KOREAN_PHONE_INTERNATIONAL: Regex = Regex("""\+?82-10-\d{3,4}-\d{4}""")

    /** 주민등록번호 (첫 자리 1-4만, 오탐 최소화). */
    val KOREAN_RRN: Regex = Regex("""\d{6}-[1-4]\d{6}""")

    /**
     * 기본 PII 마스킹 패턴 목록 — 위 7개 상수를 적용 순서대로 묶은 것.
     *
     * R228/R231 데코레이터가 `DEFAULT_PATTERNS`로 이 리스트를 참조한다. 이 리스트는
     * 불변이며, 사용자가 "모든 기본 패턴 + 추가 패턴" 조합을 원할 때 내부적으로 병합된다.
     */
    val DEFAULT: List<Regex> = listOf(
        EMAIL,
        BEARER_TOKEN,
        ATLASSIAN_GRANULAR,
        SLACK_TOKEN,
        KOREAN_PHONE_DOMESTIC,
        KOREAN_PHONE_INTERNATIONAL,
        KOREAN_RRN
    )
}
