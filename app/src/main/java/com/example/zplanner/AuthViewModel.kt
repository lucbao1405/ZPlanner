package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.ApiError
import com.example.zplanner.data.Credentials
import com.example.zplanner.data.ErrorResponse
import com.example.zplanner.data.TokenStore
import com.example.zplanner.data.ZPlannerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import retrofit2.Response

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val api: ZPlannerApi,
    private val tokenStore: TokenStore
) : ViewModel() {
    private val _signedIn = MutableStateFlow(tokenStore.read() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val json = Json { ignoreUnknownKeys = true }

    fun submit(email: String, password: String, register: Boolean) {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            runCatching {
                if (register) api.register(Credentials(email.trim(), password)) else api.login(Credentials(email.trim(), password))
            }.onSuccess { response ->
                if (response.isSuccessful && response.body() != null) {
                    tokenStore.save(response.body()!!.accessToken, email.trim())
                    _signedIn.value = true
                } else {
                    error.value = if (register) registrationError(response) else "Email hoặc mật khẩu không hợp lệ."
                }
            }.onFailure { error.value = "Không thể kết nối máy chủ cục bộ." }
            loading.value = false
        }
    }

    private fun registrationError(response: Response<*>): String {
        val fallback = "Đăng ký thất bại (HTTP ${response.code()})."
        val body = response.errorBody()?.string().orEmpty()
        if (body.isBlank()) return fallback
        val parsed = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
        val apiError: ApiError = parsed?.error ?: return "$fallback ${body.take(200)}"
        return "Đăng ký thất bại (HTTP ${response.code()}, ${apiError.code}): ${apiError.message}"
    }

    fun signOut() {
        viewModelScope.launch { runCatching { api.logout() }; tokenStore.clear(); _signedIn.value = false }
    }
}
