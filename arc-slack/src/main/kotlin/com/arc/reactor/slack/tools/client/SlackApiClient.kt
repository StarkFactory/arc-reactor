package com.arc.reactor.slack.tools.client

import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest
import com.slack.api.methods.request.conversations.ConversationsListRequest
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest
import com.slack.api.methods.request.files.FilesUploadV2Request
import com.slack.api.methods.request.reactions.ReactionsAddRequest
import com.slack.api.methods.request.search.SearchMessagesRequest
import com.slack.api.methods.request.users.UsersInfoRequest
import com.slack.api.methods.request.users.UsersListRequest
import com.slack.api.model.ConversationType
import com.arc.reactor.slack.tools.config.SlackToolsProperties
import mu.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

class SlackApiClient(
    private val client: MethodsClient,
    private val properties: SlackToolsProperties,
    private val meterRegistry: MeterRegistry? = null
) {

    private val circuitStates = ConcurrentHashMap<String, CircuitBreakerState>()

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

        val matches = mutableListOf<SlackChannel>()
        var cursor: String? = null
        var scannedPages = 0

        while (scannedPages < MAX_SEARCH_PAGES && matches.size < limit) {
            scannedPages += 1
            val pageResult = conversationsList(limit = CHANNEL_PAGE_SIZE, cursor = cursor)
            if (!pageResult.ok) {
                return FindChannelsResult(
                    ok = false,
                    query = normalizedQuery,
                    exactMatch = exactMatch,
                    channels = matches.toList(),
                    scannedPages = scannedPages,
                    hasMore = !pageResult.nextCursor.isNullOrBlank(),
                    error = pageResult.error,
                    errorDetails = pageResult.errorDetails
                )
            }

            pageResult.channels
                .filter { channelNameMatches(it.name, normalizedQuery, exactMatch) }
                .forEach { candidate ->
                    if (matches.size >= limit) return@forEach
                    if (matches.none { it.id == candidate.id }) {
                        matches.add(candidate)
                    }
                }

            cursor = pageResult.nextCursor
            if (cursor.isNullOrBlank()) break
        }

        return FindChannelsResult(
            ok = true,
            query = normalizedQuery,
            exactMatch = exactMatch,
            channels = matches.toList(),
            scannedPages = scannedPages,
            hasMore = !cursor.isNullOrBlank()
        )
    }

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

        val matches = mutableListOf<SlackUser>()
        var cursor: String? = null
        var scannedPages = 0

        while (scannedPages < MAX_SEARCH_PAGES && matches.size < limit) {
            scannedPages += 1
            val page = usersListPage(limit = USERS_PAGE_SIZE, cursor = cursor)
            if (!page.ok) {
                return FindUsersResult(
                    ok = false,
                    query = normalizedQuery,
                    exactMatch = exactMatch,
                    users = matches.toList(),
                    scannedPages = scannedPages,
                    hasMore = !page.nextCursor.isNullOrBlank(),
                    error = page.error,
                    errorDetails = page.errorDetails
                )
            }

            page.users
                .filter { userNameMatches(it, normalizedQuery, exactMatch) }
                .forEach { candidate ->
                    if (matches.size >= limit) return@forEach
                    if (matches.none { it.id == candidate.id }) {
                        matches.add(candidate)
                    }
                }

            cursor = page.nextCursor
            if (cursor.isNullOrBlank()) break
        }

        return FindUsersResult(
            ok = true,
            query = normalizedQuery,
            exactMatch = exactMatch,
            users = matches.toList(),
            scannedPages = scannedPages,
            hasMore = !cursor.isNullOrBlank()
        )
    }

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

    private fun channelNameMatches(name: String, query: String, exactMatch: Boolean): Boolean {
        return if (exactMatch) {
            name.equals(query, ignoreCase = true)
        } else {
            name.contains(query, ignoreCase = true)
        }
    }

    private fun <T> executeWithRetry(apiName: String, context: String, operation: () -> T): T {
        val startedAtNs = System.nanoTime()
        var attempts = 1

        try {
            ensureCircuitClosed(apiName)

            var attempt = 1
            var delayMs = INITIAL_RETRY_DELAY_MS

            while (true) {
                attempts = attempt
                try {
                    val result = executeWithTimeout(apiName, operation)
                    recordSuccessfulCall(apiName)
                    recordCallMetrics(
                        apiName = apiName,
                        outcome = "success",
                        errorCode = null,
                        durationNs = System.nanoTime() - startedAtNs,
                        attempts = attempts
                    )
                    return result
                } catch (e: Exception) {
                    val errorDetails = exceptionErrorDetails(e)
                    val shouldRetry = errorDetails.retryable && attempt < MAX_ATTEMPTS
                    if (!shouldRetry) {
                        recordFailedCall(apiName, e)
                        throw e
                    }

                    meterRegistry?.counter(
                        "slack_api_retries_total",
                        "api", apiName,
                        "reason", errorDetails.code
                    )?.increment()

                    val retryDelayMs = computeRetryDelayMs(errorDetails.retryAfterSeconds, delayMs)
                    logger.warn {
                        "$apiName failed (attempt=$attempt/$MAX_ATTEMPTS, context=$context, code=${errorDetails.code}), " +
                            "retrying in ${retryDelayMs}ms"
                    }
                    sleepQuietly(retryDelayMs)
                    delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    attempt += 1
                }
            }
        } catch (e: Exception) {
            val errorDetails = exceptionErrorDetails(e)
            recordCallMetrics(
                apiName = apiName,
                outcome = "error",
                errorCode = errorDetails.code,
                durationNs = System.nanoTime() - startedAtNs,
                attempts = attempts
            )
            throw e
        }
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

        private val timeoutExecutor = Executors.newCachedThreadPool(
            TimeoutWorkerThreadFactory()
        )
    }
}

private class TimeoutWorkerThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "slack-api-timeout-worker").apply {
            isDaemon = true
        }
    }
}

private class SlackApiTimeoutException(apiName: String, timeoutMs: Long) :
    RuntimeException("Slack API call timed out after ${timeoutMs}ms (api=$apiName)")

private class CircuitOpenException(
    apiName: String,
    val retryAfterSeconds: Long
) : RuntimeException("Circuit is open for $apiName")

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

// Result data classes
data class PostMessageResult(
    val ok: Boolean,
    val ts: String? = null,
    val channel: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

data class SimpleResult(
    val ok: Boolean,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

data class ConversationsListResult(
    val ok: Boolean,
    val channels: List<SlackChannel> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

data class SlackChannel(
    val id: String,
    val name: String,
    val topic: String? = null,
    val memberCount: Int = 0,
    val isPrivate: Boolean = false
)

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

data class ConversationHistoryResult(
    val ok: Boolean,
    val messages: List<SlackMessage> = emptyList(),
    val nextCursor: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

data class SlackMessage(
    val user: String? = null,
    val text: String? = null,
    val ts: String? = null,
    val threadTs: String? = null
)

data class UserInfoResult(
    val ok: Boolean,
    val user: SlackUser? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

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

data class SlackSearchMessage(
    val channelId: String? = null,
    val channelName: String? = null,
    val user: String? = null,
    val text: String? = null,
    val ts: String? = null,
    val permalink: String? = null
)

data class UploadFileResult(
    val ok: Boolean,
    val fileId: String? = null,
    val fileName: String? = null,
    val title: String? = null,
    val permalink: String? = null,
    val error: String? = null,
    val errorDetails: SlackErrorDetails? = null
)

data class SlackErrorDetails(
    val code: String,
    val message: String? = null,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null
)

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
