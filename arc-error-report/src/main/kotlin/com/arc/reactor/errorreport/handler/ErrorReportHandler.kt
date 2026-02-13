package com.arc.reactor.errorreport.handler

import com.arc.reactor.errorreport.model.ErrorReportRequest

/**
 * Interface for handling incoming error reports.
 *
 * Implement this interface to customize how error reports are processed.
 * Register as a bean to override the default behavior.
 */
interface ErrorReportHandler {

    /**
     * Handles an incoming error report asynchronously.
     *
     * @param requestId Unique identifier for this error report request
     * @param request The error report data from the production server
     */
    suspend fun handle(requestId: String, request: ErrorReportRequest)
}
