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
        val hasToolIntent = TOOL_INTENT_PATTERN.containsMatchIn(query)
        // 도메인 엔티티와 도구 의도가 모두 없는 일반 지식 질문 → STANDARD
        if (!hasToolIntent &&
            KNOWLEDGE_QUESTION_PATTERN.containsMatchIn(query) &&
            !DOMAIN_ENTITY_PATTERN.containsMatchIn(query)
        ) {
            return true
        }
        if (query.length < SHORT_QUERY_THRESHOLD &&
            !hasToolIntent && !isMultiStepQuery(query)
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

        /** 인사/잡담/일반 지식 질문 패턴 (한국어 + 영어) */
        internal val GREETING_PATTERN = Regex(
            "^(안녕|하이|헬로|반가|감사합니다|고마워|ㅎㅇ|ㅎㅎ|" +
                "hi|hello|hey|thanks|thank you|good morning|good afternoon)\\s*[.!?]?$",
            RegexOption.IGNORE_CASE
        )

        /** 도구 없이 답변 가능한 일반 지식 질문 패턴 */
        internal val KNOWLEDGE_QUESTION_PATTERN = Regex(
            "어떻게\\s*(작동|동작)|무엇인가|뭐야\\??|" +
                "차이(가|점|는)|설명해\\s*줘?$|" +
                "what\\s+is|how\\s+does|difference\\s+between|" +
                "explain\\b|compare\\b|describe\\b",
            RegexOption.IGNORE_CASE
        )

        /** 도구 사용 의도를 나타내는 키워드 패턴 */
        internal val TOOL_INTENT_PATTERN = Regex(
            // 동작 키워드
            "검색|조회|찾아|분석|생성|만들어|변환|계산|실행|" +
                "업로드|다운로드|전송|삭제|수정|업데이트|" +
                "보여줘|알려줘|해줘|가져와|확인해|요약해|정리해|" +
                "전환해|남겨줘|읽어|열어|닫아|" +
                // 도메인 엔티티 키워드 (Jira/Confluence/Bitbucket/Swagger)
                "이슈|스프린트|백로그|칸반|에픽|스토리|" +
                "PR\\b|풀\\s*리퀘|커밋|브랜치|저장소|레포|머지|리뷰|" +
                "페이지|스페이스|컨플루언스|위키|문서|" +
                "스펙|swagger|openapi|엔드포인트|API\\b|" +
                "jira|confluence|bitbucket|" +
                "[A-Z]{2,10}-\\d+|" +  // Jira 이슈 키 (JAR-36, DEV-128)
                // 영어 동작 키워드
                "search|find|create|generate|analyze|convert|" +
                "calculate|run|execute|upload|download|send|delete|update|" +
                "show|list|get|fetch|summarize|review",
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
                "한번에|함께|같이|포함해서|포함해줘|겸해서|" +
                "이랑|와\\s|과\\s|" +
                "and then|after that|next|additionally|also\\b|" +
                "along with|together with|including",
            RegexOption.IGNORE_CASE
        )

        /** 도메인 엔티티 패턴 (도구가 필요한 대상) */
        internal val DOMAIN_ENTITY_PATTERN = Regex(
            "이슈|스프린트|PR\\b|커밋|브랜치|저장소|페이지|스펙|" +
                "jira|confluence|bitbucket|swagger|[A-Z]{2,10}-\\d+",
            RegexOption.IGNORE_CASE
        )

        /** 번호 목록 패턴 (최소 2개 항목) */
        internal val NUMBERED_LIST_PATTERN = Regex(
            "(^|\\n)\\s*1[.)]\\s*.+(\\n)\\s*2[.)]\\s*",
            RegexOption.MULTILINE
        )
    }
}
