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
    private val _error = MutableStateFlow<String?>(null)
    val date: StateFlow<String> = _date
    val tasks: StateFlow<List<ApiTask>> = _tasks
    val loading: StateFlow<Boolean> = _loading
    val error: StateFlow<String?> = _error
    val sessionExpired = MutableStateFlow(false)
    fun clearSessionExpiry() { sessionExpired.value = false }
    init { load() }
    fun previousDay() { _date.value = LocalDate.parse(_date.value).minusDays(1).toString(); load() }
    fun nextDay() { _date.value = LocalDate.parse(_date.value).plusDays(1).toString(); load() }
    fun goTo(date: LocalDate) { _date.value = date.toString(); load() }
    fun load() { viewModelScope.launch { _loading.value = true; _error.value = null; runCatching { api.tasks(_date.value) }.onSuccess { r -> if (r.code() == 401) sessionExpired.value = true else if (r.isSuccessful) _tasks.value = r.body()?.tasks.orEmpty() else _error.value = "Không thể tải công việc (${r.code()})." }.onFailure { _error.value = "Không thể kết nối máy chủ." }; _loading.value = false } }
    fun save(task: ApiTask) { viewModelScope.launch { _loading.value = true; val r = runCatching { if (task.id == 0L) api.create(task) else api.update(task.id, task) }.getOrNull(); if (r?.code() == 401) sessionExpired.value = true else if (r?.isSuccessful != true) _error.value = "Không thể lưu công việc."; load() } }
    fun delete(task: ApiTask) { viewModelScope.launch { val r = runCatching { api.delete(task.id) }.getOrNull(); if (r?.code() == 401) sessionExpired.value = true else if (r?.isSuccessful != true) _error.value = "Không thể xóa công việc." else load() } }
    fun loadFor(date: LocalDate) { goTo(date) }
}
