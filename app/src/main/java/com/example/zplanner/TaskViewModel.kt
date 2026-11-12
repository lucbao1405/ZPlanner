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
class TaskViewModel @Inject constructor(
    private val api: ZPlannerApi,
    private val tokenStore: TokenStore
) : ViewModel() {
    private val _date = MutableStateFlow(LocalDate.now().toString())
    private val _tasks = MutableStateFlow<List<ApiTask>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    val date: StateFlow<String> = _date
    val tasks: StateFlow<List<ApiTask>> = _tasks
    val loading: StateFlow<Boolean> = _loading
    val error: StateFlow<String?> = _error
    val sessionExpired = MutableStateFlow(false)

    init { load() }

    fun previousDay() { _date.value = LocalDate.parse(_date.value).minusDays(1).toString(); load() }
    fun nextDay() { _date.value = LocalDate.parse(_date.value).plusDays(1).toString(); load() }
    fun clearError() { _error.value = null }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { api.tasks(_date.value) }.onSuccess { response ->
                if (response.code() == 401) sessionExpired.value = true
                else if (response.isSuccessful) _tasks.value = response.body()?.tasks.orEmpty()
                else _error.value = "Không thể tải công việc (${response.code()})."
            }.onFailure { _error.value = "Không thể kết nối Worker." }
            _loading.value = false
        }
    }

    fun save(task: ApiTask) {
        viewModelScope.launch {
            _loading.value = true
            val response = runCatching { if (task.id == 0L) api.create(task) else api.update(task.id, task) }.getOrNull()
            if (response?.code() == 401) sessionExpired.value = true
            else if (response?.isSuccessful != true) _error.value = "Không thể lưu công việc."
            load()
        }
    }

    fun delete(task: ApiTask) {
        viewModelScope.launch {
            val response = runCatching { api.delete(task.id) }.getOrNull()
            if (response?.code() == 401) sessionExpired.value = true
            else if (response?.isSuccessful != true) _error.value = "Không thể xóa công việc."
            else load()
        }
    }
}
