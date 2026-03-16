package com.arc.reactor.feedback

import java.time.Instant
import java.util.UUID

/**
 * 피드백 평점 — 좋아요(thumbs up) 또는 싫어요(thumbs down).
 *
 * WHY: 이진 평점 방식은 사용자 인지 부하를 최소화하면서도
 * 프롬프트 품질 개선에 충분한 신호를 제공한다.
 * PromptLab의 자동 최적화 파이프라인이 이 신호를 기반으로 동작한다.
 *
 * @see com.arc.reactor.promptlab.analysis.FeedbackAnalyzer 부정 피드백 분석
 */
enum class FeedbackRating {
    THUMBS_UP, THUMBS_DOWN
}

/**
 * 에이전트 응답에 대한 사용자 피드백.
 *
 * eval-testing `schemas/feedback.schema.json` 데이터 계약을 준수한다.
 * 평점/코멘트 외에도 오프라인 평가를 위한 실행 메타데이터를 캡처한다.
 *
 * WHY: 피드백 데이터는 PromptLab A/B 테스트의 입력 데이터로 활용된다.
 * 부정 피드백이 일정 수 이상 쌓이면 자동 프롬프트 최적화 파이프라인이 트리거된다.
 *
 * @param feedbackId 고유 식별자 (UUID)
 * @param query 사용자의 원래 프롬프트
 * @param response 에이전트 응답 (JSON 문자열)
 * @param rating 좋아요 또는 싫어요
 * @param timestamp 피드백 제출 시각
 * @param comment 선택적 자유 텍스트 코멘트
 * @param sessionId 대화 세션 ID
 * @param runId 에이전트 실행 런 ID
 * @param userId 피드백을 제출한 사용자
 * @param intent 쿼리의 분류된 인텐트
 * @param domain 비즈니스 도메인 (예: "order", "refund")
 * @param model 응답에 사용된 LLM 모델
 * @param promptVersion 프롬프트 템플릿 버전 번호
 * @param toolsUsed 실행 중 호출된 도구 목록
 * @param durationMs 총 실행 소요 시간 (밀리초)
 * @param tags 필터링을 위한 임의 태그
 * @param templateId 응답에 사용된 프롬프트 템플릿 ID
 * @see FeedbackStore 피드백 저장소 인터페이스
 */
data class Feedback(
    val feedbackId: String = UUID.randomUUID().toString(),
    val query: String,
    val response: String,
    val rating: FeedbackRating,
    val timestamp: Instant = Instant.now(),
    val comment: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val userId: String? = null,
    val intent: String? = null,
    val domain: String? = null,
    val model: String? = null,
    val promptVersion: Int? = null,
    val toolsUsed: List<String>? = null,
    val durationMs: Long? = null,
    val tags: List<String>? = null,
    val templateId: String? = null
)
