package com.arc.reactor.tracing

/**
 * Arc Reactor의 경량 트레이싱 추상화.
 *
 * OpenTelemetry에 대한 하드 의존성 없이 스팬 기반 계측을 제공한다.
 * OTel이 없거나 트레이싱이 비활성화된 경우, [NoOpArcReactorTracer]가 사용되어
 * 모든 연산이 오버헤드 없는 노옵(no-op)으로 처리된다.
 *
 * WHY: OTel은 선택적 의존성(compileOnly)이므로 런타임에 존재하지 않을 수 있다.
 * 이 추상화를 통해 트레이싱 가용 여부와 무관하게 코어 로직이 동일하게 동작하며,
 * OTel 존재 시 자동으로 분산 트레이싱을 활성화한다.
 *
 * 구현체는 반드시 스레드 안전해야 한다.
 *
 * @see NoOpArcReactorTracer OTel 미사용 시 기본 구현
 * @see com.arc.reactor.tracing.impl.OtelArcReactorTracer OTel 기반 구현
 */
interface ArcReactorTracer {

    /**
     * 지정된 이름과 선택적 속성으로 새 스팬을 시작한다.
     *
     * 반환된 [SpanHandle]은 스팬을 종료하고 관련 리소스를 해제하기 위해
     * 반드시 닫아야 한다 ([SpanHandle.close] 또는 use 블록 사용).
     *
     * @param name 스팬 이름 (예: "arc.agent.request")
     * @param attributes 스팬에 첨부할 초기 키-값 속성
     * @return 활성 [SpanHandle]
     */
    fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): SpanHandle

    /**
     * 활성 스팬에 대한 핸들.
     *
     * 이 인터페이스의 구현체는 [close] 이후에도 안전하게 호출 가능해야 한다.
     */
    interface SpanHandle : AutoCloseable {

        /** 스팬에 에러를 기록한다. [close] 전에 호출해야 한다. */
        fun setError(e: Throwable)

        /** 활성 스팬에 단일 속성을 추가하거나 갱신한다. */
        fun setAttribute(key: String, value: String)

        /** 스팬을 종료한다. 멱등성 — 여러 번 호출해도 안전하다. */
        override fun close()
    }
}

/**
 * OpenTelemetry가 사용 불가능하거나 트레이싱이 비활성화된 경우 사용되는 노옵 트레이서.
 *
 * 모든 연산이 비어있으며 할당이 없다 (모든 [startSpan] 호출에 대해
 * 동일한 [NoOpSpanHandle] 싱글턴이 반환된다).
 *
 * WHY: OTel 미사용 환경에서도 트레이싱 관련 코드를 if-else로 분기하지 않도록
 * 널 오브젝트 패턴(Null Object Pattern)을 적용한다.
 * 싱글턴 핸들을 반환하여 GC 부담을 없앤다.
 */
class NoOpArcReactorTracer : ArcReactorTracer {

    override fun startSpan(name: String, attributes: Map<String, String>): ArcReactorTracer.SpanHandle =
        NoOpSpanHandle

    /** 싱글턴 노옵 스팬 핸들. 모든 메서드가 빈 구현이다. */
    private object NoOpSpanHandle : ArcReactorTracer.SpanHandle {
        override fun setError(e: Throwable) = Unit
        override fun setAttribute(key: String, value: String) = Unit
        override fun close() = Unit
    }
}
