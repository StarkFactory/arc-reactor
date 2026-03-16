package com.arc.reactor.support

/**
 * 표준 경계 위반 메시지 포매터.
 *
 * 가드 단계에서 입력/출력 경계 조건 위반을 일관된 형식으로 보고한다.
 *
 * WHY: 경계 위반 메시지 형식을 단일 함수로 통일하여
 * 로그 분석과 모니터링에서 일관성을 보장한다.
 *
 * @param violation 위반 종류 (예: "INPUT_TOO_LONG")
 * @param policy 적용된 정책 이름
 * @param limit 설정된 제한값
 * @param actual 실제 측정값
 * @return 형식화된 위반 메시지 문자열
 * @see com.arc.reactor.guard 가드 패키지
 */
fun formatBoundaryViolation(
    violation: String,
    policy: String,
    limit: Int,
    actual: Int
): String = "Boundary violation [$violation]: policy=$policy, limit=$limit, actual=$actual"

/**
 * 가드 입력 검증용 표준 경계 규칙 위반 메시지 포매터.
 *
 * @param rule 위반된 규칙 이름
 * @param actual 실제 측정값
 * @param limit 설정된 제한값
 * @return 형식화된 위반 메시지 문자열
 */
fun formatBoundaryRuleViolation(
    rule: String,
    actual: Int,
    limit: Int
): String = "Boundary violation [$rule]: actual=$actual, limit=$limit"
