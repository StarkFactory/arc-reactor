package com.arc.reactor.slack.tools.client

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.canvases.CanvasesCreateRequest
import com.slack.api.methods.request.canvases.CanvasesEditRequest
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest
import com.slack.api.methods.request.conversations.ConversationsListRequest
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest
import com.slack.api.methods.request.files.FilesUploadV2Request
import com.slack.api.methods.request.reactions.ReactionsAddRequest
import com.slack.api.methods.request.search.SearchMessagesRequest
import com.slack.api.methods.request.users.UsersInfoRequest
import com.slack.api.methods.request.users.UsersListRequest
import com.slack.api.model.ConversationType
import com.slack.api.model.canvas.CanvasDocumentChange
import com.slack.api.model.canvas.CanvasDocumentContent
import com.slack.api.model.canvas.CanvasEditOperation
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import mu.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import java.io.IOException
import org.springframework.beans.factory.DisposableBean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Slack Web API 클라이언트.
 *
 * [MethodsClient]를 래핑하여 재시도, 타임아웃, Circuit Breaker, Micrometer 메트릭 기록 등
 * 복원력(resilience) 기능을 투명하게 제공한다.
 *
 * 주요 기능:
 * - 메시지 전송 / 스레드 답장
 * - 채널 목록 조회 및 이름 검색
 * - 대화 이력 및 스레드 답글 읽기
 * - 사용자 정보 조회 및 이름 검색
 * - 메시지 검색
 * - 파일 업로드
 * - Canvas 생성 및 추가
 * - 이모지 리액션 추가
 *
 * @param client Slack SDK [MethodsClient] 인스턴스
 * @param properties Slack 도구 설정 (타임아웃, Circuit Breaker 임계값 등)
 * @param meterRegistry Micrometer 메트릭 레지스트리 (선택)
 * @see SlackToolsProperties
 */
class SlackApiClient(
    private val client: MethodsClient,
    private val properties: SlackToolsProperties,
    private val meterRegistry: MeterRegistry? = null
) : DisposableBean {

    private val circuitStates = ConcurrentHashMap<String, CircuitBreakerState>()

    /** 타임아웃 실행용 스레드 풀. 빈 소멸 시 [destroy]에서 종료된다. */
    private val timeoutExecutor: ExecutorService = Executors.newCachedThreadPool(
        TimeoutWorkerThreadFactory()
    )

    /** 스레드 풀을 정리하여 리소스 누수를 방지한다. */
    override fun destroy() {
        timeoutExecutor.shutdownNow()
        logger.info { "SlackApiClient timeoutExecutor shut down" }
    }

    /**
     * 채널에 메시지를 전송한다. [threadTs]를 지정하면 스레드 답장으로 전송된다.
     *
     * @param channelId 대상 채널 ID
     * @param text 메시지 본문
     * @param threadTs 스레드 타임스탬프 (선택, 지정 시 스레드 답장)
     * @return 전송 결과 (타임스탬프, 채널 ID 포함)
     */
    fun postMessage(channelId: String, text: String, threadTs: String? = null): PostMessageResult {
        return try {
            val request = ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(text)
                .apply { if (threadTs != null) threadTs(threadTs) }
                .build()
            val response = executeWithRetry("chat.postMessage", "channelId=$channelId") {
                client.chatPostMessage(request)
            }
            PostMessageResult(
                ok = response.isOk,
                ts = response.ts,
                channel = response.channel,
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "chat.postMessage failed for channelId=$channelId" }
            val errorDetails = exceptionErrorDetails(e)
            PostMessageResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 메시지에 이모지 리액션을 추가한다.
     *
     * @param channelId 채널 ID
     * @param timestamp 대상 메시지의 타임스탬프
     * @param emoji 이모지 이름 (콜론 없이, 예: thumbsup)
     * @return 성공 여부
     */
    fun addReaction(channelId: String, timestamp: String, emoji: String): SimpleResult {
        return try {
            val request = ReactionsAddRequest.builder()
                .channel(channelId)
                .timestamp(timestamp)
                .name(emoji)
                .build()
            val response = executeWithRetry("reactions.add", "channelId=$channelId ts=$timestamp") {
                client.reactionsAdd(request)
            }
            SimpleResult(
                ok = response.isOk,
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "reactions.add failed for channelId=$channelId timestamp=$timestamp" }
            val errorDetails = exceptionErrorDetails(e)
            SimpleResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 워크스페이스의 채널 목록을 조회한다. 아카이브된 채널은 제외된다.
     *
     * @param limit 페이지당 최대 채널 수 (기본 100)
     * @param cursor 페이지네이션 커서 (다음 페이지 요청 시)
     * @return 채널 목록 및 다음 페이지 커서
     */
    fun conversationsList(limit: Int = 100, cursor: String? = null): ConversationsListResult {
        return try {
            val request = ConversationsListRequest.builder()
                .limit(limit)
                .excludeArchived(true)
                .types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                .apply { if (cursor != null) cursor(cursor) }
                .build()
            val response = executeWithRetry("conversations.list", "limit=$limit") {
                client.conversationsList(request)
            }
            ConversationsListResult(
                ok = response.isOk,
                channels = response.channels?.map { ch ->
                    SlackChannel(
                        id = ch.id,
                        name = ch.name,
                        topic = ch.topic?.value,
                        memberCount = ch.numOfMembers ?: 0,
                        isPrivate = ch.isPrivate
                    )
                } ?: emptyList(),
                nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "conversations.list failed" }
            val errorDetails = exceptionErrorDetails(e)
            ConversationsListResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 이름으로 채널을 검색한다. 전체 채널 목록을 페이지 단위로 스캔하여 일치하는 채널을 반환한다.
     *
     * @param query 검색어 (채널 이름)
     * @param exactMatch true이면 정확히 일치, false이면 부분 일치 (기본)
     * @param limit 최대 반환 건수 (기본 10)
     * @return 일치하는 채널 목록 및 스캔 정보
     */
    fun findChannelsByName(query: String, exactMatch: Boolean = false, limit: Int = 10): FindChannelsResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return FindChannelsResult(
                ok = false,
                query = query,
                exactMatch = exactMatch,
                error = "invalid_query",
                errorDetails = SlackErrorDetails(code = "invalid_query", message = "query is required")
            )
        }

        val result = searchWithPagination(
            fetchPage = { cursor ->
                val r = conversationsList(limit = CHANNEL_PAGE_SIZE, cursor = cursor)
                PageData(r.ok, r.channels, r.nextCursor, r.error, r.errorDetails)
            },
            getId = { it.id },
            matches = { channelNameMatches(it.name, normalizedQuery, exactMatch) },
            limit = limit
        )

        return FindChannelsResult(
            ok = result.ok,
            query = normalizedQuery,
            exactMatch = exactMatch,
            channels = result.items,
            scannedPages = result.scannedPages,
            hasMore = result.hasMore,
            error = result.error,
            errorDetails = result.errorDetails
        )
    }

    /**
     * 채널의 최근 메시지 이력을 조회한다.
     *
     * @param channelId 대상 채널 ID
     * @param limit 최대 메시지 수 (기본 10)
     * @param cursor 페이지네이션 커서
     * @return 메시지 목록 및 다음 페이지 커서
     */
    fun conversationHistory(channelId: String, limit: Int = 10, cursor: String? = null): ConversationHistoryResult {
        return try {
            val request = ConversationsHistoryRequest.builder()
                .channel(channelId)
                .limit(limit)
                .apply { if (cursor != null) cursor(cursor) }
                .build()
            val response = executeWithRetry("conversations.history", "channelId=$channelId") {
                client.conversationsHistory(request)
            }
            ConversationHistoryResult(
                ok = response.isOk,
                messages = response.messages?.map { msg ->
                    SlackMessage(
                        user = msg.user,
                        text = msg.text,
                        ts = msg.ts,
                        threadTs = msg.threadTs
                    )
                } ?: emptyList(),
                nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "conversations.history failed for channelId=$channelId" }
            val errorDetails = exceptionErrorDetails(e)
            ConversationHistoryResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 스레드의 답글 목록을 조회한다. 부모 메시지는 결과에서 제외된다.
     *
     * @param channelId 채널 ID
     * @param threadTs 스레드 부모 메시지의 타임스탬프
     * @param limit 최대 답글 수 (기본 10)
     * @param cursor 페이지네이션 커서
     * @return 답글 목록 및 다음 페이지 커서
     */
    fun threadReplies(channelId: String, threadTs: String, limit: Int = 10, cursor: String? = null): ConversationHistoryResult {
        return try {
            val request = ConversationsRepliesRequest.builder()
                .channel(channelId)
                .ts(threadTs)
                .limit(limit)
                .apply { if (cursor != null) cursor(cursor) }
                .build()
            val response = executeWithRetry("conversations.replies", "channelId=$channelId threadTs=$threadTs") {
                client.conversationsReplies(request)
            }
            ConversationHistoryResult(
                ok = response.isOk,
                messages = response.messages
                    ?.filterNot { it.ts == threadTs }
                    ?.map { msg ->
                        SlackMessage(
                            user = msg.user,
                            text = msg.text,
                            ts = msg.ts,
                            threadTs = msg.threadTs
                        )
                    } ?: emptyList(),
                nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "conversations.replies failed for channelId=$channelId threadTs=$threadTs" }
            val errorDetails = exceptionErrorDetails(e)
            ConversationHistoryResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 사용자 정보를 조회한다 (이름, 이메일, 봇 여부 등).
     *
     * @param userId Slack 사용자 ID
     * @return 사용자 정보 또는 에러
     */
    fun getUserInfo(userId: String): UserInfoResult {
        return try {
            val request = UsersInfoRequest.builder().user(userId).build()
            val response = executeWithRetry("users.info", "userId=$userId") {
                client.usersInfo(request)
            }
            val user = response.user
            UserInfoResult(
                ok = response.isOk,
                user = user?.toSlackUser(),
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "users.info failed for userId=$userId" }
            val errorDetails = exceptionErrorDetails(e)
            UserInfoResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 이름, 표시 이름 또는 실명으로 사용자를 검색한다.
     *
     * @param query 검색어
     * @param exactMatch true이면 정확히 일치, false이면 부분 일치 (기본)
     * @param limit 최대 반환 건수 (기본 10)
     * @return 일치하는 사용자 목록
     */
    fun findUsersByName(query: String, exactMatch: Boolean = false, limit: Int = 10): FindUsersResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return FindUsersResult(
                ok = false,
                query = query,
                exactMatch = exactMatch,
                error = "invalid_query",
                errorDetails = SlackErrorDetails(code = "invalid_query", message = "query is required")
            )
        }

        val result = searchWithPagination(
            fetchPage = { cursor ->
                val r = usersListPage(limit = USERS_PAGE_SIZE, cursor = cursor)
                PageData(r.ok, r.users, r.nextCursor, r.error, r.errorDetails)
            },
            getId = { it.id },
            matches = { userNameMatches(it, normalizedQuery, exactMatch) },
            limit = limit
        )

        return FindUsersResult(
            ok = result.ok,
            query = normalizedQuery,
            exactMatch = exactMatch,
            users = result.items,
            scannedPages = result.scannedPages,
            hasMore = result.hasMore,
            error = result.error,
            errorDetails = result.errorDetails
        )
    }

    /**
     * 워크스페이스에서 메시지를 검색한다.
     *
     * @param query 검색 쿼리
     * @param count 페이지당 결과 수 (기본 20)
     * @param page 페이지 번호 (기본 1)
     * @return 검색 결과 (일치 메시지, 총 건수, 페이지 정보)
     */
    fun searchMessages(query: String, count: Int = 20, page: Int = 1): SearchMessagesResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchMessagesResult(
                ok = false,
                query = query,
                error = "invalid_query",
                errorDetails = SlackErrorDetails(code = "invalid_query", message = "query is required")
            )
        }

        return try {
            val request = SearchMessagesRequest.builder()
                .query(normalizedQuery)
                .count(count)
                .page(page)
                .highlight(false)
                .build()
            val response = executeWithRetry("search.messages", "query=$normalizedQuery") {
                client.searchMessages(request)
            }
            val searchResult = response.messages
            val pagination = searchResult?.pagination
            SearchMessagesResult(
                ok = response.isOk,
                query = normalizedQuery,
                total = searchResult?.total ?: 0,
                page = pagination?.page ?: page,
                pageCount = pagination?.pageCount ?: 0,
                matches = searchResult?.matches
                    ?.map { item ->
                        SlackSearchMessage(
                            channelId = item.channel?.id,
                            channelName = item.channel?.name,
                            user = item.user,
                            text = item.text,
                            ts = item.ts,
                            permalink = item.permalink
                        )
                    } ?: emptyList(),
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "search.messages failed for query=$normalizedQuery" }
            val errorDetails = exceptionErrorDetails(e)
            SearchMessagesResult(ok = false, query = normalizedQuery, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 텍스트 파일을 채널에 업로드한다.
     *
     * @param channelId 대상 채널 ID
     * @param filename 파일명
     * @param content 파일 내용 (텍스트)
     * @param title 파일 제목 (선택)
     * @param initialComment 업로드와 함께 게시할 코멘트 (선택)
     * @param threadTs 스레드 답장으로 업로드할 경우 부모 타임스탬프 (선택)
     * @return 업로드 결과 (파일 ID, 퍼머링크 등)
     */
    fun uploadFile(
        channelId: String,
        filename: String,
        content: String,
        title: String? = null,
        initialComment: String? = null,
        threadTs: String? = null
    ): UploadFileResult {
        return try {
            val request = FilesUploadV2Request.builder()
                .channel(channelId)
                .filename(filename)
                .content(content)
                .apply {
                    if (!title.isNullOrBlank()) this.title(title)
                    if (!initialComment.isNullOrBlank()) this.initialComment(initialComment)
                    if (!threadTs.isNullOrBlank()) this.threadTs(threadTs)
                }
                .build()
            val response = executeWithRetry("files.uploadV2", "channelId=$channelId filename=$filename") {
                client.filesUploadV2(request)
            }
            val file = response.file ?: response.files?.firstOrNull()
            UploadFileResult(
                ok = response.isOk,
                fileId = file?.id,
                fileName = file?.name,
                title = file?.title,
                permalink = file?.permalink,
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "files.uploadV2 failed for channelId=$channelId filename=$filename" }
            val errorDetails = exceptionErrorDetails(e)
            UploadFileResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * Slack Canvas를 새로 생성한다.
     *
     * @param title Canvas 제목
     * @param markdown Canvas 마크다운 내용
     * @return 생성 결과 (Canvas ID 포함)
     */
    fun createCanvas(title: String, markdown: String): CanvasCreateResult {
        return try {
            val request = CanvasesCreateRequest.builder()
                .title(title)
                .markdown(markdown)
                .build()
            val response = executeWithRetry("canvases.create", "title=$title") {
                client.canvasesCreate(request)
            }
            CanvasCreateResult(
                ok = response.isOk,
                canvasId = response.canvasId,
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "canvases.create failed for title=$title" }
            val errorDetails = exceptionErrorDetails(e)
            CanvasCreateResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    /**
     * 기존 Slack Canvas의 끝에 마크다운 내용을 추가한다.
     *
     * @param canvasId 대상 Canvas ID
     * @param markdown 추가할 마크다운 내용
     * @return 편집 결과
     */
    fun appendCanvas(canvasId: String, markdown: String): CanvasEditResult {
        return try {
            val content = CanvasDocumentContent.builder()
                .type("markdown")
                .markdown(markdown)
                .build()
            val change = CanvasDocumentChange.builder()
                .operation(CanvasEditOperation.INSERT_AT_END)
                .documentContent(content)
                .build()
            val request = CanvasesEditRequest.builder()
                .canvasId(canvasId)
                .changes(listOf(change))
                .build()
            val response = executeWithRetry("canvases.edit", "canvasId=$canvasId") {
                client.canvasesEdit(request)
            }
            CanvasEditResult(
                ok = response.isOk,
                canvasId = canvasId,
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "canvases.edit failed for canvasId=$canvasId" }
            val errorDetails = exceptionErrorDetails(e)
            CanvasEditResult(
                ok = false,
                canvasId = canvasId,
                error = errorDetails.code,
                errorDetails = errorDetails
            )
        }
    }

    private fun usersListPage(limit: Int, cursor: String?): UsersListPageResult {
        return try {
            val request = UsersListRequest.builder()
                .limit(limit)
                .apply { if (cursor != null) cursor(cursor) }
                .build()
            val response = executeWithRetry("users.list", "limit=$limit") {
                client.usersList(request)
            }
            UsersListPageResult(
                ok = response.isOk,
                users = response.members
                    ?.filterNot { it.isDeleted }
                    ?.map { it.toSlackUser() }
                    ?: emptyList(),
                nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
                error = response.error,
                errorDetails = responseErrorDetails(response.error)
            )
        } catch (e: Exception) {
            logger.error(e) { "users.list failed" }
            val errorDetails = exceptionErrorDetails(e)
            UsersListPageResult(ok = false, error = errorDetails.code, errorDetails = errorDetails)
        }
    }

    private fun com.slack.api.model.User.toSlackUser(): SlackUser = SlackUser(
        id = id,
        name = name,
        realName = realName,
        displayName = profile?.displayName,
        email = profile?.email,
        isBot = isBot
    )

    private fun userNameMatches(user: SlackUser, query: String, exactMatch: Boolean): Boolean {
        val candidates = listOfNotNull(user.name, user.displayName, user.realName)
        return if (exactMatch) {
            candidates.any { it.equals(query, ignoreCase = true) }
        } else {
            candidates.any { it.contains(query, ignoreCase = true) }
        }
    }

    private fun responseErrorDetails(errorCode: String?): SlackErrorDetails? {
        val code = errorCode?.takeIf { it.isNotBlank() } ?: return null
        return SlackErrorDetails(
            code = code,
            message = null,
            retryable = isRetryableCode(code),
            retryAfterSeconds = null
        )
    }

    private fun exceptionErrorDetails(e: Exception): SlackErrorDetails {
        if (e is CircuitOpenException) {
            return SlackErrorDetails(
                code = CIRCUIT_OPEN_ERROR,
                message = e.message?.takeIf { it.isNotBlank() },
                retryable = false,
                retryAfterSeconds = e.retryAfterSeconds
            )
        }
        if (e is SlackApiTimeoutException) {
            return SlackErrorDetails(
                code = TIMEOUT_ERROR,
                message = e.message?.takeIf { it.isNotBlank() },
                retryable = true,
                retryAfterSeconds = null
            )
        }
        if (e is SlackApiException) {
            val statusCode = e.response?.code
            val retryAfterSeconds = extractRetryAfterSeconds(e.response?.headers?.toMultimap())
            val code = when {
                !e.error?.error.isNullOrBlank() -> e.error.error
                statusCode == 429 -> RATE_LIMITED_ERROR
                e.message?.contains(RATE_LIMITED_ERROR, ignoreCase = true) == true -> RATE_LIMITED_ERROR
                else -> "slack_api_error"
            }
            return SlackErrorDetails(
                code = code,
                message = e.message?.takeIf { it.isNotBlank() },
                retryable = retryAfterSeconds != null || isRetryableStatus(statusCode) || isRetryableCode(code),
                retryAfterSeconds = retryAfterSeconds
            )
        }
        if (e is IOException) {
            return SlackErrorDetails(
                code = "io_error",
                message = e.message?.takeIf { it.isNotBlank() },
                retryable = true,
                retryAfterSeconds = null
            )
        }
        return SlackErrorDetails(
            code = "client_error",
            message = e.message?.takeIf { it.isNotBlank() } ?: "slack_api_call_failed",
            retryable = false,
            retryAfterSeconds = null
        )
    }

    private fun extractRetryAfterSeconds(headers: Map<String, List<String>>?): Long? {
        if (headers == null) return null
        val rawValue = headers.entries
            .firstOrNull { (key, _) -> key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?: return null
        return rawValue.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun isRetryableStatus(statusCode: Int?): Boolean = statusCode in RETRYABLE_STATUS_CODES

    private fun isRetryableCode(code: String?): Boolean =
        code?.lowercase() in RETRYABLE_ERROR_CODES

    /**
     * 커서 기반 페이지네이션으로 항목을 검색하는 제네릭 헬퍼.
     *
     * 채널/사용자 검색의 공통 루프(페이지 순회, 중복 ID 추적, 필터링, limit 적용)를
     * 한 곳에서 관리한다.
     */
    private fun <T> searchWithPagination(
        fetchPage: (cursor: String?) -> PageData<T>,
        getId: (T) -> String,
        matches: (T) -> Boolean,
        limit: Int,
        maxPages: Int = MAX_SEARCH_PAGES
    ): PaginationSearchResult<T> {
        val collected = mutableListOf<T>()
        val seenIds = mutableSetOf<String>()
        var cursor: String? = null
        var scannedPages = 0

        while (scannedPages < maxPages && collected.size < limit) {
            scannedPages += 1
            val page = fetchPage(cursor)
            if (!page.ok) {
                return PaginationSearchResult(
                    ok = false,
                    items = collected.toList(),
                    scannedPages = scannedPages,
                    hasMore = !page.nextCursor.isNullOrBlank(),
                    error = page.error,
                    errorDetails = page.errorDetails
                )
            }

            for (candidate in page.items) {
                if (collected.size >= limit) break
                if (matches(candidate) && seenIds.add(getId(candidate))) {
                    collected.add(candidate)
                }
            }

            cursor = page.nextCursor
            if (cursor.isNullOrBlank()) break
        }

        return PaginationSearchResult(
            ok = true,
            items = collected.toList(),
            scannedPages = scannedPages,
            hasMore = !cursor.isNullOrBlank()
        )
    }

    private fun channelNameMatches(name: String, query: String, exactMatch: Boolean): Boolean {
        return if (exactMatch) {
            name.equals(query, ignoreCase = true)
        } else {
            name.contains(query, ignoreCase = true)
        }
    }

    // ── 복원력 계층: 재시도 + Circuit Breaker + 타임아웃 + 메트릭 ──

    /** API 호출을 재시도/타임아웃/Circuit Breaker로 감싸 실행하고 메트릭을 기록한다. */
    private fun <T> executeWithRetry(apiName: String, context: String, operation: () -> T): T {
        val startedAtNs = System.nanoTime()
        var attempts = 1
        try {
            ensureCircuitClosed(apiName)
            val result = retryLoop(apiName, context, operation) { attempts = it }
            recordCallMetrics(apiName, "success", null, System.nanoTime() - startedAtNs, attempts)
            return result
        } catch (e: Exception) {
            val errorDetails = exceptionErrorDetails(e)
            recordCallMetrics(apiName, "error", errorDetails.code, System.nanoTime() - startedAtNs, attempts)
            throw e
        }
    }

    /** 재시도 루프 본체. 성공하면 결과를 반환하고, 재시도 불가하면 예외를 던진다. */
    private fun <T> retryLoop(
        apiName: String,
        context: String,
        operation: () -> T,
        onAttempt: (Int) -> Unit
    ): T {
        var attempt = 1
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            onAttempt(attempt)
            try {
                val result = executeWithTimeout(apiName, operation)
                recordSuccessfulCall(apiName)
                return result
            } catch (e: Exception) {
                handleRetryOrThrow(apiName, context, e, attempt, delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                attempt += 1
            }
        }
    }

    /** 재시도 가능 여부를 판단하고 불가 시 예외를 던진다. */
    private fun handleRetryOrThrow(
        apiName: String,
        context: String,
        e: Exception,
        attempt: Int,
        delayMs: Long
    ) {
        val errorDetails = exceptionErrorDetails(e)
        if (!errorDetails.retryable || attempt >= MAX_ATTEMPTS) {
            recordFailedCall(apiName, e)
            throw e
        }
        meterRegistry?.counter(
            "slack_api_retries_total", "api", apiName, "reason", errorDetails.code
        )?.increment()
        val retryDelayMs = computeRetryDelayMs(errorDetails.retryAfterSeconds, delayMs)
        logger.warn {
            "$apiName failed (attempt=$attempt/$MAX_ATTEMPTS, context=$context, " +
                "code=${errorDetails.code}), retrying in ${retryDelayMs}ms"
        }
        sleepQuietly(retryDelayMs)
    }

    private fun recordCallMetrics(
        apiName: String,
        outcome: String,
        errorCode: String?,
        durationNs: Long,
        attempts: Int
    ) {
        val normalizedErrorCode = errorCode ?: "none"
        meterRegistry?.counter(
            "slack_api_calls_total",
            "api", apiName,
            "outcome", outcome,
            "error_code", normalizedErrorCode
        )?.increment()
        meterRegistry?.timer(
            "slack_api_latency",
            "api", apiName,
            "outcome", outcome
        )?.record(durationNs, TimeUnit.NANOSECONDS)
        meterRegistry?.counter(
            "slack_api_attempts_total",
            "api", apiName,
            "outcome", outcome
        )?.increment(attempts.toDouble())
        if (outcome == "error" && errorCode != null) {
            meterRegistry?.counter(
                "slack_api_error_code_total",
                "api", apiName,
                "error_code", errorCode
            )?.increment()
        }
    }

    /** 별도 스레드 풀에서 API 호출을 실행하고 지정 시간 내 완료되지 않으면 타임아웃 예외를 발생시킨다. */
    private fun <T> executeWithTimeout(apiName: String, operation: () -> T): T {
        val timeoutMs = properties.resilience.timeoutMs
        val future: Future<T> = timeoutExecutor.submit<T> { operation() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw SlackApiTimeoutException(apiName = apiName, timeoutMs = timeoutMs)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw RuntimeException("slack api call interrupted: $apiName", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Exception) throw cause
            throw RuntimeException(cause ?: e)
        }
    }

    /** Circuit Breaker가 열려 있으면 [CircuitOpenException]을 던진다. */
    private fun ensureCircuitClosed(apiName: String) {
        val cfg = properties.resilience.circuitBreaker
        if (!cfg.enabled) return

        val now = System.currentTimeMillis()
        val state = circuitStates.computeIfAbsent(apiName) { CircuitBreakerState() }
        val openUntilMs = state.currentOpenUntil(now) ?: return
        val retryAfterSeconds = ((openUntilMs - now).coerceAtLeast(0L) + 999L) / 1_000L
        throw CircuitOpenException(apiName = apiName, retryAfterSeconds = retryAfterSeconds)
    }

    private fun recordSuccessfulCall(apiName: String) {
        val cfg = properties.resilience.circuitBreaker
        if (!cfg.enabled) return

        circuitStates.computeIfAbsent(apiName) { CircuitBreakerState() }.recordSuccess()
    }

    private fun recordFailedCall(apiName: String, error: Exception) {
        val cfg = properties.resilience.circuitBreaker
        if (!cfg.enabled || !countsTowardCircuitBreaker(error)) return

        val state = circuitStates.computeIfAbsent(apiName) { CircuitBreakerState() }
        val opened = state.recordFailure(
            nowMs = System.currentTimeMillis(),
            failureThreshold = cfg.failureThreshold,
            openDurationMs = cfg.openStateDurationMs
        )
        if (opened) {
            logger.warn {
                "$apiName circuit opened after ${cfg.failureThreshold} consecutive failures for ${cfg.openStateDurationMs}ms"
            }
        }
    }

    private fun countsTowardCircuitBreaker(error: Exception): Boolean {
        return when (error) {
            is CircuitOpenException -> false
            is SlackApiTimeoutException -> true
            is IOException -> true
            is SlackApiException -> {
                val statusCode = error.response?.code
                statusCode != null && statusCode >= 500
            }
            else -> false
        }
    }

    private fun computeRetryDelayMs(retryAfterSeconds: Long?, fallbackDelayMs: Long): Long {
        if (retryAfterSeconds != null) {
            return (retryAfterSeconds * 1_000L).coerceIn(0L, MAX_RETRY_DELAY_MS)
        }
        return fallbackDelayMs.coerceIn(0L, MAX_RETRY_DELAY_MS)
    }

    private fun sleepQuietly(delayMs: Long) {
        if (delayMs <= 0) return
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val CHANNEL_PAGE_SIZE = 200
        private const val USERS_PAGE_SIZE = 200
        private const val MAX_SEARCH_PAGES = 20
        private const val RATE_LIMITED_ERROR = "rate_limited"
        private const val TIMEOUT_ERROR = "timeout"
        private const val CIRCUIT_OPEN_ERROR = "circuit_open"
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 100L
        private const val MAX_RETRY_DELAY_MS = 2_000L

        private val RETRYABLE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
        private val RETRYABLE_ERROR_CODES = setOf(
            "rate_limited",
            "ratelimited",
            "internal_error",
            "request_timeout",
            "service_unavailable"
        )
    }
}

/** API 타임아웃 워커 스레드를 데몬 스레드로 생성하는 팩토리. */
private class TimeoutWorkerThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "slack-api-timeout-worker").apply {
            isDaemon = true
        }
    }
}

/** Slack API 호출 타임아웃 시 발생하는 내부 예외. */
private class SlackApiTimeoutException(apiName: String, timeoutMs: Long) :
    RuntimeException("Slack API call timed out after ${timeoutMs}ms (api=$apiName)")

/** Circuit Breaker가 열린 상태에서 호출 시 발생하는 예외. */
private class CircuitOpenException(
    apiName: String,
    val retryAfterSeconds: Long
) : RuntimeException("Circuit is open for $apiName")

/**
 * API별 Circuit Breaker 상태.
 *
 * 연속 실패가 임계값에 도달하면 지정 시간 동안 회로를 열어 호출을 차단한다.
 * 성공 호출이 기록되면 실패 카운터를 초기화한다.
 */
private class CircuitBreakerState {
    @Volatile
    private var openUntilMs: Long = 0
    private var consecutiveFailures: Int = 0

    @Synchronized
    fun currentOpenUntil(nowMs: Long): Long? {
        if (openUntilMs <= nowMs) {
            openUntilMs = 0
            return null
        }
        return openUntilMs
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        openUntilMs = 0
    }

    @Synchronized
    fun recordFailure(nowMs: Long, failureThreshold: Int, openDurationMs: Long): Boolean {
        if (openUntilMs > nowMs) return false

        consecutiveFailures += 1
        if (consecutiveFailures < failureThreshold) return false

        consecutiveFailures = 0
        openUntilMs = nowMs + openDurationMs
        return true
    }
}

// ── 결과 데이터 클래스 ──

/** 메시지 전송 결과. */
data class PostMessageResult(
    val ok: Boolean,
    val ts: String? = null,
    val channel: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 단순 성공/실패 결과 (리액션 추가 등). */
data class SimpleResult(
    val ok: Boolean,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 채널 목록 조회 결과. */
data class ConversationsListResult(
    val ok: Boolean,
    val channels: List<SlackChannel> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** Slack 채널 요약 정보. */
data class SlackChannel(
    val id: String,
    val name: String,
    val topic: String? = null,
    val memberCount: Int = 0,
    val isPrivate: Boolean = false
)

/** 채널 이름 검색 결과. */
data class FindChannelsResult(
    val ok: Boolean,
    val query: String,
    val exactMatch: Boolean = false,
    val channels: List<SlackChannel> = emptyList(),
    val scannedPages: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 대화 이력 / 스레드 답글 조회 결과. */
data class ConversationHistoryResult(
    val ok: Boolean,
    val messages: List<SlackMessage> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** Slack 메시지 단건 정보. */
data class SlackMessage(
    val user: String? = null,
    val text: String? = null,
    val ts: String? = null,
    val threadTs: String? = null
)

/** 사용자 정보 조회 결과. */
data class UserInfoResult(
    val ok: Boolean,
    val user: SlackUser? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 사용자 이름 검색 결과. */
data class FindUsersResult(
    val ok: Boolean,
    val query: String,
    val exactMatch: Boolean = false,
    val users: List<SlackUser> = emptyList(),
    val scannedPages: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 메시지 검색 결과. */
data class SearchMessagesResult(
    val ok: Boolean,
    val query: String,
    val matches: List<SlackSearchMessage> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageCount: Int = 0,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 검색된 메시지 단건 정보. */
data class SlackSearchMessage(
    val channelId: String? = null,
    val channelName: String? = null,
    val user: String? = null,
    val text: String? = null,
    val ts: String? = null,
    val permalink: String? = null
)

/** 파일 업로드 결과. */
data class UploadFileResult(
    val ok: Boolean,
    val fileId: String? = null,
    val fileName: String? = null,
    val title: String? = null,
    val permalink: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** Canvas 생성 결과. */
data class CanvasCreateResult(
    val ok: Boolean,
    val canvasId: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** Canvas 편집 결과. */
data class CanvasEditResult(
    val ok: Boolean,
    val canvasId: String,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** Slack API 에러 상세 정보 (재시도 가능 여부, Retry-After 등). */
data class SlackErrorDetails(
    val code: String,
    val message: String? = null,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null
)

/** Slack 사용자 요약 정보. */
data class SlackUser(
    val id: String,
    val name: String?,
    val realName: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val isBot: Boolean = false
)

private data class UsersListPageResult(
    val ok: Boolean,
    val users: List<SlackUser> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 페이지네이션 API 응답을 통합하는 내부 전용 컨테이너. */
private data class PageData<T>(
    val ok: Boolean,
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

/** 제네릭 페이지네이션 검색 결과. 채널/사용자 검색 헬퍼 내부 전용. */
private data class PaginationSearchResult<T>(
    val ok: Boolean,
    val items: List<T> = emptyList(),
    val scannedPages: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)
