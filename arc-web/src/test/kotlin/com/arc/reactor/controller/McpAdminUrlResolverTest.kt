package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class McpAdminUrlResolverTest {

    @Nested
    inner class ExplicitAdminUrl {

        @Test
        fun `prefer explicit adminUrl over sse url해야 한다`() {
            val config = mapOf(
                "adminUrl" to "https://admin.example.com/base/",
                "url" to "https://sse.example.com/sse"
            )

            val resolved = McpAdminUrlResolver.resolve(config)

            assertEquals(
                "https://admin.example.com/base",
                resolved,
                "Expected explicit adminUrl to win and trailing slash to be removed"
            )
        }

        @Test
        fun `reject non-http scheme in adminUrl해야 한다`() {
            val config = mapOf("adminUrl" to "ftp://admin.example.com/policy")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertNull(resolved, "Expected non-http adminUrl schemes to be rejected")
        }
    }

    @Nested
    inner class SseDerivedUrl {

        @Test
        fun `derive admin base from sse url해야 한다`() {
            val config = mapOf("url" to "https://mcp.example.com/admin/sse")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertEquals(
                "https://mcp.example.com/admin",
                resolved,
                "Expected /sse suffix to be removed from derived admin base URL"
            )
        }

        @Test
        fun `url does not end with sse일 때 keep path해야 한다`() {
            val config = mapOf("url" to "https://mcp.example.com/admin/v1")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertEquals(
                "https://mcp.example.com/admin/v1",
                resolved,
                "Expected derived admin base URL to preserve non-sse paths"
            )
        }

        @Test
        fun `strip query and fragment from derived url해야 한다`() {
            val config = mapOf("url" to "https://mcp.example.com/admin/sse?token=x#section")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertEquals(
                "https://mcp.example.com/admin",
                resolved,
                "Expected query and fragment to be removed from derived admin base URL"
            )
        }
    }

    @Nested
    inner class InvalidUrls {

        @Test
        fun `reject relative adminUrl해야 한다`() {
            val config = mapOf("adminUrl" to "/admin")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertNull(resolved, "Expected relative adminUrl to be rejected")
        }

        @Test
        fun `reject invalid sse url해야 한다`() {
            val config = mapOf("url" to "not-a-url")

            val resolved = McpAdminUrlResolver.resolve(config)

            assertNull(resolved, "Expected invalid sse url to produce null admin base URL")
        }
    }
}
