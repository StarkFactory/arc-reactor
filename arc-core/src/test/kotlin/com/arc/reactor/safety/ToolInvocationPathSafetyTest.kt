package com.arc.reactor.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

class ToolInvocationPathSafetyTest {

    @Test
    fun `direct tool call must stay in approved execution boundary`() {
        val sourceRoot = resolveSourceRoot()
        val directCallFiles = Files.walk(sourceRoot).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".kt") }
                .filter { path -> DIRECT_TOOL_CALL_REGEX.containsMatchIn(Files.readString(path)) }
                .map { sourceRoot.relativize(it).toString().replace('\\', '/') }
                .toSet()
        }

        assertEquals(
            setOf("com/arc/reactor/scheduler/DynamicSchedulerService.kt"),
            directCallFiles,
            "Direct tool invocation must be centralized in scheduler execution boundary"
        )
    }

    private fun resolveSourceRoot(): Path {
        val moduleRelative = Paths.get("src/main/kotlin")
        if (Files.exists(moduleRelative)) {
            return moduleRelative
        }
        val repoRelative = Paths.get("arc-core/src/main/kotlin")
        if (Files.exists(repoRelative)) {
            return repoRelative
        }
        error("Cannot resolve arc-core Kotlin source root")
    }

    companion object {
        private val DIRECT_TOOL_CALL_REGEX = Regex("""\btool\.call\(""")
    }
}
