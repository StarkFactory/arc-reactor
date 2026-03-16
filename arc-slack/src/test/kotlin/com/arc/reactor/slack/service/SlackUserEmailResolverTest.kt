package com.arc.reactor.slack.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class SlackUserEmailResolverTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var resolver: SlackUserEmailResolver

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .defaultHeader("Authorization", "Bearer test-token")
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .build()

        resolver = SlackUserEmailResolver(
            botToken = "test-token",
            cacheTtlSeconds = 3600,
            cacheMaxEntries = 1000,
            webClient = webClient
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `email from users info를 해결한다`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "user": {
                        "profile": {
                          "email": "alice@example.com"
                        }
                      }
                    }
                    """.trimIndent()
                )
        )

        val email = resolver.resolveEmail("U123")

        email shouldBe "alice@example.com"
        mockServer.requestCount shouldBe 1
        mockServer.takeRequest().path shouldBe "/users.info"
    }

    @Test
    fun `users info is not ok일 때 null를 반환한다`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":"missing_scope"}""")
        )

        val email = resolver.resolveEmail("U123")

        email shouldBe null
        mockServer.requestCount shouldBe 1
    }

    @Test
    fun `cache for repeated user lookups를 사용한다`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "user": {
                        "profile": {
                          "email": "alice@example.com"
                        }
                      }
                    }
                    """.trimIndent()
                )
        )

        val first = resolver.resolveEmail("U123")
        val second = resolver.resolveEmail("U123")

        first shouldBe "alice@example.com"
        second shouldBe "alice@example.com"
        mockServer.requestCount shouldBe 1
    }
}
