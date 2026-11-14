package com.example.zplanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ZPlanner", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (register) "Tạo tài khoản mới" else "Chào mừng bạn trở lại", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Mật khẩu") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(18.dp))
            Button(enabled = !loading && email.isNotBlank() && password.isNotBlank(), onClick = { viewModel.submit(email, password, register) }, modifier = Modifier.fillMaxWidth()) {
                if (loading) CircularProgressIndicator() else Text(if (register) "Đăng ký" else "Đăng nhập")
            }
            TextButton(onClick = { register = !register }) { Text(if (register) "Đã có tài khoản? Đăng nhập" else "Tạo tài khoản mới") }
        }
    }
}
