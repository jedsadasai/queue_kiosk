package com.queuekiosk.data

import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Every failure of the server API, classified so the UI can show the right message. */
class ApiException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: kind.name, cause) {

    enum class Kind {
        /** Base URL could not be parsed. */
        INVALID_URL,

        /** Login returned 401 (fleQ: "Username/Password อาจไม่ถูกต้อง"). */
        BAD_CREDENTIALS,

        /** A token-protected call is still rejected after re-login. */
        UNAUTHORIZED,

        /** Non-2xx status (fleQ showed "url อาจไม่ถูกต้อง" for 500). */
        SERVER,

        /** No connection, DNS failure, timeout... */
        NETWORK,

        /** 2xx, but the body is not what the contract says (or not JSON at all). */
        BAD_RESPONSE,

        /** No stored credentials / workflow. The user must log in. */
        NOT_LOGGED_IN,
    }
}

/** Runs a Retrofit call and maps transport/parse errors to [ApiException]. */
internal suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    throw e
} catch (e: MalformedJsonException) {
    throw ApiException(ApiException.Kind.BAD_RESPONSE, e.message, e)
} catch (e: JsonParseException) {
    throw ApiException(ApiException.Kind.BAD_RESPONSE, e.message, e)
} catch (e: IOException) {
    throw ApiException(ApiException.Kind.NETWORK, e.message, e)
}
