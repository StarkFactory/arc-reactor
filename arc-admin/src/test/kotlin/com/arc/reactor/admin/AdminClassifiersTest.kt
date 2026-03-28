package com.arc.reactor.admin

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/** [AdminClassifiers]의 가드 스테이지 분류, 도구 오류 분류, 에러 타입 분류, provider 추론 테스트 */
class AdminClassifiersTest {

    @Nested
    inner class ClassifyGuardStage {

        @Test
        fun `RateLimit 포함 스테이지명은 rate_limit으로 분류된다`() {
            assertEquals("rate_limit", AdminClassifiers.classifyGuardStage("RateLimitGuard")) {
                "RateLimit 키워드가 포함된 스테이지는 rate_limit으로 분류되어야 한다"
            }
        }

        @Test
        fun `ratelimit 소문자도 rate_limit으로 분류된다`() {
            assertEquals("rate_limit", AdminClassifiers.classifyGuardStage("ratelimit-stage")) {
                "대소문자 무관하게 ratelimit은 rate_limit으로 분류되어야 한다"
            }
        }

        @Test
        fun `Injection 포함 스테이지명은 prompt_injection으로 분류된다`() {
            assertEquals("prompt_injection", AdminClassifiers.classifyGuardStage("InjectionDetectionStage")) {
                "Injection 키워드가 포함된 스테이지는 prompt_injection으로 분류되어야 한다"
            }
        }

        @Test
        fun `Classification 포함 스테이지명은 classification으로 분류된다`() {
            assertEquals("classification", AdminClassifiers.classifyGuardStage("ContentClassificationGuard")) {
                "Classification 키워드가 포함된 스테이지는 classification으로 분류되어야 한다"
            }
        }

        @Test
        fun `Permission 포함 스테이지명은 permission으로 분류된다`() {
            assertEquals("permission", AdminClassifiers.classifyGuardStage("PermissionGuardStage")) {
                "Permission 키워드가 포함된 스테이지는 permission으로 분류되어야 한다"
            }
        }

        @Test
        fun `InputValidation 포함 스테이지명은 input_validation으로 분류된다`() {
            assertEquals("input_validation", AdminClassifiers.classifyGuardStage("InputValidationGuard")) {
                "InputValidation 키워드가 포함된 스테이지는 input_validation으로 분류되어야 한다"
            }
        }

        @Test
        fun `Unicode 포함 스테이지명은 unicode_normalization으로 분류된다`() {
            assertEquals("unicode_normalization", AdminClassifiers.classifyGuardStage("UnicodeNormalizationGuard")) {
                "Unicode 키워드가 포함된 스테이지는 unicode_normalization으로 분류되어야 한다"
            }
        }

        @Test
        fun `TopicDrift 포함 스테이지명은 topic_drift으로 분류된다`() {
            assertEquals("topic_drift", AdminClassifiers.classifyGuardStage("TopicDriftGuard")) {
                "TopicDrift 키워드가 포함된 스테이지는 topic_drift으로 분류되어야 한다"
            }
        }

        @Test
        fun `알 수 없는 스테이지명은 other로 분류된다`() {
            assertEquals("other", AdminClassifiers.classifyGuardStage("CustomGuardStage")) {
                "알 수 없는 스테이지명은 other로 폴백되어야 한다"
            }
        }

        @Test
        fun `빈 스테이지명은 other로 분류된다`() {
            assertEquals("other", AdminClassifiers.classifyGuardStage("")) {
                "빈 문자열 스테이지명은 other로 폴백되어야 한다"
            }
        }
    }

    @Nested
    inner class ClassifyToolError {

        @Test
        fun `null 입력은 null을 반환한다`() {
            AdminClassifiers.classifyToolError(null) shouldBe null
        }

        @Test
        fun `timeout 포함 메시지는 timeout으로 분류된다`() {
            assertEquals("timeout", AdminClassifiers.classifyToolError("Request timeout after 30s")) {
                "timeout 키워드가 포함된 메시지는 timeout으로 분류되어야 한다"
            }
        }

        @Test
        fun `TIMEOUT 대문자도 timeout으로 분류된다`() {
            assertEquals("timeout", AdminClassifiers.classifyToolError("TIMEOUT: connection timed out")) {
                "대소문자 무관하게 TIMEOUT은 timeout으로 분류되어야 한다"
            }
        }

        @Test
        fun `connection 포함 메시지는 connection_error로 분류된다`() {
            assertEquals("connection_error", AdminClassifiers.classifyToolError("Connection refused to host")) {
                "connection 키워드가 포함된 메시지는 connection_error로 분류되어야 한다"
            }
        }

        @Test
        fun `permission 포함 메시지는 permission_denied로 분류된다`() {
            assertEquals("permission_denied", AdminClassifiers.classifyToolError("Insufficient permission to access resource")) {
                "permission 키워드가 포함된 메시지는 permission_denied로 분류되어야 한다"
            }
        }

        @Test
        fun `not found 포함 메시지는 not_found로 분류된다`() {
            assertEquals("not_found", AdminClassifiers.classifyToolError("Resource not found at path")) {
                "not found 키워드가 포함된 메시지는 not_found로 분류되어야 한다"
            }
        }

        @Test
        fun `알 수 없는 에러 메시지는 unknown으로 분류된다`() {
            assertEquals("unknown", AdminClassifiers.classifyToolError("Unexpected internal failure")) {
                "알 수 없는 에러 메시지는 unknown으로 폴백되어야 한다"
            }
        }

        @Test
        fun `빈 문자열 에러 메시지는 unknown으로 분류된다`() {
            assertEquals("unknown", AdminClassifiers.classifyToolError("")) {
                "빈 에러 메시지는 unknown으로 폴백되어야 한다"
            }
        }
    }

    @Nested
    inner class ClassifyErrorType {

        @Test
        fun `timeout 포함 메시지는 TimeoutException으로 분류된다`() {
            assertEquals("TimeoutException", AdminClassifiers.classifyErrorType("socket timeout exceeded")) {
                "timeout 키워드가 포함된 메시지는 TimeoutException으로 분류되어야 한다"
            }
        }

        @Test
        fun `connection 포함 메시지는 ConnectionException으로 분류된다`() {
            assertEquals("ConnectionException", AdminClassifiers.classifyErrorType("connection reset by peer")) {
                "connection 키워드가 포함된 메시지는 ConnectionException으로 분류되어야 한다"
            }
        }

        @Test
        fun `permission 포함 메시지는 PermissionDenied로 분류된다`() {
            assertEquals("PermissionDenied", AdminClassifiers.classifyErrorType("no permission to write file")) {
                "permission 키워드가 포함된 메시지는 PermissionDenied로 분류되어야 한다"
            }
        }

        @Test
        fun `알 수 없는 메시지는 RuntimeException으로 분류된다`() {
            assertEquals("RuntimeException", AdminClassifiers.classifyErrorType("something went wrong")) {
                "알 수 없는 에러 메시지는 RuntimeException으로 폴백되어야 한다"
            }
        }

        @Test
        fun `빈 문자열은 RuntimeException으로 분류된다`() {
            assertEquals("RuntimeException", AdminClassifiers.classifyErrorType("")) {
                "빈 에러 메시지는 RuntimeException으로 폴백되어야 한다"
            }
        }
    }

    @Nested
    inner class DeriveProvider {

        @Test
        fun `gpt- 로 시작하는 모델은 openai로 분류된다`() {
            assertEquals("openai", AdminClassifiers.deriveProvider("gpt-4o")) {
                "gpt- 접두사 모델은 openai provider로 분류되어야 한다"
            }
        }

        @Test
        fun `gpt-4-turbo도 openai로 분류된다`() {
            assertEquals("openai", AdminClassifiers.deriveProvider("gpt-4-turbo")) {
                "gpt-4-turbo는 openai provider로 분류되어야 한다"
            }
        }

        @Test
        fun `o1 로 시작하는 모델은 openai로 분류된다`() {
            assertEquals("openai", AdminClassifiers.deriveProvider("o1-preview")) {
                "o1 접두사 모델은 openai provider로 분류되어야 한다"
            }
        }

        @Test
        fun `o3 로 시작하는 모델은 openai로 분류된다`() {
            assertEquals("openai", AdminClassifiers.deriveProvider("o3-mini")) {
                "o3 접두사 모델은 openai provider로 분류되어야 한다"
            }
        }

        @Test
        fun `claude- 로 시작하는 모델은 anthropic으로 분류된다`() {
            assertEquals("anthropic", AdminClassifiers.deriveProvider("claude-3-5-sonnet-20241022")) {
                "claude- 접두사 모델은 anthropic provider로 분류되어야 한다"
            }
        }

        @Test
        fun `gemini- 로 시작하는 모델은 google로 분류된다`() {
            assertEquals("google", AdminClassifiers.deriveProvider("gemini-2.5-flash")) {
                "gemini- 접두사 모델은 google provider로 분류되어야 한다"
            }
        }

        @Test
        fun `mistral 로 시작하는 모델은 mistral로 분류된다`() {
            assertEquals("mistral", AdminClassifiers.deriveProvider("mistral-large-2407")) {
                "mistral 접두사 모델은 mistral provider로 분류되어야 한다"
            }
        }

        @Test
        fun `codestral 로 시작하는 모델은 mistral로 분류된다`() {
            assertEquals("mistral", AdminClassifiers.deriveProvider("codestral-latest")) {
                "codestral 접두사 모델은 mistral provider로 분류되어야 한다"
            }
        }

        @Test
        fun `command 로 시작하는 모델은 cohere로 분류된다`() {
            assertEquals("cohere", AdminClassifiers.deriveProvider("command-r-plus")) {
                "command 접두사 모델은 cohere provider로 분류되어야 한다"
            }
        }

        @Test
        fun `llama 포함 모델은 meta로 분류된다`() {
            assertEquals("meta", AdminClassifiers.deriveProvider("llama-3.1-70b")) {
                "llama 포함 모델은 meta provider로 분류되어야 한다"
            }
        }

        @Test
        fun `알 수 없는 모델은 unknown으로 분류된다`() {
            assertEquals("unknown", AdminClassifiers.deriveProvider("custom-enterprise-model")) {
                "알 수 없는 모델명은 unknown provider로 폴백되어야 한다"
            }
        }

        @Test
        fun `빈 문자열 모델은 unknown으로 분류된다`() {
            assertEquals("unknown", AdminClassifiers.deriveProvider("")) {
                "빈 모델명은 unknown provider로 폴백되어야 한다"
            }
        }

        @Test
        fun `반환값이 null이 아니다`() {
            AdminClassifiers.deriveProvider("any-model") shouldNotBe null
        }
    }
}
