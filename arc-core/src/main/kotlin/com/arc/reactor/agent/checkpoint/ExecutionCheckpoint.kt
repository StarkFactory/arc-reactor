package com.arc.reactor.agent.checkpoint

import java.time.Instant

/**
 * ReAct 루프 실행 체크포인트.
 *
 * 매 ReAct 단계 후 중간 상태를 스냅샷으로 저장하여,
 * 장애 복구 또는 디버깅에 활용할 수 있다.
 *
 * @property runId 실행 식별자
 * @property step 현재 ReAct 단계 번호 (1부터 시작)
 * @property messages 직렬화된 메시지 목록
 * @property toolCalls 도구 호출 이력
 * @property tokensUsed 누적 토큰 사용량
 * @property createdAt 체크포인트 생성 시각
 */
data class ExecutionCheckpoint(
    val runId: String,
    val step: Int,
    val messages: List<String>,
    val toolCalls: List<String>,
    val tokensUsed: Int,
    val createdAt: Instant = Instant.now()
)
