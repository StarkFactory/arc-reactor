package com.arc.reactor.controller

import java.net.URI

/**
 * Resolves and normalizes MCP admin API base URLs from server config.
 */
internal object McpAdminUrlResolver {

    fun resolve(config: Map<String, Any>): String? {
        val explicitAdminUrl = config["adminUrl"]?.toString().orEmpty()
        if (explicitAdminUrl.isNotBlank()) {
            return normalizeAbsoluteHttpUrl(explicitAdminUrl)
        }

        val sseUrl = config["url"]?.toString().orEmpty()
        if (sseUrl.isBlank()) {
            return null
        }
        return deriveFromSseUrl(sseUrl)
    }

    private fun deriveFromSseUrl(rawSseUrl: String): String? {
        val sseUri = parseAbsoluteHttpUri(rawSseUrl) ?: return null
        val path = sseUri.path.orEmpty()
        val basePath = if (path.endsWith("/sse")) path.removeSuffix("/sse") else path
        val rebuilt = URI(
            sseUri.scheme,
            sseUri.userInfo,
            sseUri.host,
            sseUri.port,
            basePath.ifBlank { null },
            null,
            null
        )
        return normalizeAbsoluteHttpUrl(rebuilt.toString())
    }

    private fun normalizeAbsoluteHttpUrl(rawUrl: String): String? {
        val uri = parseAbsoluteHttpUri(rawUrl) ?: return null
        val normalizedPath = uri.path?.takeIf { it.isNotBlank() }
        return URI(
            uri.scheme.lowercase(),
            uri.userInfo,
            uri.host,
            uri.port,
            normalizedPath,
            null,
            null
        ).toString().trimEnd('/')
    }

    private fun parseAbsoluteHttpUri(rawUrl: String): URI? {
        return try {
            val uri = URI(rawUrl.trim())
            val scheme = uri.scheme?.lowercase() ?: return null
            if (!uri.isAbsolute || uri.host.isNullOrBlank()) return null
            if (scheme != "http" && scheme != "https") return null
            uri
        } catch (_: Exception) {
            null
        }
    }
}
