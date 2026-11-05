package com.queuekiosk.data.api

import com.queuekiosk.BuildConfig
import com.queuekiosk.data.ApiException
import com.queuekiosk.data.SessionStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the Retrofit service for a base URL and caches it per URL.
 *
 * Differences from fleQ's APIClient:
 *  - one client for all calls; the token is read from [SessionStore] on every request
 *    (fleQ copied the token into the interceptor when the client was built, so it could go stale)
 *  - HTTP body logging only in debug builds (fleQ logged request bodies, including passwords, in release)
 *  - explicit timeouts
 */
class ApiClientFactory(
    private val session: SessionStore,
    private val debugLogging: Boolean = BuildConfig.DEBUG,
) {
    @Volatile private var cached: Pair<String, ApiService>? = null

    /** @throws ApiException with kind INVALID_URL if [baseUrl] cannot be parsed. */
    fun service(baseUrl: String = session.baseUrl): ApiService {
        val normalized = normalizeBaseUrl(baseUrl)
        cached?.let { (url, service) -> if (url == normalized) return service }
        val service = try {
            build(normalized)
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiException.Kind.INVALID_URL, e.message, e)
        }
        cached = normalized to service
        return service
    }

    private fun build(baseUrl: String): ApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(session))
            .apply {
                if (debugLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader("Authorization")
                    })
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    companion object {
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim()
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}

/** Adds `Authorization: <token_type> <token>` (fleQ: APIClient.getApiClientWithHeader). */
class AuthInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Never send an old token with the login call itself.
        if (request.url.encodedPath.endsWith("/login")) return chain.proceed(request)

        val token = session.token
        if (token.isBlank()) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", token)
                .header("Accept", "application/json")
                .build()
        )
    }
}
