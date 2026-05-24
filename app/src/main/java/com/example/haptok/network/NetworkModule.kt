package com.example.haptok.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual dependency-injection module providing the networking stack
 * as a thread-safe singleton.
 *
 * The backend base URL is stored in [SharedPreferences] and defaults
 * to [DEFAULT_BASE_URL] (emulator loopback).
 */
class NetworkModule private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkModule"
        private const val PREFS_NAME = "haptok_settings"
        const val PREF_KEY_BASE_URL = "server_url"
        const val DEFAULT_BASE_URL = "http://192.168.1.8:8000"

        @Volatile
        private var INSTANCE: NetworkModule? = null

        fun getInstance(context: Context): NetworkModule =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkModule(context.applicationContext).also {
                    INSTANCE = it
                    it.rebuildRetrofit()
                    Log.i(TAG, "Initialised with base URL: ${it.baseUrl}")
                }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Fully configured Moshi instance with Kotlin support. */
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    /** OkHttpClient with logging and generous timeouts for video uploads. */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor { Log.d(TAG, it) }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Current base URL from preferences. */
    val baseUrl: String
        get() = prefs.getString(PREF_KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    @Volatile
    private var _apiService: HaptokApiService? = null

    /** Retrofit-generated API service. */
    val apiService: HaptokApiService
        get() = _apiService ?: synchronized(this) {
            _apiService ?: createApiService().also { _apiService = it }
        }

    /**
     * Update the server base URL and rebuild the Retrofit instance.
     */
    @Synchronized
    fun updateBaseUrl(url: String) {
        prefs.edit().putString(PREF_KEY_BASE_URL, url).apply()
        rebuildRetrofit()
        Log.i(TAG, "Base URL updated to: $url")
    }

    private fun rebuildRetrofit() {
        _apiService = createApiService()
    }

    private fun createApiService(): HaptokApiService {
        val url = baseUrl.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(HaptokApiService::class.java)
    }
}
