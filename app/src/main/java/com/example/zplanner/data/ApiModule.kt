package com.example.zplanner.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {
    private const val BASE_URL = "https://zplanner-api.lucbao045.workers.dev/"

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context) = TokenStore(context)

    @Provides
    @Singleton
    fun provideApi(tokenStore: TokenStore): ZPlannerApi {
        val auth = Interceptor { chain ->
            val request = chain.request().newBuilder().apply {
                tokenStore.read()?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder().addInterceptor(auth).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZPlannerApi::class.java)
    }
}
