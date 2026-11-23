package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@HiltViewModel
class EventViewModel @Inject constructor(private val api: ZPlannerApi, private val tokenStore: TokenStore? = null) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableStateFlow<List<ApiEvent>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _overlap = MutableStateFlow<List<ApiEvent>?>(null)
    private val _saving = MutableStateFlow(false)
    private var pending: ApiEvent? = null
    private var visibleWeekStart: LocalDate? = null
    private var sessionGeneration = 0L
    private var latestLoadId = 0L
    val events: StateFlow<List<ApiEvent>> = _events
    val error: StateFlow<String?> = _error
    val overlap: StateFlow<List<ApiEvent>?> = _overlap
    val sessionExpired = MutableStateFlow(false)

    fun clearOverlap() { _overlap.value = null; pending = null }
    fun clearData() {
        sessionGeneration++
        latestLoadId++
        pending = null
        _saving.value = false
        _events.value = emptyList()
        _error.value = null
        clearOverlap()
        visibleWeekStart = null
    }
    fun confirmOverlap(onComplete: (Boolean) -> Unit = {}) {
        if (_saving.value) return
        val event = pending ?: run { onComplete(false); return }
        save(event, confirmOverlap = true, onComplete = onComplete)
    }

    fun loadWeek(selectedDate: LocalDate) = viewModelScope.launch {
        val weekStart = selectedDate.with(java.time.DayOfWeek.MONDAY)
        visibleWeekStart = weekStart
        val requestGeneration = sessionGeneration
        val requestId = ++latestLoadId
        _events.value = emptyList()
        val zone = ZoneId.systemDefault()
        val from = weekStart.atStartOfDay(zone).toInstant().toApiTimestamp()
        val to = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toApiTimestamp()
        val requestToken = tokenStore?.read() ?: "unit-test-session"
        val r = runCatching { api.events(from, to) }.getOrNull()
        val stillCurrent = requestGeneration == sessionGeneration && requestId == latestLoadId &&
            visibleWeekStart == weekStart && tokenStore?.read() == requestToken
        if (!stillCurrent) return@launch
        when {
            r?.code() == 401 -> sessionExpired.value = true
            r?.isSuccessful == true -> _events.value = r.body()?.events.orEmpty()
            else -> _error.value = "Không thể tải sự kiện (${r?.code() ?: "kết nối"})."
        }
    }

    fun save(event: ApiEvent, confirmOverlap: Boolean = false, onComplete: (Boolean) -> Unit = {}) = viewModelScope.launch {
        if (_saving.value) return@launch
        _saving.value = true
        try {
            _error.value = null
            val requestToken = tokenStore?.read() ?: "unit-test-session"
            val requestGeneration = sessionGeneration
            val request = ApiEventRequest(event.title, event.description, event.location, event.startAt, event.endAt, event.reminderMinutes, confirmOverlap)
            val r = runCatching { if (event.id == 0L) api.createEvent(request) else api.updateEvent(event.id, request) }.getOrNull()
            val stillCurrent = requestGeneration == sessionGeneration && tokenStore?.read() == requestToken
            if (!stillCurrent) { onComplete(false); return@launch }
            when {
                r?.code() == 401 -> {
                    if (confirmOverlap) { pending = null; _overlap.value = null }
                    sessionExpired.value = true
                    _error.value = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
                    onComplete(false)
                }
                r?.code() == 409 -> {
                    val parsed = runCatching { json.decodeFromString<ErrorResponse>(r.errorBody()?.string().orEmpty()) }.getOrNull()
                    if (parsed?.error?.code == "EVENT_OVERLAP") {
                        pending = event
                        _overlap.value = parsed.error.overlaps
                        onComplete(false)
                    } else {
                        if (confirmOverlap) { pending = null; _overlap.value = null }
                        _error.value = parsed?.error?.message ?: "Sự kiện bị từ chối (HTTP 409)."
                        onComplete(false)
                    }
                }
                r?.isSuccessful == true -> {
                    pending = null
                    _overlap.value = null
                    _error.value = null
                    visibleWeekStart?.let { loadWeek(it) }
                    onComplete(true)
                }
                else -> {
                    if (confirmOverlap) { pending = null; _overlap.value = null }
                    _error.value = "Không thể lưu sự kiện (${r?.code() ?: "lỗi kết nối"})."
                    onComplete(false)
                }
            }
        } finally {
            _saving.value = false
        }
    }

    fun delete(event: ApiEvent) = viewModelScope.launch {
        val r = runCatching { api.deleteEvent(event.id) }.getOrNull()
        when {
            r?.code() == 401 -> sessionExpired.value = true
            r?.isSuccessful == true -> visibleWeekStart?.let { loadWeek(it) }
            else -> _error.value = "Không thể xóa sự kiện (${r?.code() ?: "kết nối"})."
        }
    }
}

internal fun Instant.toApiTimestamp(): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(this)