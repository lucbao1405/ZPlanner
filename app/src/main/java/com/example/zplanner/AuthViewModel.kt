package com.example.zplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zplanner.data.ApiError
import com.example.zplanner.data.Credentials
import com.example.zplanner.data.GoogleAuthRequest
import com.example.zplanner.data.GoogleLinkRequest
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
    val googleLinkRequired = MutableStateFlow(false)
    private val json = Json { ignoreUnknownKeys = true }


    fun submitLogin(email: String, password: String) {
        submitCredentials(email, password, register = false)
    }

    fun submitRegister(email: String, password: String) {
        submitCredentials(email, password, register = true)
    }

    private fun submitCredentials(email: String, password: String, register: Boolean) {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            runCatching {
                if (register) api.register(Credentials(email.trim(), password)) else api.login(Credentials(email.trim(), password))
            }.onSuccess { response ->
                if (response.isSuccessful && response.body() != null) {
                    saveSession(response.body()!!, email.trim(), clearGoogleLink = register)
                } else {
                    error.value = if (register) registrationError(response) else "Email hoặc mật khẩu không hợp lệ."
                }
            }.onFailure { error.value = "Không thể kết nối máy chủ. Vui lòng thử lại." }
            loading.value = false
        }
    }

    fun googleSignIn(idToken: String, nonce: String) {
        authenticateGoogle(idToken, nonce, link = false)
    }

    fun linkGoogle(idToken: String, nonce: String) {
        authenticateGoogle(idToken, nonce, link = true)
    }

    private fun authenticateGoogle(idToken: String, nonce: String, link: Boolean) {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            if (link) {
                val response = runCatching { api.linkGoogle(GoogleLinkRequest(idToken, nonce)) }.getOrNull()
                if (response?.isSuccessful == true) {
                    googleLinkRequired.value = false
                    error.value = null
                    _signedIn.value = true
                } else {
                    error.value = response?.let { googleError(it) } ?: "Không thể kết nối máy chủ xác thực Google."
                }
            } else {
                runCatching { api.google(GoogleAuthRequest(idToken, nonce)) }
                    .onSuccess { response ->
                        if (response.isSuccessful && response.body() != null) {
                            saveSession(response.body()!!)
                        } else if (response.code() == 409 && apiErrorCode(response) == "ACCOUNT_LINK_REQUIRED") {
                            googleLinkRequired.value = true
                            error.value = "Tài khoản Google trùng email tài khoản mật khẩu. Hãy đăng nhập bằng mật khẩu để liên kết Google."
                        } else {
                            error.value = googleError(response)
                        }
                    }
                    .onFailure { error.value = "Không thể kết nối máy chủ xác thực Google." }
            }
            loading.value = false
        }
    }

    private fun saveSession(response: com.example.zplanner.data.AuthResponse, fallbackEmail: String? = null, clearGoogleLink: Boolean = true) {
        tokenStore.save(response.accessToken, response.user.email.ifBlank { fallbackEmail.orEmpty() })
        _signedIn.value = true
        if (clearGoogleLink) googleLinkRequired.value = false
    }

    private fun googleError(response: Response<*>): String {
        val code = apiErrorCode(response) ?: "HTTP_${response.code()}"
        return "Google Sign-In thất bại ($code)."
    }

    private fun apiErrorCode(response: Response<*>): String? {
        val body = response.errorBody()?.string().orEmpty()
        return runCatching { json.decodeFromString<ErrorResponse>(body).error.code }.getOrNull()
    }

    private fun registrationError(response: Response<*>): String {
        val body = response.errorBody()?.string().orEmpty()
        val parsed = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
        val apiError: ApiError? = parsed?.error
        return when {
            apiError?.code == "EMAIL_ALREADY_EXISTS" || apiError?.code == "EMAIL_EXISTS" || response.code() == 409 ->
                "Email này đã được đăng ký. Vui lòng đăng nhập hoặc dùng email khác."
            response.code() >= 500 -> "Máy chủ đang gặp sự cố. Vui lòng thử lại sau."
            apiError != null -> "Đăng ký thất bại: ${apiError.message}"
            else -> "Đăng ký thất bại (HTTP ${response.code()})."
        }
    }

    fun cancelPendingGoogleLink() {
        googleLinkRequired.value = false
        error.value = null
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { api.logout() }
            tokenStore.clear()
            googleLinkRequired.value = false
            _signedIn.value = false
        }
    }
}
