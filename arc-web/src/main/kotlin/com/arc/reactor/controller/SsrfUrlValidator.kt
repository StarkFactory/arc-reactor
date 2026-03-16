package com.arc.reactor.controller

import mu.KotlinLogging
import java.net.InetAddress
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 *
 * Checks:
 * - Only http/https schemes are allowed
 * - Resolved IP must not be in private/reserved ranges
 * - DNS rebinding protection via post-resolution IP check
 */
object SsrfUrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Validates the given URL string for SSRF safety.
     *
     * @return null if the URL is safe, or an error message describing the violation
     */
    fun validate(url: String, allowPrivateAddresses: Boolean = false): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "Invalid URL format: $url"
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return "URL scheme '$scheme' is not allowed. Only http and https are permitted."
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return "URL must contain a valid host: $url"
        }

        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return "Cannot resolve host '$host': ${e.message}"
        }

        if (!allowPrivateAddresses && isPrivateOrReserved(address)) {
            logger.warn { "SSRF blocked: URL '$url' resolves to private/reserved address ${address.hostAddress}" }
            return "URL resolves to a private or reserved IP address, which is not allowed."
        }

        return null
    }

    internal fun isPrivateOrReserved(address: InetAddress): Boolean {
        val bytes = address.address

        // IPv6 loopback ::1
        if (address.isLoopbackAddress) return true

        // IPv4 checks
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            return when {
                // 127.0.0.0/8 (loopback)
                b0 == 127 -> true
                // 10.0.0.0/8
                b0 == 10 -> true
                // 172.16.0.0/12
                b0 == 172 && b1 in 16..31 -> true
                // 192.168.0.0/16
                b0 == 192 && b1 == 168 -> true
                // 169.254.0.0/16 (link-local / cloud metadata)
                b0 == 169 && b1 == 254 -> true
                // 0.0.0.0/8
                b0 == 0 -> true
                else -> false
            }
        }

        // IPv6: link-local, site-local, loopback
        if (bytes.size == 16) {
            if (address.isLinkLocalAddress) return true
            if (address.isSiteLocalAddress) return true
            // IPv4-mapped IPv6 (::ffff:x.x.x.x) — check the embedded IPv4
            if (isIpv4MappedIpv6(bytes)) {
                val ipv4Bytes = bytes.copyOfRange(12, 16)
                val ipv4 = InetAddress.getByAddress(ipv4Bytes)
                return isPrivateOrReserved(ipv4)
            }
        }

        return false
    }

    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean {
        // First 10 bytes are 0, bytes 10-11 are 0xFF
        for (i in 0..9) {
            if (bytes[i].toInt() != 0) return false
        }
        return bytes[10].toInt() and 0xFF == 0xFF && bytes[11].toInt() and 0xFF == 0xFF
    }
}
