package com.arc.reactor.errorreport.handler

import com.arc.reactor.errorreport.model.ErrorReportRequest

/**
 * 수신된 오류 리포트를 처리하는 인터페이스.
 *
 * 이 인터페이스를 구현하여 오류 리포트 처리 방식을 커스터마이즈할 수 있다.
 * 커스텀 빈으로 등록하면 기본 동작([DefaultErrorReportHandler])을 교체한다.
 *
 * @see DefaultErrorReportHandler AgentExecutor에 위임하는 기본 구현
 */
interface ErrorReportHandler {

    /**
     * 수신된 오류 리포트를 비동기로 처리한다.
     *
     * @param requestId 이 오류 리포트 요청의 고유 식별자
     * @param request 프로덕션 서버에서 전송된 오류 리포트 데이터
     */
    suspend fun handle(requestId: String, request: ErrorReportRequest)
}
