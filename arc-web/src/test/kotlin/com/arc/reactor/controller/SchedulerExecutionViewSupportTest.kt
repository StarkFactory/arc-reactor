package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SchedulerExecutionViewSupportTest {

    @Test
    fun `failure reason from stored scheduler error를 추출한다`() {
        val failure = schedulerFailureReason("Job 'Release digest' failed: MCP server 'atlassian' is not connected")

        assertEquals("MCP server 'atlassian' is not connected", failure)
    }

    @Test
    fun `successful result에 대해 null failure reason를 반환한다`() {
        assertNull(schedulerFailureReason("Release digest completed successfully"))
    }

    @Test
    fun `compact result preview를 빌드한다`() {
        val preview = schedulerResultPreview("alpha\nbeta\tgamma", maxLength = 12)

        assertEquals("alpha beta…", preview)
    }
}
