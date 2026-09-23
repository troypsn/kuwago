package com.example.kuwago.network

import com.example.kuwago.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private val BASE_URL = BuildConfig.API_URL

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(75, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            val token = BuildConfig.KWAGO_API_KEY
            if (token.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }
        .addInterceptor { chain ->
            val request = chain.request()
            var response = chain.proceed(request)
            var tryCount = 0
            val maxRetries = 2
            // If cloud server is waking up (502 Bad Gateway, 503 Unavailable, 504 Timeout), retry with brief backoff
            while (!response.isSuccessful && (response.code == 502 || response.code == 503 || response.code == 504) && tryCount < maxRetries) {
                tryCount++
                response.close()
                try {
                    Thread.sleep(2000L * tryCount)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                response = chain.proceed(request)
            }
            response
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
