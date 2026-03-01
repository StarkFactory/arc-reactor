package com.arc.reactor.memory

import com.arc.reactor.memory.impl.JdbcUserMemoryStore
import com.arc.reactor.memory.model.UserMemory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.util.concurrent.CancellationException

class JdbcUserMemoryStoreCancellationTest {

    @Test
    fun `get rethrows CancellationException for structured concurrency`() = runTest {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<UserMemory>>(), any<String>())
        } throws CancellationException("cancelled")
        val store = JdbcUserMemoryStore(jdbcTemplate, autoCreateTable = false)

        try {
            store.get("user-1")
            fail("CancellationException must be rethrown from JdbcUserMemoryStore.get")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `get returns null on non-cancellation database exception`() = runTest {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<UserMemory>>(), any<String>())
        } throws RuntimeException("db down")
        val store = JdbcUserMemoryStore(jdbcTemplate, autoCreateTable = false)

        val result = store.get("user-1")

        assertNull(result, "Non-cancellation errors should keep fail-open behavior and return null")
    }
}
