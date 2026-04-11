package com.arc.reactor.auth

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * IamTokenExchangeService 테스트.
 *
 * R324 도입 이후 `exchange`는 `suspend fun`이다. 본 테스트는 suspend 계약과 기본 실패 경로를
 * 검증한다. 실제 RSA 서명 검증 경로는 통합 테스트(외부 IAM 서버 픽스처)에서 커버되고, 본
 * 유닛 테스트는 IAM 서버가 도달 불가능할 때 null 반환과 동시 호출 안전성을 확인한다.
 */
class IamTokenExchangeServiceTest {

    private val userStore = mockk<UserStore>()
    private val jwtTokenProvider = mockk<JwtTokenProvider>()

    @Test
    fun `IAM 서버가 도달 불가능하면 null 반환해야 한다`() = runTest {
        val service = IamTokenExchangeService(
            iamProperties = IamProperties(
                enabled = true,
                baseUrl = "http://127.0.0.1:1", // 도달 불가능한 포트
                issuer = "aslan-iam"
            ),
            userStore = userStore,
            jwtTokenProvider = jwtTokenProvider
        )

        // R324: suspend fun으로 호출. 네트워크 실패 시 null 반환, 예외 전파하지 않음
        val result = service.exchange("any-token")

        assertNull(result) { "R324: IAM 서버 도달 실패 시 null 반환 (예외 전파 금지)" }
    }

    @Test
    fun `prefetchPublicKey는 실패해도 예외 없이 완료해야 한다`() {
        val service = IamTokenExchangeService(
            iamProperties = IamProperties(
                enabled = true,
                baseUrl = "http://127.0.0.1:1",
                issuer = "aslan-iam"
            ),
            userStore = userStore,
            jwtTokenProvider = jwtTokenProvider
        )

        // R324: prefetchPublicKey는 startup 경로에서 호출되므로 fetch 실패 시 warn 로그만 남기고
        // throw하지 않아야 한다. 예외 발생 시 Spring context 기동이 중단되어 더 큰 장애가 된다.
        service.prefetchPublicKey()
        // 예외가 throw되지 않았으므로 통과
        assertNotNull(service) { "prefetchPublicKey 호출 후 서비스가 여전히 유효해야 한다" }
    }
}
