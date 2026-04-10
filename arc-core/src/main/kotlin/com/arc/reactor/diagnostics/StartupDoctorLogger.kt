package com.arc.reactor.diagnostics

import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

private val logger = KotlinLogging.logger {}

/**
 * R243: 애플리케이션 기동 직후 [DoctorDiagnostics]를 실행하여 결과를 로그에 출력하는 러너.
 *
 * Arc Reactor가 기동되자마자 `arc.reactor.*` opt-in 기능들(R225~R238)이 현재 어떻게
 * 활성화되어 있는지 한 번에 확인할 수 있다. 배포 직후 "PII 마스킹이 적용 안 된 것 같은데?"
 * 같은 질문에 로그 한 줄로 답할 수 있다.
 *
 * ## 동작 방식
 *
 * 1. 기동 완료 시점([ApplicationRunner.run])에 [DoctorDiagnostics.runDiagnostics] 호출
 * 2. [DoctorReport.toHumanReadable]로 멀티라인 한국어 텍스트 생성 (R239)
 * 3. 멀티라인 텍스트를 단일 `logger.info` 호출로 출력 (개행 포함)
 * 4. WARN/ERROR가 하나라도 있으면 `logger.warn`으로 overall 상태 라벨 추가 경고
 *
 * ## 실패 처리 (fail-open)
 *
 * 진단 실행 중 예외가 발생해도 애플리케이션 기동을 차단하지 않는다. 예외는 `logger.warn`
 * 로만 기록된다. 이는 관측 레이어가 에이전트 실행 경로를 방해하면 안 된다는 원칙에
 * 따른 것이다.
 *
 * ## Opt-in 활성화
 *
 * 기본값은 비활성(`false`)이다. `application.yml`에 다음 속성을 추가하면 활성화된다:
 *
 * ```yaml
 * arc:
 *   reactor:
 *     diagnostics:
 *       startup-log:
 *         enabled: true
 *         include-details: true    # 섹션별 개별 check 상세 포함 여부
 *         warn-on-issues: true     # WARN/ERROR 감지 시 추가 경고 라인
 * ```
 *
 * ## 설정 예시별 출력
 *
 * **활성 + 상세 포함 + 정상**:
 * ```
 * INFO  c.a.r.d.StartupDoctorLogger - Arc Reactor 기동 진단 결과:
 * === Arc Reactor Doctor Report ===
 * 생성 시각: 2026-04-11T14:30:00Z
 * 요약: 5 섹션 — OK 3, SKIPPED 2
 * 전체 상태: 정상
 *
 * [OK] Approval Context Resolver
 *      활성 (RedactedApprovalContextResolver)
 *      [OK] resolver bean: 등록됨: RedactedApprovalContextResolver
 *      ...
 * ```
 *
 * **활성 + WARN 포함**:
 * ```
 * INFO  c.a.r.d.StartupDoctorLogger - Arc Reactor 기동 진단 결과: [본문 생략]
 * WARN  c.a.r.d.StartupDoctorLogger - Arc Reactor 진단에 경고/오류 감지 — 전체 상태: 경고 포함
 * ```
 *
 * ## 3대 최상위 제약
 *
 * - **MCP 호환성**: atlassian-mcp-server 경로 전혀 미접근 — `DoctorDiagnostics` 읽기 전용
 * - **Redis 캐시**: `systemPrompt`, `CacheKeyBuilder` 미수정 → scopeFingerprint 불변
 * - **컨텍스트 관리**: `MemoryStore`, `Trimmer`, 세션 경로 미접근
 *
 * @param doctor R236 진단 서비스
 * @param includeDetails `toHumanReadable` 호출 시 개별 check 상세 포함 여부
 * @param warnOnIssues WARN/ERROR 감지 시 추가 `logger.warn` 라인 출력 여부
 *
 * @see DoctorDiagnostics
 * @see DoctorReport.toHumanReadable R239 포맷터
 */
class StartupDoctorLogger(
    private val doctor: DoctorDiagnostics,
    private val includeDetails: Boolean = true,
    private val warnOnIssues: Boolean = true
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val report = try {
            doctor.runDiagnostics()
        } catch (e: Exception) {
            logger.warn(e) { "기동 진단 실행 실패 — 서비스 시작을 차단하지 않음" }
            return
        }

        // 멀티라인 텍스트를 한 번에 출력 (KotlinLogging은 개행을 그대로 전달)
        val text = report.toHumanReadable(includeDetails = includeDetails)
        logger.info { "Arc Reactor 기동 진단 결과:\n$text" }

        if (warnOnIssues && report.hasWarningsOrErrors()) {
            logger.warn {
                "Arc Reactor 진단에 경고/오류 감지 — 전체 상태: ${report.overallStatusLabel()}"
            }
        }
    }
}
