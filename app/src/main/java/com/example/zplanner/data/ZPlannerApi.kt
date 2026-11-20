package com.example.zplanner.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ZPlannerApi {
    @POST("auth/register")
    suspend fun register(@Body credentials: Credentials): Response<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body credentials: Credentials): Response<AuthResponse>

    @POST("auth/google")
    suspend fun google(@Body request: GoogleAuthRequest): Response<AuthResponse>

    @POST("auth/google/link")
    suspend fun linkGoogle(@Body request: GoogleLinkRequest): Response<Unit>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("tasks")
    suspend fun tasks(@Query("date") date: String): Response<TasksResponse>

    @POST("tasks")
    suspend fun create(@Body task: ApiTask): Response<TaskResponse>

    @PUT("tasks/{id}")
    suspend fun update(@Path("id") id: Long, @Body task: ApiTask): Response<TaskResponse>

    @DELETE("tasks/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>

    @GET("events")
    suspend fun events(@Query("from") from: String, @Query("to") to: String): Response<EventsResponse>

    @POST("events")
    suspend fun createEvent(@Body event: ApiEventRequest): Response<EventResponse>

    @PUT("events/{id}")
    suspend fun updateEvent(@Path("id") id: Long, @Body event: ApiEventRequest): Response<EventResponse>

    @DELETE("events/{id}")
    suspend fun deleteEvent(@Path("id") id: Long): Response<Unit>
}
