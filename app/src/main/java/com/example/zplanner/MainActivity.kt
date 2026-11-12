package com.example.zplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.zplanner.ui.theme.ZPlannerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZPlannerTheme {
                val authViewModel: AuthViewModel = hiltViewModel()
                val signedIn by authViewModel.signedIn.collectAsState()
                if (signedIn) TaskScreen(onSignOut = authViewModel::signOut)
                else AuthScreen(authViewModel)
            }
        }
    }
}
