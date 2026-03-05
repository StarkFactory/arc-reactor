package com.arc.reactor.slack.proactive

import java.util.concurrent.ConcurrentHashMap

/**
 * Store for proactive channel configuration.
 *
 * Manages which Slack channels have proactive monitoring enabled.
 * Implement this interface to back with a database for persistence across restarts.
 */
interface ProactiveChannelStore {

    fun list(): List<ProactiveChannel>

    fun isEnabled(channelId: String): Boolean

    fun add(channelId: String, channelName: String? = null): ProactiveChannel

    fun remove(channelId: String): Boolean
}

data class ProactiveChannel(
    val channelId: String,
    val channelName: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * In-memory implementation. Channels are lost on restart.
 * Seed from [SlackProperties.proactiveChannelIds] at startup.
 */
class InMemoryProactiveChannelStore : ProactiveChannelStore {

    private val channels = ConcurrentHashMap<String, ProactiveChannel>()

    override fun list(): List<ProactiveChannel> =
        channels.values.sortedBy { it.addedAt }

    override fun isEnabled(channelId: String): Boolean =
        channels.containsKey(channelId)

    override fun add(channelId: String, channelName: String?): ProactiveChannel {
        val channel = ProactiveChannel(
            channelId = channelId,
            channelName = channelName
        )
        channels[channelId] = channel
        return channel
    }

    override fun remove(channelId: String): Boolean =
        channels.remove(channelId) != null

    fun seedFromConfig(channelIds: List<String>) {
        for (id in channelIds) {
            channels.putIfAbsent(id, ProactiveChannel(channelId = id))
        }
    }
}
