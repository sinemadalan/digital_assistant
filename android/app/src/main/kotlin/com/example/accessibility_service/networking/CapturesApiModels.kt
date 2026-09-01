package com.example.accessibility_service.networking

data class CapturesResponse(
    val accepted: Int,
    val skipped: Int,
    val config: CapturesConfig,
    val commands: List<RawCaptureCommand>,
)

data class CapturesConfig(
    val batchSize: Int,
    val flushSeconds: Int,
)

/** A command preserved as JSON. Phase 3 deliberately does not interpret or execute it. */
data class RawCaptureCommand(val json: String)

sealed interface CapturesApiResult {
    data class Success(val response: CapturesResponse) : CapturesApiResult

    data object Unauthorized : CapturesApiResult

    data object Unprocessable : CapturesApiResult

    data object ServiceUnavailable : CapturesApiResult

    data class OtherHttpError(val statusCode: Int) : CapturesApiResult

    data object Timeout : CapturesApiResult

    data class NetworkError(val cause: Throwable) : CapturesApiResult

    data class InvalidRequest(val reason: InvalidRequestReason) : CapturesApiResult

    data class InvalidResponse(val reason: String) : CapturesApiResult
}

enum class InvalidRequestReason {
    BLANK_TOKEN,
    EMPTY_EVENTS,
    TOO_MANY_EVENTS,
}
