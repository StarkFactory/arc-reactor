package com.arc.reactor.support

import kotlinx.coroutines.CancellationException

/**
 * 넓은 범위의 catch 블록에서 코루틴 취소 신호를 재전파한다.
 *
 * WHY: 코루틴의 구조화된 동시성(structured concurrency)에서 CancellationException은
 * 특별한 의미를 가진다. 이를 삼켜버리면(swallow) 상위 코루틴의 취소가 전파되지 않아
 * 리소스 누수와 좀비 코루틴이 발생할 수 있다.
 *
 * CLAUDE.md의 "CancellationException" 규칙:
 * 모든 suspend fun에서 일반 Exception 캐치 전에 반드시 CancellationException을
 * 캐치하여 재전파해야 한다. 이 확장 함수를 사용하면 이 패턴을 간결하게 적용할 수 있다.
 *
 * 사용 예시:
 * ```kotlin
 * try {
 *     someSuspendFunction()
 * } catch (e: Exception) {
 *     e.throwIfCancellation()  // CancellationException이면 여기서 재전파
 *     logger.error(e) { "에러 처리" }  // 그 외 예외만 여기에 도달
 * }
 * ```
 *
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor ReAct 루프에서의 활용
 */
fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}

/**
 * 코루틴 취소 시맨틱을 보존하는 [runCatching] 변형.
 *
 * 표준 [runCatching]은 CancellationException도 캡처하여 Result.failure로 래핑하는데,
 * 이는 구조화된 동시성을 깨뜨린다. 이 함수는 CancellationException을 재전파하고
 * 나머지 예외만 Result.failure로 래핑한다.
 *
 * WHY: suspend 블록에서 안전하게 결과를 캡처하면서도 취소 전파를 보장하기 위함.
 *
 * @param block 실행할 suspend 블록
 * @return 성공 시 Result.success, 비취소 예외 시 Result.failure
 */
suspend inline fun <T> runSuspendCatchingNonCancellation(
    block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: Exception) {
        e.throwIfCancellation()
        Result.failure(e)
    }
}
