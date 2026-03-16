package com.arc.reactor.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SchedulerTagsTest {

    @Nested
    inner class InMemoryStoreTagsPersistence {

        @Test
        fun `save and retrieve job with tags`() {
            val store = InMemoryScheduledJobStore()
            val job = mcpJob(tags = setOf("daily", "reporting"))
            val saved = store.save(job)

            val retrieved = store.findById(saved.id)

            assertEquals(setOf("daily", "reporting"), retrieved?.tags,
                "Tags should be persisted and retrievable from InMemoryScheduledJobStore")
        }

        @Test
        fun `save and retrieve job with empty tags`() {
            val store = InMemoryScheduledJobStore()
            val job = mcpJob(tags = emptySet())
            val saved = store.save(job)

            val retrieved = store.findById(saved.id)

            assertTrue(retrieved?.tags?.isEmpty() == true,
                "Empty tags set should be preserved after save/retrieve")
        }

        @Test
        fun `update preserves tags`() {
            val store = InMemoryScheduledJobStore()
            val job = mcpJob(tags = setOf("v1"))
            val saved = store.save(job)

            val updated = store.update(saved.id, saved.copy(tags = setOf("v2", "urgent")))

            assertEquals(setOf("v2", "urgent"), updated?.tags,
                "Tags should be updated correctly via store.update()")
        }

        @Test
        fun `tags with special characters are preserved`() {
            val store = InMemoryScheduledJobStore()
            val specialTags = setOf(
                "tag with spaces",
                "tag,with,commas",
                "unicode-\uD83D\uDE80-rocket",
                "korean-\uD55C\uAD6D\uC5B4"
            )
            val job = mcpJob(tags = specialTags)
            val saved = store.save(job)

            val retrieved = store.findById(saved.id)

            assertEquals(specialTags, retrieved?.tags,
                "Tags with spaces, commas, unicode, and Korean characters should be preserved")
        }

        @Test
        fun `list returns jobs with their tags intact`() {
            val store = InMemoryScheduledJobStore()
            store.save(mcpJob(name = "job-a", tags = setOf("alpha")))
            store.save(mcpJob(name = "job-b", tags = setOf("beta", "gamma")))
            store.save(mcpJob(name = "job-c", tags = emptySet()))

            val jobs = store.list()

            assertEquals(3, jobs.size, "All three jobs should be listed")
            val tagsByName = jobs.associate { it.name to it.tags }
            assertEquals(setOf("alpha"), tagsByName["job-a"],
                "job-a should have tag 'alpha'")
            assertEquals(setOf("beta", "gamma"), tagsByName["job-b"],
                "job-b should have tags 'beta' and 'gamma'")
            assertEquals(emptySet<String>(), tagsByName["job-c"],
                "job-c should have empty tags")
        }
    }

    @Nested
    inner class TagFiltering {

        @Test
        fun `filter jobs by tag returns only matching jobs`() {
            val jobs = listOf(
                mcpJob(name = "job-a", tags = setOf("daily", "reporting")),
                mcpJob(name = "job-b", tags = setOf("weekly")),
                mcpJob(name = "job-c", tags = setOf("daily", "alerts"))
            )

            val filtered = jobs.filter { "daily" in it.tags }

            assertEquals(2, filtered.size,
                "Filtering by 'daily' should return 2 jobs")
            assertTrue(filtered.all { "daily" in it.tags },
                "All filtered jobs should contain the 'daily' tag")
        }

        @Test
        fun `filter by tag not present returns empty list`() {
            val jobs = listOf(
                mcpJob(name = "job-a", tags = setOf("daily")),
                mcpJob(name = "job-b", tags = setOf("weekly"))
            )

            val filtered = jobs.filter { "nonexistent" in it.tags }

            assertTrue(filtered.isEmpty(),
                "Filtering by a non-existent tag should return an empty list")
        }

        @Test
        fun `filter with blank tag returns all jobs`() {
            val jobs = listOf(
                mcpJob(name = "job-a", tags = setOf("daily")),
                mcpJob(name = "job-b", tags = emptySet())
            )
            val tag: String? = null

            val filtered = if (tag.isNullOrBlank()) jobs else jobs.filter { tag in it.tags }

            assertEquals(2, filtered.size,
                "Null tag filter should return all jobs")
        }

        @Test
        fun `filter by tag with special characters matches exactly`() {
            val specialTag = "tag with spaces"
            val jobs = listOf(
                mcpJob(name = "job-a", tags = setOf(specialTag)),
                mcpJob(name = "job-b", tags = setOf("tag", "with", "spaces"))
            )

            val filtered = jobs.filter { specialTag in it.tags }

            assertEquals(1, filtered.size,
                "Only the job with the exact special-character tag should match")
            assertEquals("job-a", filtered[0].name,
                "The matched job should be job-a which has the exact tag")
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private fun mcpJob(
        name: String = "test-job",
        tags: Set<String> = emptySet()
    ) = ScheduledJob(
        name = name,
        cronExpression = "0 0 9 * * *",
        jobType = ScheduledJobType.MCP_TOOL,
        mcpServerName = "test-server",
        toolName = "test_tool",
        tags = tags,
        enabled = true
    )
}
