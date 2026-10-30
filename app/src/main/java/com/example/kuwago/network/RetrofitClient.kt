package com.example.kuwago.network

import com.example.kuwago.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val BASE_URL = if (BuildConfig.API_URL.endsWith("/")) {
        BuildConfig.API_URL
    } else {
        "${BuildConfig.API_URL}/"
    }

    private val FALLBACK_BASE_URL = if (BuildConfig.FALLBACK_API_URL.endsWith("/")) {
        BuildConfig.FALLBACK_API_URL
    } else {
        "${BuildConfig.FALLBACK_API_URL}/"
    }

    private val fallbackHttpUrl = FALLBACK_BASE_URL.toHttpUrlOrNull()

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
    }

    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /**
     * Checks whether a response from the primary (Vercel) backend indicates the deployment
     * is out of quota, disabled, rate-limited, or experiencing a server error.
     */
    private fun shouldFailover(response: Response): Boolean {
        val code = response.code
        val vercelError = response.header("X-Vercel-Error")
        return !vercelError.isNullOrEmpty() ||
            code == 402 || // Payment Required / Vercel DEPLOYMENT_DISABLED when free tier runs out
            code == 403 ||
            code == 404 ||
            code == 429 ||
            code in 500..599
    }

    /**
     * Rewrites the request URL to target the fallback backend (Render) while preserving
     * path segments and query parameters.
     */
    private fun rewriteToFallback(request: Request): Request? {
        val targetBase = fallbackHttpUrl ?: return null
        if (request.url.host.equals(targetBase.host, ignoreCase = true)) {
            return null // Already targeting fallback
        }
        val newUrl = request.url.newBuilder()
            .scheme(targetBase.scheme)
            .host(targetBase.host)
            .port(targetBase.port)
            .build()
        return request.newBuilder()
            .url(newUrl)
            .build()
    }

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            val credential = BuildConfig.BACKEND_CREDENTIAL
            if (credential.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $credential")
            }
            chain.proceed(requestBuilder.build())
        }
        .addInterceptor { chain ->
            val primaryRequest = chain.request()

            // 1. Try primary backend (Vercel) first with a shorter timeout so we fail over quickly if unresponsive
            var response: Response? = null
            var primaryError: IOException? = null
            try {
                response = chain
                    .withConnectTimeout(15, TimeUnit.SECONDS)
                    .withReadTimeout(20, TimeUnit.SECONDS)
                    .withWriteTimeout(20, TimeUnit.SECONDS)
                    .proceed(primaryRequest)
            } catch (e: IOException) {
                primaryError = e
            }

            // If primary succeeded and does not require failover, return immediately
            if (response != null && !shouldFailover(response)) {
                return@addInterceptor response
            }

            // 2. Primary failed or ran out of quota (e.g. HTTP 402 DEPLOYMENT_DISABLED) -> Fail over to Render
            val fallbackRequest = rewriteToFallback(primaryRequest)
            if (fallbackRequest == null) {
                if (response != null) return@addInterceptor response
                throw primaryError ?: IOException("Primary backend request failed and no fallback configured")
            }

            response?.close()

            // Execute against fallback (Render) with full 120s timeout and cold-start retry loop
            var fallbackResponse = chain
                .withConnectTimeout(120, TimeUnit.SECONDS)
                .withReadTimeout(120, TimeUnit.SECONDS)
                .withWriteTimeout(120, TimeUnit.SECONDS)
                .proceed(fallbackRequest)

            var tryCount = 0
            val maxRetries = 2
            while (!fallbackResponse.isSuccessful &&
                (fallbackResponse.code == 502 || fallbackResponse.code == 503 || fallbackResponse.code == 504) &&
                tryCount < maxRetries
            ) {
                tryCount++
                fallbackResponse.close()
                try {
                    Thread.sleep(2000L * tryCount)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                fallbackResponse = chain
                    .withConnectTimeout(120, TimeUnit.SECONDS)
                    .withReadTimeout(120, TimeUnit.SECONDS)
                    .withWriteTimeout(120, TimeUnit.SECONDS)
                    .proceed(fallbackRequest)
            }

            fallbackResponse
        }
        .addInterceptor(logging)
        .build()

    fun resetConnectionPool() {
        try {
            connectionPool.evictAll()
        } catch (e: Exception) {
            // ignore
        }
    }

    val instance: SmishingApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(SmishingApiService::class.java)
    }
}
