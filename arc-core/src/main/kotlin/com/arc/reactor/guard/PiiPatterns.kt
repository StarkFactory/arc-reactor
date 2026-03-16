package com.arc.reactor.guard

/**
 * 개인식별정보(PII) 정규식 패턴 공유 객체
 *
 * 입력 Guard([com.arc.reactor.guard.example.PiiDetectionGuard])와
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
 * @see com.arc.reactor.guard.example.PiiDetectionGuard 입력 Guard에서의 PII 차단
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

    /**
     * 지원하는 전체 PII 패턴 목록.
     *
     * 현재 한국 주민등록번호, 전화번호, 신용카드번호, 이메일을 탐지한다.
     */
    val ALL: List<PiiPattern> = listOf(
        // ── 주민등록번호 ──
        // 형식: 6자리(생년월일)-1~4로 시작하는 7자리
        // 예: 900101-1234567
        PiiPattern(
            name = "주민등록번호",
            regex = Regex("""\d{6}\s?-\s?[1-4]\d{6}"""),
            mask = "******-*******"
        ),
        // ── 전화번호 ──
        // 한국 휴대폰 번호: 010, 011, 016, 017, 018, 019로 시작
        // 예: 010-1234-5678, 01012345678
        PiiPattern(
            name = "전화번호",
            regex = Regex("""01[016789]-?\d{3,4}-?\d{4}"""),
            mask = "***-****-****"
        ),
        // ── 신용카드번호 ──
        // 4자리씩 4그룹, 하이픈 또는 공백 구분 가능
        // 예: 1234-5678-9012-3456
        PiiPattern(
            name = "신용카드번호",
            regex = Regex("""\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}"""),
            mask = "****-****-****-****"
        ),
        // ── 이메일 ──
        // 표준 이메일 형식
        // 예: user@example.com
        PiiPattern(
            name = "이메일",
            regex = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
            mask = "***@***.***"
        )
    )
}
