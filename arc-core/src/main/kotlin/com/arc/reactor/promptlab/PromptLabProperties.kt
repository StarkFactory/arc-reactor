package com.arc.reactor.promptlab

/**
 * Prompt Lab 설정 프로퍼티.
 *
 * [AgentProperties]를 통해 `arc.reactor.prompt-lab.*`에 바인딩된다.
 *
 * WHY: PromptLab의 동작을 설정으로 제어하여 실험 규모, 비용, 자동화를 조절한다.
 */
data class PromptLabProperties(
    /** Prompt Lab 기능 활성화 */
    val enabled: Boolean = false,

    /** 최대 동시 실험 수 */
    val maxConcurrentExperiments: Int = 3,

    /** 실험당 최대 테스트 쿼리 수 */
    val maxQueriesPerExperiment: Int = 100,

    /** 실험당 최대 프롬프트 버전 수 */
    val maxVersionsPerExperiment: Int = 10,

    /** 버전-쿼리 쌍당 최대 반복 횟수 */
    val maxRepetitions: Int = 5,

    /** 기본 LLM 심판 모델 (null = 실험 모델과 동일) */
    val defaultJudgeModel: String? = null,

    /** LLM 심판 평가용 기본 토큰 예산 */
    val defaultJudgeBudgetTokens: Int = 100_000,

    /** 실험 실행 타임아웃 (밀리초) */
    val experimentTimeoutMs: Long = 600_000,

    /** 자동 생성할 후보 프롬프트 수 */
    val candidateCount: Int = 3,

    /** 자동 파이프라인 트리거를 위한 최소 부정 피드백 수 */
    val minNegativeFeedback: Int = 5,

    /** 크론 스케줄 설정 */
    val schedule: ScheduleProperties = ScheduleProperties()
)

/** 자동 프롬프트 최적화를 위한 크론 스케줄 설정 */
data class ScheduleProperties(
    /** 스케줄 자동 최적화 활성화 */
    val enabled: Boolean = false,

    /** 크론 표현식 (기본값: 매일 오전 2시) */
    val cron: String = "0 0 2 * * *",

    /** 대상 템플릿 ID (빈 목록 = 모든 템플릿) */
    val templateIds: List<String> = emptyList()
)
