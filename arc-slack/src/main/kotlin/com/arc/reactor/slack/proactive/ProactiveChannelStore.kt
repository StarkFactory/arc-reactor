package com.arc.reactor.slack.proactive

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/**
 * 선행적(proactive) 채널 설정 저장소.
 *
 * 선행적 모니터링이 활성화된 Slack 채널을 관리한다.
 * 재시작 간 영속성이 필요하면 데이터베이스 기반으로 이 인터페이스를 구현한다.
 *
 * @see InMemoryProactiveChannelStore
 */
interface ProactiveChannelStore {

    fun list(): List<ProactiveChannel>

    fun isEnabled(channelId: String): Boolean

    fun add(channelId: String, channelName: String? = null): ProactiveChannel

    fun remove(channelId: String): Boolean
}

/** 선행적 모니터링 대상 채널 정보. */
data class ProactiveChannel(
    val channelId: String,
    val channelName: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * 인메모리 구현. 재시작 시 채널 목록이 초기화된다.
 * 시작 시 [SlackProperties.proactiveChannelIds]에서 초기 채널을 주입한다.
 */
class InMemoryProactiveChannelStore : ProactiveChannelStore {

    private val channels: Cache<String, ProactiveChannel> = Caffeine.newBuilder()
        .maximumSize(5_000)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build()

    override fun list(): List<ProactiveChannel> =
        channels.asMap().values.sortedBy { it.addedAt }

    override fun isEnabled(channelId: String): Boolean =
        channels.getIfPresent(channelId) != null

    override fun add(channelId: String, channelName: String?): ProactiveChannel {
        val channel = ProactiveChannel(
            channelId = channelId,
            channelName = channelName
        )
        channels.put(channelId, channel)
        return channel
    }

    override fun remove(channelId: String): Boolean {
        val existed = channels.getIfPresent(channelId) != null
        channels.invalidate(channelId)
        return existed
    }

    fun seedFromConfig(channelIds: List<String>) {
        for (id in channelIds) {
            if (channels.getIfPresent(id) == null) {
                channels.put(id, ProactiveChannel(channelId = id))
            }
        }
    }
}
