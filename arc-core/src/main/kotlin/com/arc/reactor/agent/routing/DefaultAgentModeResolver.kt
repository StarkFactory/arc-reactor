package com.arc.reactor.agent.routing

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 키워드/휴리스틱 기반 자동 모드 선택기.
 *
 * LLM 호출 없이 사용자 쿼리의 구조와 키워드를 분석하여
 * 최적의 [AgentMode]를 결정한다.
 *
 * ## 판단 기준
 * - **STANDARD**: 도구가 없거나, 인사/잡담/짧은 질문
 * - **REACT**: 단일 의도 쿼리 (도구 1-2개 관련)
 * - **PLAN_EXECUTE**: 복합/멀티스텝 쿼리 (순서 키워드, 복수 의도)
 *
 * @see AgentModeResolver 인터페이스 정의
 * @see AgentMode 실행 모드 열거형
 */
class DefaultAgentModeResolver : AgentModeResolver {

    override suspend fun resolve(
        command: AgentCommand,
        availableTools: List<String>
    ): AgentMode {
        val query = command.userPrompt.trim()

        if (availableTools.isEmpty() || command.maxToolCalls <= 0) {
            return logAndReturn(AgentMode.STANDARD, query, "도구 없음")
        }
        if (isSimpleQuery(query)) {
            return logAndReturn(AgentMode.STANDARD, query, "단순 질문")
        }
        if (isMultiStepQuery(query)) {
            return logAndReturn(AgentMode.PLAN_EXECUTE, query, "멀티스텝 감지")
        }
        return logAndReturn(AgentMode.REACT, query, "기본 단일 도구 모드")
    }

    /**
     * 인사, 잡담, 짧은 단순 질문인지 판단한다.
     *
     * 짧은 입력(40자 미만)이면서 도구 관련 키워드와
     * 멀티스텝 키워드가 모두 없으면 단순 질문으로 간주.
     */
    internal fun isSimpleQuery(query: String): Boolean {
        if (query.isEmpty()) return true
        if (GREETING_PATTERN.containsMatchIn(query)) return true
        if (query.length < SHORT_QUERY_THRESHOLD &&
            !TOOL_INTENT_PATTERN.containsMatchIn(query) &&
            !isMultiStepQuery(query)
        ) {
            return true
        }
        return false
    }

    /**
     * 멀티스텝/복합 쿼리인지 판단한다.
     *
     * 순서 키워드, 복합 문장 연결어, 또는 번호 목록이 감지되면 멀티스텝으로 간주.
     */
    internal fun isMultiStepQuery(query: String): Boolean {
        if (SEQUENCE_PATTERN.containsMatchIn(query)) return true
        val connectorCount = COMPOUND_CONNECTOR_PATTERN.findAll(query).count()
        if (connectorCount >= MIN_COMPOUND_CONNECTORS) return true
        if (NUMBERED_LIST_PATTERN.containsMatchIn(query)) return true
        return false
    }

    /** 결정 사유를 로깅하고 모드를 반환한다. */
    private fun logAndReturn(
        mode: AgentMode,
        query: String,
        reason: String
    ): AgentMode {
        logger.debug {
            "모드 자동 선택: mode=$mode, reason=$reason, " +
                "query=${query.take(MAX_LOG_QUERY_LENGTH)}"
        }
        return mode
    }

    companion object {
        /** 단순 질문으로 판단하는 쿼리 길이 임계값 (문자 수) */
        internal const val SHORT_QUERY_THRESHOLD = 40

        /** 로그에 포함할 쿼리 최대 길이 */
        private const val MAX_LOG_QUERY_LENGTH = 80

        /** 복합 문장으로 판단하는 최소 연결어 수 */
        private const val MIN_COMPOUND_CONNECTORS = 2

        /** 인사/잡담 패턴 (한국어 + 영어) */
        internal val GREETING_PATTERN = Regex(
            "^(안녕|하이|헬로|반가|감사합니다|고마워|ㅎㅇ|ㅎㅎ|" +
                "hi|hello|hey|thanks|thank you|good morning|good afternoon)\\s*[.!?]?$",
            RegexOption.IGNORE_CASE
        )

        /** 도구 사용 의도를 나타내는 키워드 패턴 */
        internal val TOOL_INTENT_PATTERN = Regex(
            "검색|조회|찾아|분석|생성|만들어|변환|계산|실행|" +
                "업로드|다운로드|전송|삭제|수정|업데이트|" +
                "search|find|create|generate|analyze|convert|" +
                "calculate|run|execute|upload|download|send|delete|update",
            RegexOption.IGNORE_CASE
        )

        /**
         * 순서/단계를 나타내는 키워드 패턴.
         * "먼저...그다음", "1단계...2단계", "순서대로" 등.
         */
        internal val SEQUENCE_PATTERN = Regex(
            "먼저.{2,60}(그다음|그리고|이후|다음에)|" +
                "(1단계|2단계|3단계)|" +
                "순서대로|단계별로|차례로|" +
                "step\\s*\\d|first.{2,60}then|" +
                "phase\\s*\\d",
            RegexOption.IGNORE_CASE
        )

        /** 복합 문장 연결어 패턴 */
        internal val COMPOUND_CONNECTOR_PATTERN = Regex(
            "그리고|그다음|다음에|이후에|그런 다음|또한|" +
                "and then|after that|next|additionally|also\\b",
            RegexOption.IGNORE_CASE
        )

        /** 번호 목록 패턴 (최소 2개 항목) */
        internal val NUMBERED_LIST_PATTERN = Regex(
            "(^|\\n)\\s*1[.)]\\s*.+(\\n)\\s*2[.)]\\s*",
            RegexOption.MULTILINE
        )
    }
}
