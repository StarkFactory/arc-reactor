package com.arc.reactor.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 에 대한 테스트. STDIO command and args validation in [McpConnectionSupport].
 */
class McpStdioCommandValidationTest {

    private fun support(
        allowedCommands: Set<String> = McpSecurityConfig.DEFAULT_ALLOWED_STDIO_COMMANDS
    ) = McpConnectionSupport(
        connectionTimeoutMs = 300,
        maxToolOutputLengthProvider = { 50_000 },
        allowedStdioCommandsProvider = { allowedCommands }
    )

    @Nested
    inner class CommandAllowlist {

        @Test
        fun `allowed은(는) command passes validation`() {
            val support = support()

            assertTrue(support.validateStdioCommand("npx", "test-server")) {
                "Default-allowed command 'npx' should pass validation"
            }
        }

        @Test
        fun `all default commands은(는) accepted이다`() {
            val support = support()
            val defaults = listOf(
                "npx", "node", "python", "python3",
                "uvx", "uv", "docker", "deno", "bun"
            )

            for (cmd in defaults) {
                assertTrue(support.validateStdioCommand(cmd, "test")) {
                    "Default command '$cmd' should be allowed"
                }
            }
        }

        @Test
        fun `command not in allowlist은(는) rejected이다`() {
            val support = support()

            assertFalse(support.validateStdioCommand("bash", "test-server")) {
                "Command 'bash' should be rejected (not in allowlist)"
            }
        }

        @Test
        fun `비어있는 allowlist rejects all commands`() {
            val support = support(allowedCommands = emptySet())

            assertFalse(support.validateStdioCommand("npx", "test-server")) {
                "Any command should be rejected when allowlist is empty"
            }
        }

        @Test
        fun `커스텀 allowlist accepts custom command`() {
            val support = support(allowedCommands = setOf("my-tool"))

            assertTrue(support.validateStdioCommand("my-tool", "test-server")) {
                "Custom command should be accepted by custom allowlist"
            }
        }

        @Test
        fun `커스텀 allowlist rejects default commands not in it`() {
            val support = support(allowedCommands = setOf("my-tool"))

            assertFalse(support.validateStdioCommand("npx", "test-server")) {
                "Default command should be rejected when not in custom allowlist"
            }
        }

        @Test
        fun `absolute은(는) path command uses basename for allowlist check`() {
            // 경로 검사는 파일 존재 여부도 확인합니다,
            // but the allowlist check on basename comes first
            val support = support(allowedCommands = setOf("sh"))

            // /usr/bin/sh does not pass the path-exists check on all
            // platforms, but the base name "sh" is in the allowlist.
            // 파일 존재를 보장할 수 없으므로 다음만 테스트합니다
            // the rejection case for a command not in allowlist.
            assertFalse(
                support.validateStdioCommand("/usr/bin/bash", "test-server")
            ) {
                "Absolute path command should use basename 'bash' " +
                    "for allowlist check and reject it"
            }
        }
    }

    @Nested
    inner class PathTraversal {

        @Test
        fun `command with double-dot path traversal은(는) rejected이다`() {
            val support = support()

            assertFalse(
                support.validateStdioCommand("../../../bin/npx", "test-server")
            ) {
                "Command with '..' path traversal should be rejected"
            }
        }

        @Test
        fun `command with embedded double-dot은(는) rejected이다`() {
            val support = support()

            assertFalse(
                support.validateStdioCommand(
                    "/usr/local/../bin/npx", "test-server"
                )
            ) {
                "Command with embedded '..' should be rejected"
            }
        }

        @Test
        fun `command with double-dot as standalone name은(는) rejected이다`() {
            val support = support()

            assertFalse(support.validateStdioCommand("..", "test-server")) {
                "Standalone '..' command should be rejected"
            }
        }
    }

    @Nested
    inner class ArgsValidation {

        @Test
        fun `유효한 args pass validation`() {
            val support = support()

            assertTrue(
                support.validateStdioArgs(
                    listOf("-y", "@modelcontextprotocol/server-everything"),
                    "test-server"
                )
            ) {
                "Normal args should pass validation"
            }
        }

        @Test
        fun `비어있는 args list passes validation`() {
            val support = support()

            assertTrue(support.validateStdioArgs(emptyList(), "test-server")) {
                "Empty args list should pass validation"
            }
        }

        @Test
        fun `args with null byte은(는) rejected이다`() {
            val support = support()

            assertFalse(
                support.validateStdioArgs(
                    listOf("--flag\u0000injected"), "test-server"
                )
            ) {
                "Args containing null byte should be rejected"
            }
        }

        @Test
        fun `args with control characters은(는) rejected이다`() {
            val support = support()

            // 다양한 제어 문자 테스트 (벨, 백스페이스, 이스케이프)
            val controlChars = listOf("\u0007", "\u0008", "\u001B")
            for (ch in controlChars) {
                assertFalse(
                    support.validateStdioArgs(
                        listOf("arg${ch}value"), "test-server"
                    )
                ) {
                    "Args containing control char U+${
                        ch[0].code.toString(16).padStart(4, '0')
                    } should be rejected"
                }
            }
        }

        @Test
        fun `args with tab은(는) accepted이다`() {
            val support = support()

            assertTrue(
                support.validateStdioArgs(
                    listOf("value\twith\ttabs"), "test-server"
                )
            ) {
                "Args containing tabs should be accepted"
            }
        }

        @Test
        fun `args with newline은(는) accepted이다`() {
            val support = support()

            assertTrue(
                support.validateStdioArgs(
                    listOf("value\nwith\nnewlines"), "test-server"
                )
            ) {
                "Args containing newlines should be accepted"
            }
        }

        @Test
        fun `두 번째 arg with control char causes rejection`() {
            val support = support()

            assertFalse(
                support.validateStdioArgs(
                    listOf("safe-arg", "bad\u0001arg"), "test-server"
                )
            ) {
                "Any arg in the list containing control chars should " +
                    "cause rejection"
            }
        }
    }
}
