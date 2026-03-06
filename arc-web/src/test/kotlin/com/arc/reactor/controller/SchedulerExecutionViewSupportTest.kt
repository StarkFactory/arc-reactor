package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SchedulerExecutionViewSupportTest {

    @Test
    fun `extracts failure reason from stored scheduler error`() {
        val failure = schedulerFailureReason("Job 'Release digest' failed: MCP server 'atlassian' is not connected")

        assertEquals("MCP server 'atlassian' is not connected", failure)
    }

    @Test
    fun `returns null failure reason for successful result`() {
        assertNull(schedulerFailureReason("Release digest completed successfully"))
    }

    @Test
    fun `builds compact result preview`() {
        val preview = schedulerResultPreview("alpha\nbeta\tgamma", maxLength = 12)

        assertEquals("alpha beta…", preview)
    }
}
