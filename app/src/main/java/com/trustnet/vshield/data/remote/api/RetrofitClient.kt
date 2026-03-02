package com.trustnet.vshield.data.remote.api

import android.util.Log
import com.trustnet.vshield.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    //URL Railway
    private const val BASE_URL = "https://vshield-backend-production.up.railway.app/"

    val api: VShieldApiService by lazy { create() }

    private fun create(): VShieldApiService {
        val logging = HttpLoggingInterceptor { Log.d("VShieldHTTP", it) }.apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "VShieldAndroid/${BuildConfig.VERSION_NAME}")
                    .build()
                chain.proceed(req)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VShieldApiService::class.java)
    }
}
