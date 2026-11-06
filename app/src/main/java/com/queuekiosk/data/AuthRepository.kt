package com.queuekiosk.data

import com.queuekiosk.data.api.ApiClientFactory
import com.queuekiosk.data.api.LoginData
import kotlinx.coroutines.delay

/**
 * Login and silent re-login. Ports fleQ's LoginCall.java and ReLoginCall.java.
 */
class AuthRepository(
    private val session: SessionStore,
    private val api: ApiClientFactory,
) {

    /**
     * POST {baseUrl}/api/v1/login. On success it stores the base URL, credentials,
     * "<token_type> <token>" and workflow.id, as fleQ's LoginCall.onResponse does.
     * Unlike fleQ, nothing is saved if the login fails.
     */
    suspend fun login(baseUrl: String, username: String, password: String): LoginData {
        val service = api.service(baseUrl)
        val response = apiCall { service.login(username, password) }

        when {
            response.code() == 401 -> throw ApiException(ApiException.Kind.BAD_CREDENTIALS, "HTTP 401")
            !response.isSuccessful -> throw ApiException(ApiException.Kind.SERVER, "HTTP ${response.code()}")
        }

        val body = response.body()
        val data = body?.data
        val token = data?.token
        val workflowId = data?.workflow?.id
        if (body?.status != STATUS_SUCCESS || data == null || token.isNullOrBlank() || workflowId == null) {
            throw ApiException(
                ApiException.Kind.BAD_RESPONSE,
                "Login response must have status=success, data.token and data.workflow.id",
            )
        }

        session.baseUrl = baseUrl.trim()
        session.username = username
        session.password = password
        session.token = listOfNotNull(data.tokenType?.trim()?.takeIf { it.isNotEmpty() }, token.trim())
            .joinToString(" ")
        session.workflowId = workflowId
        session.workflowName = data.workflow?.name.orEmpty()
        session.branchName = data.workflow?.branchName ?: data.branch.orEmpty()
        return data
    }

    /** Re-login with stored credentials (fleQ: ReLoginCall.doLogin). */
    suspend fun reLogin(): LoginData {
        if (session.baseUrl.isBlank() || session.username.isBlank() || session.password.isEmpty()) {
            throw ApiException(ApiException.Kind.NOT_LOGGED_IN)
        }
        return login(session.baseUrl, session.username, session.password)
    }

    /**
     * Retries [reLogin] until it succeeds, as fleQ's ReLoginCall.tryToReLogin does. It gives up
     * immediately on errors that retrying cannot fix (bad credentials, no stored login).
     *
     * fleQ waited 5 s between attempts. Here the first retries come sooner ([RETRY_DELAYS_MS]),
     * because right after power-on the server is often only a moment away (Wi-Fi or DNS still
     * coming up). [beforeAttempt] runs before every attempt, e.g. to wait until a network exists.
     */
    suspend fun reLoginWithRetry(
        retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
        beforeAttempt: suspend () -> Unit = {},
        onRetry: (ApiException) -> Unit = {},
    ): LoginData {
        var attempt = 0
        while (true) {
            beforeAttempt()
            try {
                return reLogin()
            } catch (e: ApiException) {
                if (e.kind == ApiException.Kind.BAD_CREDENTIALS || e.kind == ApiException.Kind.NOT_LOGGED_IN) throw e
                onRetry(e)
                delay(retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)])
                attempt++
            }
        }
    }

    companion object {
        /** fleQ compared against FirebaseAnalytics.Param.SUCCESS, which is the string "success". */
        const val STATUS_SUCCESS = "success"
        /** Waits between re-login attempts. The last one repeats (fleQ's fixed 5 s). */
        val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L)
    }
}
