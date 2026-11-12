package com.example.zplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (register) "Đăng ký" else "Đăng nhập")
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu") }, singleLine = true)
        error?.let { Text(it) }
        Button(enabled = !loading, onClick = { viewModel.submit(email, password, register) }) {
            if (loading) CircularProgressIndicator() else Text(if (register) "Đăng ký" else "Đăng nhập")
        }
        Button(onClick = { register = !register }) {
            Text(if (register) "Đã có tài khoản? Đăng nhập" else "Tạo tài khoản")
        }
    }
}
