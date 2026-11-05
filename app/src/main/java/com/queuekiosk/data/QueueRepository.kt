package com.queuekiosk.data

import com.queuekiosk.data.api.AddQueueResponse
import com.queuekiosk.data.api.ApiClientFactory
import retrofit2.Response
import java.util.Date

/** What gets printed on the queue ticket. */
data class QueueTicket(
    val number: String,
    val total: String?,
    val building: String?,
    val doctor: String?,
    val urlQr: String?,
    val workflowName: String?,
    val issuedAt: Date,
) {
    companion object {
        /** Used for the "test print" button in printer setup. */
        fun sample(issuedAt: Date = Date()) = QueueTicket(
            number = "A001",
            total = "3",
            building = "TEST PRINT",
            doctor = null,
            urlQr = "https://example.com/queue/A001",
            workflowName = null,
            issuedAt = issuedAt,
        )
    }
}

/**
 * Creates a queue number on the server. Ports fleQ's AddQueueCall.java.
 *
 * fleQ re-logged in before *every* button press (MainActivity -> ReLoginCall -> startWorking ->
 * AddQueueCall). Here we only re-login when the server rejects the token (401), then retry once.
 */
class QueueRepository(
    private val session: SessionStore,
    private val api: ApiClientFactory,
    private val auth: AuthRepository,
    private val clock: () -> Date = { Date() },
) {

    suspend fun addQueue(): QueueTicket {
        if (session.workflowId < 0 || session.baseUrl.isBlank()) {
            throw ApiException(ApiException.Kind.NOT_LOGGED_IN)
        }

        var response = request()
        if (response.code() == 401) {
            auth.reLogin()
            response = request()
        }

        when {
            response.code() == 401 -> throw ApiException(ApiException.Kind.UNAUTHORIZED, "HTTP 401 after re-login")
            !response.isSuccessful -> throw ApiException(ApiException.Kind.SERVER, "HTTP ${response.code()}")
        }

        val data = response.body()?.data
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "add-queue response has no data")
        val number = data.number?.takeIf { it.isNotBlank() }
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "add-queue response has no number")

        return QueueTicket(
            number = padNumber(number),
            total = data.total,
            building = data.building,
            doctor = data.doctor,
            urlQr = data.urlQr,
            workflowName = data.workflowName ?: session.workflowName.ifBlank { null },
            issuedAt = clock(),
        )
    }

    private suspend fun request(): Response<AddQueueResponse> =
        apiCall { api.service(session.baseUrl).addQueue(session.workflowId.toString()) }

    companion object {
        /** fleQ MainActivity.createQueueCard: a 3-character number gets a leading "0". */
        fun padNumber(number: String): String = if (number.length == 3) "0$number" else number
    }
}
