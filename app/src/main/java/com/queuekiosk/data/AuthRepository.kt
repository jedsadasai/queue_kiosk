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
     * Retries [reLogin] every [retryDelayMs] until it succeeds, as fleQ's ReLoginCall.tryToReLogin
     * does (5 s). It gives up immediately on errors that retrying cannot fix (bad credentials, no stored login).
     */
    suspend fun reLoginWithRetry(
        retryDelayMs: Long = RETRY_DELAY_MS,
        onRetry: (ApiException) -> Unit = {},
    ): LoginData {
        while (true) {
            try {
                return reLogin()
            } catch (e: ApiException) {
                if (e.kind == ApiException.Kind.BAD_CREDENTIALS || e.kind == ApiException.Kind.NOT_LOGGED_IN) throw e
                onRetry(e)
                delay(retryDelayMs)
            }
        }
    }

    companion object {
        /** fleQ compared against FirebaseAnalytics.Param.SUCCESS, which is the string "success". */
        const val STATUS_SUCCESS = "success"
        const val RETRY_DELAY_MS = 5_000L
    }
}
