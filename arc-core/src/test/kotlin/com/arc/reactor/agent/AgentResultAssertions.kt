package com.arc.reactor.agent

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 설명적인 AgentResult 단언을 위한 확장 함수.
 *
 * 단순한 `assertTrue(result.success)`를 실패 시 실제 오류를 표시하는 메시지로 대체합니다.
 */

fun AgentResult.assertSuccess(message: String = "") {
    assertTrue(success) {
        "Expected success but got error: ${errorMessage}${if (message.isNotEmpty()) " — $message" else ""}"
    }
}

fun AgentResult.assertFailure(message: String = "") {
    assertFalse(success) {
        "Expected failure but got success with content: ${content}${if (message.isNotEmpty()) " — $message" else ""}"
    }
}

fun AgentResult.assertErrorContains(expected: String) {
    assertFailure()
    assertTrue(errorMessage?.contains(expected, ignoreCase = true) == true) {
        "Expected error containing '$expected' but got: $errorMessage"
    }
}

fun AgentResult.assertErrorCode(expected: AgentErrorCode) {
    assertFailure()
    assertEquals(expected, errorCode) {
        "Expected errorCode $expected but got: $errorCode (errorMessage=$errorMessage)"
    }
}
