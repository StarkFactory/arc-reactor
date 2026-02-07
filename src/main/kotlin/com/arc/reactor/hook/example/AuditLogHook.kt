package com.arc.reactor.hook.example

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 감사 로그 Hook (예시) — AfterAgentCompleteHook 구현
 *
 * 에이전트 실행이 완료될 때마다(성공/실패 모두) 감사 로그를 기록합니다.
 * 실제 프로젝트에서는 DB 저장, 외부 로그 시스템 전송 등으로 확장할 수 있습니다.
 *
 * ## Hook 실행 시점
 * ```
 * BeforeAgentStart → [Agent Loop] → AfterAgentComplete ← 여기서 실행
 * ```
 *
 * ## 활용 사례
 * - 사용자별 사용 이력 추적
 * - 도구 호출 패턴 분석
 * - 에러 모니터링 및 알림
 * - 과금/빌링 데이터 수집
 *
 * ## 활성화 방법
 * 이 클래스에 @Component를 추가하면 자동 등록됩니다.
 *
 * ## Spring DI 활용 예시
 * ```kotlin
 * @Component
 * class AuditLogHook(
 *     private val auditRepository: AuditRepository  // DB 저장
 * ) : AfterAgentCompleteHook {
 *     // ...
 * }
 * ```
 */
// @Component  ← 주석 해제하면 자동 등록
class AuditLogHook : AfterAgentCompleteHook {

    // 100-199: 표준 Hook 범위
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info {
            "AUDIT | runId=${context.runId} " +
                "userId=${context.userId} " +
                "success=${response.success} " +
                "tools=${response.toolsUsed} " +
                "duration=${context.durationMs()}ms " +
                "prompt=\"${context.userPrompt.take(100)}\""
        }

        // 실제 프로젝트에서는 여기에 DB 저장, 외부 시스템 전송 등을 추가합니다:
        // auditRepository.save(AuditLog(
        //     runId = context.runId,
        //     userId = context.userId,
        //     success = response.success,
        //     toolsUsed = response.toolsUsed,
        //     durationMs = context.durationMs(),
        //     prompt = context.userPrompt
        // ))
    }
}
