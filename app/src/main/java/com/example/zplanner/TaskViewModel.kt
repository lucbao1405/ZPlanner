package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.ApiTask
import com.example.zplanner.data.TokenStore
import com.example.zplanner.data.ZPlannerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TaskViewModel @Inject constructor(private val api: ZPlannerApi, private val tokenStore: TokenStore) : ViewModel() {
    private val _date = MutableStateFlow(LocalDate.now().toString())
    private val _tasks = MutableStateFlow<List<ApiTask>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _saving = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private var sessionGeneration = 0L
    private var latestLoadId = 0L
    val date: StateFlow<String> = _date
    val tasks: StateFlow<List<ApiTask>> = _tasks
    val loading: StateFlow<Boolean> = _loading
    val saving: StateFlow<Boolean> = _saving
    val error: StateFlow<String?> = _error
    val sessionExpired = MutableStateFlow(false)
    fun clearSessionExpiry() { sessionExpired.value = false }

    fun clearData() {
        sessionGeneration++
        latestLoadId++
        _tasks.value = emptyList()
        _loading.value = false
        _error.value = null
    }

    init { load() }
    fun previousDay() { _date.value = LocalDate.parse(_date.value).minusDays(1).toString(); load() }
    fun nextDay() { _date.value = LocalDate.parse(_date.value).plusDays(1).toString(); load() }
    fun goTo(date: LocalDate) { _date.value = date.toString(); load() }

    fun load() {
        val requestedDate = _date.value
        val requestedToken = tokenStore.read() ?: run {
            _tasks.value = emptyList()
            return
        }
        val requestGeneration = sessionGeneration
        val requestId = ++latestLoadId
        _tasks.value = emptyList()
        viewModelScope.launch {
            _loading.value = true
            val result = runCatching { api.tasks(requestedDate) }
            val stillCurrent = requestGeneration == sessionGeneration &&
                requestId == latestLoadId &&
                _date.value == requestedDate &&
                tokenStore.read() == requestedToken
            if (stillCurrent) {
                result.onSuccess { response ->
                    when {
                        response.code() == 401 -> sessionExpired.value = true
                        response.isSuccessful -> _tasks.value = response.body()?.tasks.orEmpty()
                        else -> _error.value = "Không thể tải công việc (${response.code()})."
                    }
                }.onFailure { _error.value = "Không thể kết nối máy chủ." }
                _loading.value = false
            }
        }
    }

    fun save(task: ApiTask, onComplete: (Boolean) -> Unit = {}) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val result = runCatching { if (task.id == 0L) api.create(task) else api.update(task.id, task) }
            val success = result.getOrNull()?.let { response ->
                when {
                    response.code() == 401 -> { sessionExpired.value = true; false }
                    response.isSuccessful -> true
                    else -> { _error.value = "Không thể lưu công việc (${response.code()})."; false }
                }
            } ?: run { _error.value = "Không thể kết nối máy chủ."; false }
            _saving.value = false
            if (success) load()
            onComplete(success)
        }
    }

    fun delete(task: ApiTask) { viewModelScope.launch { val r = runCatching { api.delete(task.id) }.getOrNull(); if (r?.code() == 401) sessionExpired.value = true else if (r?.isSuccessful != true) _error.value = "Không thể xóa công việc." else { _tasks.value = _tasks.value.filterNot { it.id == task.id }; load() } } }
    fun loadFor(date: LocalDate) { goTo(date) }
}
