package com.mythara.minimax

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Factory for MiniMax network clients. Two flavors:
 *  - [retrofit] — for the non-streaming `/models` validation call. Builds
 *    a fresh Retrofit each invocation because the base URL (region) and
 *    API key (Bearer header) are both user-mutable settings.
 *  - [okHttp]   — naked OkHttp client for SSE streaming. Same auth
 *    interceptor + `readTimeout(0)` so long generations don't get cut.
 *
 * Both share a single [Json] configured for forgiving decoding — MiniMax
 * occasionally adds new fields ahead of docs; ignoring unknown keys keeps
 * us from breaking on a Tuesday.
 */
class MiniMaxClient(
    private val apiKey: String,
    private val region: Region,
) {
    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()
        chain.proceed(req)
    }

    private val logging = HttpLoggingInterceptor().apply {
        // BODY would leak the API key into logcat; use BASIC for headers + method/path.
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            // readTimeout(0) means "no read timeout" — required for SSE
            // long streams. The Retrofit-driven /models call short-circuits
            // because it gets a full response in one shot.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val retrofit: MiniMaxApi by lazy {
        Retrofit.Builder()
            .baseUrl(region.baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MiniMaxApi::class.java)
    }

    /**
     * Suspends to round-trip `GET /models`. Returns `Result.success` on
     * 2xx with at least one model in the response, `Result.failure` with
     * a [ApiException] otherwise. Used by Settings → "Validate" button.
     */
    suspend fun validateKey(): Result<List<String>> = runCatching {
        val res = retrofit.listModels()
        if (res.isSuccessful) {
            res.body()?.data?.map { it.id }.orEmpty()
        } else {
            val mapped = ErrorMapper.fromHttp(res.code(), res.errorBody()?.string())
            throw ApiException(mapped)
        }
    }

    companion object {
        /**
         * Shared JSON config. The non-obvious choice is `encodeDefaults = true`
         * so that default-valued fields (notably `Tool.type = "function"` and
         * `stream = true`) are serialised on the wire. With encodeDefaults=false
         * MiniMax received tools without a `type` field and rejected the
         * request with `(2013) invalid tool type`. Null fields are still
         * dropped via `explicitNulls = false`.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }
    }
}

/** Thrown by [MiniMaxClient] when the API returns a non-2xx response. */
class ApiException(val mapped: ErrorMapper.Mapped) :
    RuntimeException("MiniMax ${mapped.httpStatus} ${mapped.code ?: ""}: ${mapped.message}")
