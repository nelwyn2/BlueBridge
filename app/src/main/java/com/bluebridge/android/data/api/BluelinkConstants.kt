package com.bluebridge.android.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

object BluelinkConstants {
    const val BASE_URL_US_HYUNDAI = "https://api.telematics.hyundaiusa.com/"
    const val BASE_URL_US_KIA = "https://kiaconnect.com/"
    const val BASE_URL_CA_HYUNDAI = "https://api.hyundaicanada.com/"
    const val BASE_URL_EU = "https://prd.eu-ccapi.hyundai.com:8080/"
    const val BASE_URL_AU = "https://prd.aus-ccapi.hyundai.com:8080/"

    // Public client credentials from the Bluelink app (via bluelinky)
    const val CLIENT_ID = "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920"
    const val CLIENT_SECRET = "v558o935-6nne-423i-baa8"
    const val API_HOST = "api.telematics.hyundaiusa.com"
    const val APP_ID = "14d5efbe-c194-4a5c-af66-e0ba8c8f4c80"

    // Kia UVO credentials
    const val KIA_CLIENT_ID = "L5hc7010"
    const val KIA_CLIENT_SECRET = "mcnpc9dEZlfLfFaHR18zAMBNBqNMcDdcOBOLWOlBqjGDCcpkIj"

    const val TIMEOUT_SECONDS = 30L
    const val COMMAND_TIMEOUT_SECONDS = 60L
}

@Singleton
class ApiClient(private val baseUrl: String = BluelinkConstants.BASE_URL_US_HYUNDAI) {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "okhttp/4.12.0")
                .addHeader("client_id", BluelinkConstants.CLIENT_ID)
                .addHeader("clientSecret", BluelinkConstants.CLIENT_SECRET)
                .addHeader("appId", BluelinkConstants.APP_ID)
                .addHeader("deviceType", "Android")
                .addHeader("from", "SPA")
                .addHeader("language", "0")
                .addHeader("offset", "-5")
                .addHeader("to", "ISS")
                .addHeader("encryptFlag", "false")
                .build()
            chain.proceed(request)
        }
        .connectTimeout(BluelinkConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(BluelinkConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(BluelinkConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val apiService: BluelinkApiService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BluelinkApiService::class.java)
}

// Regional API configuration
enum class Region(val baseUrl: String, val label: String) {
    US_HYUNDAI(BluelinkConstants.BASE_URL_US_HYUNDAI, "USA — Hyundai"),
    US_KIA(BluelinkConstants.BASE_URL_US_KIA, "USA — Kia"),
    CA_HYUNDAI(BluelinkConstants.BASE_URL_CA_HYUNDAI, "Canada — Hyundai"),
    EU(BluelinkConstants.BASE_URL_EU, "Europe"),
    AU(BluelinkConstants.BASE_URL_AU, "Australia / New Zealand")
}
