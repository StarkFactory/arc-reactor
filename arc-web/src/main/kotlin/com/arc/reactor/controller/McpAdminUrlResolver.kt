package com.arc.reactor.controller

import java.net.URI

/**
 * MCP 서버 config에서 admin API 기본 URL을 해석하고 정규화하는 유틸리티.
 *
 * 해석 우선순위:
 * 1. config.adminUrl (명시적 admin URL)
 * 2. config.url에서 /sse 접미사를 제거하여 유도
 *
 * WHY: MCP 서버마다 admin URL 지정 방식이 다르므로 여러 소스에서 안전하게 해석해야 한다.
 * 해석된 URL은 [SsrfUrlValidator]로 사설/예약 IP 접근을 차단한다.
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
