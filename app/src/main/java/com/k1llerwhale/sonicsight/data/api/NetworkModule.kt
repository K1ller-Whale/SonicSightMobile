package com.k1llerwhale.sonicsight.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

@Deprecated("Replaced by GrpcModule")
object NetworkModule {

    // TODO: STEP 1 - ENTER YOUR BACKEND URL HERE
    // If testing on Emulator, use "http://10.0.2.2:8000/"
    // If testing on Real Device, use your PC's local IP: "http://192.168.1.X:8000/"
    private const val BASE_URL = "http://192.168.69.187:8000/"

    private val client by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS) // AI Inference takes time!
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}