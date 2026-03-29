package com.arc.reactor.promptlab

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.promptlab.analysis.FeedbackAnalyzer
import com.arc.reactor.promptlab.analysis.PromptCandidateGenerator
import com.arc.reactor.promptlab.eval.EvaluationPipeline
import com.arc.reactor.promptlab.eval.EvaluationPipelineFactory
import com.arc.reactor.promptlab.hook.ExperimentCaptureHook
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentStatus
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.promptlab.model.TokenUsageSummary
import com.arc.reactor.promptlab.model.Trial
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 실험 실행 엔진.
 *
 * 각 프롬프트 버전을 테스트 쿼리에 대해 실행하고,
 * 결과를 3계층 파이프라인으로 평가하여 보고서를 생성한다.
 *
 * ## A/B 테스트 실험 실행 흐름
 * 1. 실험을 RUNNING 상태로 변경
 * 2. 모든 버전(기준 + 후보) x 쿼리 x 반복에 대해 트라이얼 실행
 * 3. 각 트라이얼: 에이전트 실행 -> 3계층 평가 -> 결과 저장
 * 4. 트라이얼 결과로 비교 보고서 생성
 * 5. 실험을 COMPLETED 상태로 변경
 *
 * ## 자동 최적화 파이프라인 (runAutoPipeline)
 * 1. FeedbackAnalyzer로 부정 피드백 분석 -> 약점 식별
 * 2. PromptCandidateGenerator로 약점을 보완하는 후보 프롬프트 생성
 * 3. 기준 vs 후보 실험 실행 -> 보고서 생성
 *
 * WHY: 프롬프트 변경의 효과를 정량적으로 측정하여
 * 감에 의한 프롬프트 수정 대신 데이터 기반 의사결정을 지원한다.
 *
 * @see com.arc.reactor.promptlab.analysis.FeedbackAnalyzer 피드백 분석기
 * @see com.arc.reactor.promptlab.analysis.PromptCandidateGenerator 후보 프롬프트 생성기
 * @see ReportGenerator 보고서 생성기
 */
class ExperimentOrchestrator(
    private val agentExecutor: AgentExecutor,
    private val promptTemplateStore: PromptTemplateStore,
    private val experimentStore: ExperimentStore,
    private val evaluationPipelineFactory: EvaluationPipelineFactory,
    private val reportGenerator: ReportGenerator,
    private val feedbackAnalyzer: FeedbackAnalyzer,
    private val candidateGenerator: PromptCandidateGenerator,
    private val properties: PromptLabProperties
) {

    /**
     * 실험을 실행한다: 모든 버전 x 쿼리 x 반복 트라이얼을 수행한다.
     *
     * @param experimentId 실행할 실험 ID
     * @return 완료된 실험 (상태: COMPLETED 또는 FAILED)
     * @throws IllegalArgumentException 실험이 존재하지 않는 경우
     */
    suspend fun execute(experimentId: String): Experiment {
        val experiment = experimentStore.get(experimentId)
            ?: throw IllegalArgumentException("Experiment not found: $experimentId")
        require(experiment.status == ExperimentStatus.PENDING) {
            "Experiment must be PENDING, was: ${experiment.status}"
        }

        val running = experiment.copy(
            status = ExperimentStatus.RUNNING,
            startedAt = Instant.now()
        )
        experimentStore.save(running)
        logger.info { "실험 시작: ${experiment.id} (${experiment.name})" }

        return try {
            withTimeout(properties.experimentTimeoutMs) {
                val trials = executeAllTrials(running)
                experimentStore.saveTrials(experimentId, trials)

                val report = reportGenerator.generate(running, trials)
                experimentStore.saveReport(experimentId, report)

                val completed = running.copy(
                    status = ExperimentStatus.COMPLETED,
                    completedAt = Instant.now()
                )
                experimentStore.save(completed)
                logger.info { "실험 완료: $experimentId, trials=${trials.size}" }
                completed
            }
        } catch (e: TimeoutCancellationException) {
            val failed = running.copy(
                status = ExperimentStatus.FAILED,
                errorMessage = "Experiment timed out after ${properties.experimentTimeoutMs}ms",
                completedAt = Instant.now()
            )
            experimentStore.save(failed)
            logger.error { "실험 타임아웃: $experimentId" }
            failed
        } catch (e: Exception) {
            e.throwIfCancellation()
            val failed = running.copy(
                status = ExperimentStatus.FAILED,
                errorMessage = "실험 실행 중 오류 발생 (${e.javaClass.simpleName})",
                completedAt = Instant.now()
            )
            experimentStore.save(failed)
            logger.error { "실험 실패: $experimentId - ${e.message}" }
            failed
        }
    }

    /**
     * 완전 자동화 파이프라인: 피드백 분석 -> 후보 생성 -> 실험 실행.
     *
     * @param templateId 최적화 대상 프롬프트 템플릿 ID
     * @param since 이 시각 이후의 피드백만 분석 (null=전체)
     * @param candidateCount 생성할 후보 수 (null=설정 기본값)
     * @param judgeModel LLM 심판 모델 (null=설정 기본값)
     * @return 완료된 실험, 또는 부정 피드백이 불충분하면 null
     */
    suspend fun runAutoPipeline(
        templateId: String,
        since: Instant? = null,
        candidateCount: Int? = null,
        judgeModel: String? = null
    ): Experiment? {
        logger.info { "자동 파이프라인 시작: template=$templateId" }

        val analysis = feedbackAnalyzer.analyze(templateId, since)
        if (analysis.negativeCount < properties.minNegativeFeedback) {
            logger.info {
                "건너뜀: 부정 피드백 ${analysis.negativeCount}건만 존재 " +
                    "(최소=${properties.minNegativeFeedback})"
            }
            return null
        }

        val candidateIds = candidateGenerator.generate(
            templateId = templateId,
            analysis = analysis,
            candidateCount = candidateCount ?: properties.candidateCount
        )
        if (candidateIds.isEmpty()) {
            logger.warn { "후보 프롬프트 생성 실패: template=$templateId" }
            return null
        }

        val activeVersion = promptTemplateStore.getActiveVersion(templateId)
            ?: throw IllegalStateException("No active version for template=$templateId")

        val experiment = Experiment(
            name = "Auto-optimize: $templateId",
            description = "Auto-generated experiment from ${analysis.negativeCount} negative feedback",
            templateId = templateId,
            baselineVersionId = activeVersion.id,
            candidateVersionIds = candidateIds,
            testQueries = analysis.sampleQueries,
            judgeModel = judgeModel,
            autoGenerated = true,
            createdAt = Instant.now()
        )
        experimentStore.save(experiment)
        return execute(experiment.id)
    }

    private suspend fun executeAllTrials(
        experiment: Experiment
    ): List<Trial> {
        val allVersionIds = listOf(experiment.baselineVersionId) +
            experiment.candidateVersionIds
        val pipeline = evaluationPipelineFactory.create(
            experiment.evaluationConfig
        )
        val trials = mutableListOf<Trial>()
        for (versionId in allVersionIds) {
            val version = promptTemplateStore.getVersion(versionId)
            if (version == null) {
                logger.warn { "버전을 찾을 수 없어 건너뜀: $versionId" }
                continue
            }
            executeVersionTrials(
                experiment, version, pipeline, trials
            )
        }
        return trials
    }

    private suspend fun executeVersionTrials(
        experiment: Experiment,
        version: com.arc.reactor.prompt.PromptVersion,
        pipeline: EvaluationPipeline,
        trials: MutableList<Trial>
    ) {
        for (query in experiment.testQueries) {
            for (rep in 0 until experiment.repetitions) {
                val trial = executeSingleTrial(
                    experiment, version.id, version.version,
                    version.content, query, rep, pipeline
                )
                trials.add(trial)
            }
        }
    }

    private suspend fun executeSingleTrial(
        experiment: Experiment,
        versionId: String,
        versionNumber: Int,
        systemPrompt: String,
        query: TestQuery,
        repetitionIndex: Int,
        pipeline: EvaluationPipeline
    ): Trial {
        val runId = UUID.randomUUID().toString()
        val command = AgentCommand(
            systemPrompt = systemPrompt,
            userPrompt = query.query,
            model = experiment.model,
            temperature = experiment.temperature,
            metadata = mapOf(
                ExperimentCaptureHook.EXPERIMENT_ID_KEY to experiment.id,
                ExperimentCaptureHook.VERSION_ID_KEY to versionId,
                ExperimentCaptureHook.RUN_ID_KEY to runId
            )
        )

        return try {
            val result = agentExecutor.execute(command)
            val evaluations = pipeline.evaluate(
                result.content.orEmpty(),
                query
            )
            buildTrial(
                experiment, versionId, versionNumber, query,
                repetitionIndex, result, evaluations
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "트라이얼 실패: version=$versionId, query=${query.query}: ${e.message}" }
            Trial(
                experimentId = experiment.id,
                promptVersionId = versionId,
                promptVersionNumber = versionNumber,
                testQuery = query,
                repetitionIndex = repetitionIndex,
                success = false,
                errorMessage = "시도 실행 중 오류 발생 (${e.javaClass.simpleName})",
                executedAt = Instant.now()
            )
        }
    }

    private fun buildTrial(
        experiment: Experiment,
        versionId: String,
        versionNumber: Int,
        query: TestQuery,
        repetitionIndex: Int,
        result: AgentResult,
        evaluations: List<com.arc.reactor.promptlab.model.EvaluationResult>
    ): Trial {
        val tokenSummary = result.tokenUsage?.let {
            TokenUsageSummary(it.promptTokens, it.completionTokens, it.totalTokens)
        }
        return Trial(
            experimentId = experiment.id,
            promptVersionId = versionId,
            promptVersionNumber = versionNumber,
            testQuery = query,
            repetitionIndex = repetitionIndex,
            response = result.content,
            success = result.success,
            errorMessage = result.errorMessage,
            toolsUsed = result.toolsUsed,
            tokenUsage = tokenSummary,
            durationMs = result.durationMs,
            evaluations = evaluations,
            executedAt = Instant.now()
        )
    }
}
