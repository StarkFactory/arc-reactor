package com.arc.reactor.multibot

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 Slack 봇 인스턴스 저장소.
 */
class JdbcSlackBotInstanceStore(
    private val jdbc: JdbcTemplate
) : SlackBotInstanceStore {

    private val rowMapper = RowMapper<SlackBotInstance> { rs, _ ->
        SlackBotInstance(
            id = rs.getString("id"),
            name = rs.getString("name"),
            botToken = rs.getString("bot_token"),
            appToken = rs.getString("app_token"),
            personaId = rs.getString("persona_id"),
            defaultChannel = rs.getString("default_channel"),
            enabled = rs.getBoolean("enabled"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    override fun list(): List<SlackBotInstance> {
        return jdbc.query("SELECT * FROM slack_bot_instances ORDER BY created_at", rowMapper)
    }

    override fun get(id: String): SlackBotInstance? {
        return jdbc.query("SELECT * FROM slack_bot_instances WHERE id = ?", rowMapper, id)
            .firstOrNull()
    }

    override fun save(instance: SlackBotInstance): SlackBotInstance {
        jdbc.update(
            """INSERT INTO slack_bot_instances (id, name, bot_token, app_token, persona_id, default_channel, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET
                 name = EXCLUDED.name,
                 bot_token = EXCLUDED.bot_token,
                 app_token = EXCLUDED.app_token,
                 persona_id = EXCLUDED.persona_id,
                 default_channel = EXCLUDED.default_channel,
                 enabled = EXCLUDED.enabled,
                 updated_at = EXCLUDED.updated_at""",
            instance.id, instance.name, instance.botToken, instance.appToken,
            instance.personaId, instance.defaultChannel, instance.enabled,
            java.sql.Timestamp.from(instance.createdAt),
            java.sql.Timestamp.from(instance.updatedAt)
        )
        return instance
    }

    override fun delete(id: String) {
        jdbc.update("DELETE FROM slack_bot_instances WHERE id = ?", id)
    }
}
