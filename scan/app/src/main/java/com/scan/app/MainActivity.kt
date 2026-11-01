package com.scan.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.scan.app.ui.AboutScreen
import com.scan.app.ui.HistoryDetailScreen
import com.scan.app.ui.HistoryScreen
import com.scan.app.ui.HomeScreen
import com.scan.app.ui.LibraryScreen
import com.scan.app.ui.ModelsScreen
import com.scan.app.ui.OnboardingScreen
import com.scan.app.ui.ScanColors
import com.scan.app.ui.ScanTheme
import com.scan.app.ui.SettingsScreen
import com.scan.app.ui.SourceScreen
import com.scan.app.util.Retention

class MainActivity : ComponentActivity() {

    private var pendingScreen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingScreen = intent?.getStringExtra(EXTRA_SCREEN)
        setContent { AppRoot(pendingScreen) { pendingScreen = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingScreen = intent.getStringExtra(EXTRA_SCREEN)
    }

    companion object {
        const val EXTRA_SCREEN = "extra_screen"
        const val EXTRA_SCREEN_HISTORY = "history"
        const val EXTRA_SCREEN_SETTINGS = "settings"
        const val EXTRA_SCREEN_MODELS = "models"
        const val EXTRA_SCREEN_LIBRARY = "library"
    }
}

@Composable
private fun AppRoot(pending: String?, consumePending: () -> Unit) {
    val context = LocalContext.current
    val scan = ScanApp.get(context)

    var lang by remember { mutableStateOf(scan.settings.lang) }
    var onboarded by remember { mutableStateOf(scan.settings.onboardingDone) }
    var screen by remember { mutableStateOf(if (onboarded) "home" else "onboarding") }
    var detailId by remember { mutableLongStateOf(-1L) }
    var sourceKey by remember { mutableStateOf("") }

    LaunchedEffect(pending) {
        if (!pending.isNullOrBlank()) {
            if (pending == "history" || pending == "settings" || pending == "models" ||
                pending == "library"
            ) {
                if (onboarded) screen = pending
            }
            consumePending()
        }
    }

    LaunchedEffect(Unit) { Retention.run(scan) }

    ScanTheme(lang = lang) {
        androidx.compose.material3.Surface(
            color = ScanColors.Bg,
            modifier = Modifier.fillMaxSize()
        ) {
            when (screen) {
                "onboarding" -> OnboardingScreen(
                    onDone = {
                        scan.settings.onboardingDone = true
                        onboarded = true
                        screen = "home"
                    }
                )

                "models" -> ModelsScreen(onBack = { screen = "home" })

                "settings" -> SettingsScreen(
                    onBack = { screen = "home" },
                    onLanguageChange = { newLang ->
                        scan.settings.lang = newLang
                        lang = newLang
                    },
                    onOpenAbout = { screen = "about" }
                )

                "history" -> HistoryScreen(
                    onBack = { screen = "home" },
                    onOpen = { id ->
                        detailId = id
                        screen = "detail"
                    }
                )

                "detail" -> HistoryDetailScreen(
                    id = detailId,
                    onBack = { screen = "history" },
                    onDeleted = { screen = "history" }
                )

                "about" -> AboutScreen(onBack = { screen = "settings" })

                "library" -> LibraryScreen(
                    onBack = { screen = "home" },
                    onOpenSource = { key ->
                        sourceKey = key
                        screen = "source"
                    }
                )

                "source" -> SourceScreen(
                    sourceKey = sourceKey,
                    onBack = { screen = "library" }
                )

                else -> HomeScreen(
                    onOpenModels = { screen = "models" },
                    onOpenHistory = { screen = "history" },
                    onOpenLibrary = { screen = "library" },
                    onOpenSettings = { screen = "settings" },
                    onOpenAbout = { screen = "about" }
                )
            }
        }
    }

    BackHandler(enabled = screen != "home" && screen != "onboarding") {
        screen = when (screen) {
            "detail" -> "history"
            "about" -> "settings"
            "source" -> "library"
            else -> "home"
        }
    }
}
