package com.arc.reactor.guard

/**
 * 개인식별정보(PII) 정규식 패턴 공유 객체
 *
 * 입력 Guard(커스텀 GuardStage 구현)와
 * 출력 마스킹([com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard]) 양쪽에서
 * 공통으로 사용하는 PII 패턴을 정의한다.
 *
 * 왜 한 곳에 모아두는가:
 * - 입력 Guard는 PII가 포함된 요청 자체를 차단하고,
 * - 출력 Guard는 LLM 응답에 포함된 PII를 마스킹한다.
 * - 동일한 패턴을 공유하여 탐지 일관성을 보장한다.
 *
 * 주의: 프로덕션에서는 Presidio 같은 전문 라이브러리와 통합하거나
 * 국가별 PII 패턴을 추가하는 것을 권장한다.
 *
 * @see com.arc.reactor.guard.GuardStage 커스텀 입력 Guard 구현을 위한 인터페이스
 * @see com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard 출력에서의 PII 마스킹
 */
object PiiPatterns {

    /**
     * PII 패턴 데이터 클래스
     *
     * @property name 패턴 이름 (로깅 및 사용자 메시지에 표시)
     * @property regex 탐지용 정규식
     * @property mask 마스킹 시 대체할 문자열
     */
    data class PiiPattern(val name: String, val regex: Regex, val mask: String)

    // ═══════════════════════════════════════════════════
    // 한국 PII 패턴
    // ═══════════════════════════════════════════════════

    /** 한국 PII 패턴 */
    val KR: List<PiiPattern> = listOf(
        // 형식: 6자리(생년월일)-1~4로 시작하는 7자리. 예: 900101-1234567
        PiiPattern(
            name = "주민등록번호",
            regex = Regex("""\d{6}\s?-\s?[1-4]\d{6}"""),
            mask = "******-*******"
        ),
        // 한국 휴대폰 번호: 010, 011, 016, 017, 018, 019로 시작. 예: 010-1234-5678
        PiiPattern(
            name = "전화번호",
            regex = Regex("""01[016789]-?\d{3,4}-?\d{4}"""),
            mask = "***-****-****"
        ),
        // 한국 운전면허번호: 지역코드(2자리)-앞2자리-6자리-2자리. 예: 11-23-123456-78
        PiiPattern(
            name = "운전면허번호",
            regex = Regex("""\d{2}-\d{2}-\d{6}-\d{2}"""),
            mask = "**-**-******-**"
        ),
        // 한국 여권번호: 영문1자리 + 숫자8자리. 예: M12345678
        PiiPattern(
            name = "한국여권번호",
            regex = Regex("""\b[A-Z]\d{8}\b"""),
            mask = "*********"
        )
    )

    // ═══════════════════════════════════════════════════
    // 국제 PII 패턴
    // ═══════════════════════════════════════════════════

    /** 국제 공통 PII 패턴 (구체적 → 범용 순서) */
    val INTL: List<PiiPattern> = listOf(
        // IBAN: 국가코드(2) + 체크(2) + 최대30자리 영숫자. 예: DE89370400440532013000
        // JP My Number보다 먼저 평가해야 내부 숫자 그룹이 선행 치환되지 않는다.
        PiiPattern(
            name = "IBAN",
            regex = Regex("""\b[A-Z]{2}\d{2}\s?[\dA-Z]{4}(?:\s?[\dA-Z]{4}){1,7}(?:\s?[\dA-Z]{1,4})?"""),
            mask = "[IBAN MASKED]"
        ),
        // US Social Security Number: 3-2-4 형식. 예: 123-45-6789
        PiiPattern(
            name = "US SSN",
            regex = Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            mask = "***-**-****"
        ),
        // 일본 마이넘버: 12자리 숫자. 예: 1234 5678 9012
        PiiPattern(
            name = "JP My Number",
            regex = Regex("""\b\d{4}\s\d{4}\s\d{4}\b"""),
            mask = "**** **** ****"
        )
    )

    // ═══════════════════════════════════════════════════
    // 공통 PII 패턴 (국가 무관)
    // ═══════════════════════════════════════════════════

    /** 국가와 무관한 공통 PII 패턴 */
    val COMMON: List<PiiPattern> = listOf(
        // 신용카드번호: 4자리씩 4그룹. 예: 1234-5678-9012-3456
        PiiPattern(
            name = "신용카드번호",
            regex = Regex("""\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}"""),
            mask = "****-****-****-****"
        ),
        // 이메일 주소. 예: user@example.com
        PiiPattern(
            name = "이메일",
            regex = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
            mask = "***@***.***"
        ),
        // IPv4 주소 (로그 등에서 사용자 IP 유출 방지). 예: 192.168.1.100
        PiiPattern(
            name = "IP주소",
            regex = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b"""),
            mask = "***.***.***.***"
        )
    )

    /**
     * 전체 PII 패턴 목록.
     *
     * 한국 + 국제 + 공통 패턴을 모두 포함한다.
     * 순서가 중요: 구체적 패턴(주민등록번호 등)이 범용 패턴(숫자열)보다 먼저 평가되어야
     * false positive를 줄일 수 있다.
     */
    val ALL: List<PiiPattern> = KR + INTL + COMMON
}
