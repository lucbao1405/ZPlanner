package com.example.zplanner.data

import kotlinx.serialization.Serializable

@Serializable
data class Credentials(val email: String, val password: String)

@Serializable
data class GoogleAuthRequest(val idToken: String, val nonce: String)

@Serializable
data class GoogleLinkRequest(val idToken: String, val nonce: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Long,
    val user: User
)

@Serializable
data class User(val id: String, val email: String)

@Serializable
data class ApiTask(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val date: String,
    val startTime: String,
    val endTime: String,
    val priority: String = "medium",
    val status: String = "todo"
)

@Serializable
data class TasksResponse(val tasks: List<ApiTask>)

@Serializable
data class TaskResponse(val task: ApiTask)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val overlaps: List<ApiEvent> = emptyList()
)

@Serializable
data class ErrorResponse(val error: ApiError)

@Serializable
data class ApiEvent(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAt: String,
    val endAt: String,
    val reminderMinutes: Int = 0
)

@Serializable
data class ApiEventRequest(
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAt: String,
    val endAt: String,
    val reminderMinutes: Int = 0,
    val confirmOverlap: Boolean = false
)

@Serializable
data class EventsResponse(val events: List<ApiEvent> = emptyList())

@Serializable
data class EventResponse(val event: ApiEvent)
