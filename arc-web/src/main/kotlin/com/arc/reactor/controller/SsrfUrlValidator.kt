package com.arc.reactor.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.net.InetAddress
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * 서버 측 요청 위조(SSRF) 공격을 방지하기 위한 URL 검증기.
 *
 * 검증 항목:
 * - http/https 스킴만 허용
 * - 해석된 IP가 사설/예약 범위에 속하지 않아야 함
 * - DNS 리바인딩 방지를 위한 해석 후 IP 검사
 *
 * WHY: MCP 서버 등록 시 사용자가 내부 네트워크 주소를 지정하여
 * 내부 서비스에 접근하는 SSRF 공격을 방지해야 한다.
 */
object SsrfUrlValidator {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * 주어진 URL 문자열의 SSRF 안전성을 검증한다.
     *
     * R293 fix: `suspend fun`으로 전환 + `InetAddress.getByName`을 [withContext]
     * `Dispatchers.IO`로 격리. 이전 구현은 blocking DNS resolution을 Reactor Netty NIO
     * 이벤트 루프에서 직접 실행하여 WebFlux 핸들러를 차단할 수 있었다 (DNS latency
     * 또는 미응답 호스트 시 수십 초~분 단위 차단 가능). suspend 전환으로 모든 callers
     * (registerServer/updateServer/parseMediaUri 등 suspend chain)가 자연스럽게 IO
     * dispatcher로 격리된다.
     *
     * @return URL이 안전하면 null, 위반 사항이 있으면 오류 메시지를 반환
     */
    suspend fun validate(url: String, allowPrivateAddresses: Boolean = false): String? {
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

        // R293: blocking DNS resolution을 IO dispatcher로 격리
        val address = try {
            withContext(Dispatchers.IO) {
                InetAddress.getByName(host)
            }
        } catch (e: Exception) {
            logger.warn(e) { "호스트 DNS 확인 실패: host=$host" }
            return "호스트를 확인할 수 없습니다"
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
                // 100.64.0.0/10 (CGNAT — RFC 6598)
                b0 == 100 && b1 in 64..127 -> true
                // 240.0.0.0/4 (reserved) + 255.255.255.255 (broadcast)
                b0 >= 240 -> true
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
