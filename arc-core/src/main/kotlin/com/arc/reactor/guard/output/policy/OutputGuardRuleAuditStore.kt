package com.arc.reactor.guard.output.policy

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 출력 Guard 규칙 감사 로그 데이터 클래스
 *
 * 관리자가 출력 Guard 규칙을 생성/수정/삭제/시뮬레이션할 때 기록되는 감사 로그이다.
 *
 * @property id 감사 로그 고유 ID
 * @property ruleId 대상 규칙 ID (삭제 시에도 기록)
 * @property action 수행된 동작 (CREATE, UPDATE, DELETE, SIMULATE)
 * @property actor 동작을 수행한 관리자 식별자
 * @property detail 변경 상세 내용 (선택사항)
 * @property createdAt 감사 로그 생성 시각
 */
data class OutputGuardRuleAuditLog(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String? = null,
    val action: OutputGuardRuleAuditAction,
    val actor: String,
    val detail: String? = null,
    val createdAt: Instant = Instant.now()
)

/**
 * 출력 Guard 규칙 감사 동작 열거형
 */
enum class OutputGuardRuleAuditAction {
    /** 규칙 생성 */
    CREATE,
    /** 규칙 수정 */
    UPDATE,
    /** 규칙 삭제 */
    DELETE,
    /** 규칙 시뮬레이션 (적용 전 테스트) */
    SIMULATE
}

/**
 * 출력 Guard 규칙 감사 로그 저장소 인터페이스
 *
 * @see InMemoryOutputGuardRuleAuditStore 메모리 기반 구현체
 * @see JdbcOutputGuardRuleAuditStore JDBC 기반 구현체
 */
interface OutputGuardRuleAuditStore {
    /** 감사 로그 목록을 최신순으로 조회한다 (최대 [limit]개). */
    fun list(limit: Int = 100): List<OutputGuardRuleAuditLog>

    /** 감사 로그를 저장한다. */
    fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog
}

/**
 * 메모리 기반 출력 Guard 규칙 감사 로그 저장소
 *
 * [ConcurrentLinkedDeque]를 사용하여 스레드 안전하게 로그를 관리한다.
 * 최대 5,000건을 유지하며, 초과 시 가장 오래된 로그를 제거한다.
 *
 * @see JdbcOutputGuardRuleAuditStore 영구 저장이 필요한 환경용
 */
class InMemoryOutputGuardRuleAuditStore : OutputGuardRuleAuditStore {
    private val logs = ConcurrentLinkedDeque<OutputGuardRuleAuditLog>()

    override fun list(limit: Int): List<OutputGuardRuleAuditLog> {
        val size = limit.coerceIn(1, 1000)
        return logs.asSequence()
            .take(size)
            .toList()
    }

    override fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog {
        logs.addFirst(log)
        // 최대 5,000건 유지 — 초과 시 가장 오래된 항목 제거
        while (logs.size > 5000) {
            logs.pollLast()
        }
        return log
    }
}

/**
 * JDBC 기반 출력 Guard 규칙 감사 로그 저장소
 *
 * output_guard_rule_audits 테이블에 감사 로그를 영구 저장한다.
 *
 * @param jdbcTemplate Spring JdbcTemplate
 *
 * @see InMemoryOutputGuardRuleAuditStore 메모리 기반 대안
 */
class JdbcOutputGuardRuleAuditStore(
    private val jdbcTemplate: JdbcTemplate
) : OutputGuardRuleAuditStore {

    override fun list(limit: Int): List<OutputGuardRuleAuditLog> {
        return jdbcTemplate.query(
            """SELECT id, rule_id, action, actor, detail, created_at
               FROM output_guard_rule_audits
               ORDER BY created_at DESC
               LIMIT ?""",
            ROW_MAPPER,
            limit.coerceIn(1, 1000)
        )
    }

    override fun save(log: OutputGuardRuleAuditLog): OutputGuardRuleAuditLog {
        jdbcTemplate.update(
            """INSERT INTO output_guard_rule_audits (id, rule_id, action, actor, detail, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            log.id,
            log.ruleId,
            log.action.name,
            log.actor,
            log.detail,
            java.sql.Timestamp.from(log.createdAt)
        )
        return log
    }

    companion object {
        /** ResultSet → OutputGuardRuleAuditLog 매핑 함수 */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            OutputGuardRuleAuditLog(
                id = rs.getString("id"),
                ruleId = rs.getString("rule_id"),
                action = OutputGuardRuleAuditAction.valueOf(rs.getString("action")),
                actor = rs.getString("actor"),
                detail = rs.getString("detail"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}
