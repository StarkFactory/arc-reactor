package com.arc.reactor.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SHA-256 해싱 및 HMAC 유틸리티.
 *
 * ThreadLocal을 사용하여 JCA 프로바이더 조회 비용을 제거하고,
 * 룩업 테이블 기반 hex 인코딩으로 바이트별 String.format 오버헤드를 제거한다.
 */
object HashUtils {

    private val SHA256_DIGEST = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** SHA-256 해시를 hex 문자열로 반환한다. 코루틴 안전 (ThreadLocal). */
    fun sha256Hex(input: String): String {
        val digest = SHA256_DIGEST.get()
        digest.reset()
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    /** HMAC-SHA256을 hex 문자열로 반환한다. */
    fun hmacSha256Hex(secret: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    /** 바이트 배열을 hex 문자열로 변환한다. 룩업 테이블 기반으로 할당 최소화. */
    fun bytesToHex(bytes: ByteArray): String {
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                append(HEX_CHARS[b.toInt() and 0x0F])
            }
        }
    }
}
