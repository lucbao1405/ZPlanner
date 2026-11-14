package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.Credentials
import com.example.zplanner.data.TokenStore
import com.example.zplanner.data.ZPlannerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val api: ZPlannerApi,
    private val tokenStore: TokenStore
) : ViewModel() {
    private val _signedIn = MutableStateFlow(tokenStore.read() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

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
                } else error.value = "Email hoặc mật khẩu không hợp lệ."
            }.onFailure { error.value = "Không thể kết nối máy chủ cục bộ." }
            loading.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch { runCatching { api.logout() }; tokenStore.clear(); _signedIn.value = false }
    }
}
