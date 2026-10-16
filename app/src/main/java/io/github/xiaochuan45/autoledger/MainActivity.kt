package io.github.xiaochuan45.autoledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.xiaochuan45.autoledger.ui.LedgerApp
import io.github.xiaochuan45.autoledger.capture.NotificationRecoveryResult
import io.github.xiaochuan45.autoledger.capture.PermissionHelper

class MainActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)
    private var foregroundRecovery by mutableStateOf<NotificationRecoveryResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF176D55),
                    secondary = Color(0xFF52645B),
                    background = Color(0xFFF7F9F6),
                    surface = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFE2F1E9),
                ),
            ) {
                LedgerApp(resumeCount, foregroundRecovery)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foregroundRecovery = PermissionHelper.maybeRecoverNotificationsOnForeground(this)
        resumeCount++
    }
}
