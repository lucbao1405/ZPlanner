package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@HiltViewModel
class EventViewModel @Inject constructor(private val api: ZPlannerApi) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableStateFlow<List<ApiEvent>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _overlap = MutableStateFlow<List<ApiEvent>?>(null)
    private var pending: ApiEvent? = null
    private var visibleWeekStart: LocalDate? = null
    val events: StateFlow<List<ApiEvent>> = _events
    val error: StateFlow<String?> = _error
    val overlap: StateFlow<List<ApiEvent>?> = _overlap
    val sessionExpired = MutableStateFlow(false)

    fun clearOverlap() { _overlap.value = null; pending = null }
    fun confirmOverlap() { pending?.let { event -> pending = null; save(event, true) }; _overlap.value = null }

    fun loadWeek(selectedDate: LocalDate) = viewModelScope.launch {
        val weekStart = selectedDate.with(java.time.DayOfWeek.MONDAY)
        visibleWeekStart = weekStart
        val zone = ZoneId.systemDefault()
        val from = weekStart.atStartOfDay(zone).toInstant().toString()
        val to = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toString()
        val r = runCatching { api.events(from, to) }.getOrNull()
        when {
            r?.code() == 401 -> sessionExpired.value = true
            r?.isSuccessful == true -> _events.value = r.body()?.events.orEmpty()
            else -> _error.value = "Không thể tải sự kiện (${r?.code() ?: "kết nối"})."
        }
    }

    fun save(event: ApiEvent, confirmOverlap: Boolean = false) = viewModelScope.launch {
        _error.value = null
        val request = ApiEventRequest(event.title, event.description, event.location, event.startAt, event.endAt, event.reminderMinutes, confirmOverlap)
        val r = runCatching { if (event.id == 0L) api.createEvent(request) else api.updateEvent(event.id, request) }.getOrNull()
        when {
            r?.code() == 401 -> sessionExpired.value = true
            r?.code() == 409 -> {
                val parsed = runCatching { json.decodeFromString<ErrorResponse>(r.errorBody()?.string().orEmpty()) }.getOrNull()
                if (parsed?.error?.code == "EVENT_OVERLAP") { pending = event; _overlap.value = parsed.error.overlaps }
                else _error.value = parsed?.error?.message ?: "Sự kiện bị từ chối."
            }
            r?.isSuccessful == true -> visibleWeekStart?.let { loadWeek(it) }
            else -> _error.value = "Không thể lưu sự kiện (${r?.code() ?: "kết nối"})."
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
