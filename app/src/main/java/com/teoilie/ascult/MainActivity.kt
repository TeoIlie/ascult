package com.teoilie.ascult

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teoilie.ascult.ui.AscultTheme
import com.teoilie.ascult.ui.DictionaryScreen
import com.teoilie.ascult.ui.SettingsScreen
import com.teoilie.ascult.ui.TranslationScreen
import com.teoilie.ascult.ui.isDark

/** The app's screens, each one level deeper than the last; Back returns to the previous one. */
private enum class Screen { MAIN, SETTINGS, DICTIONARY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

            // Nothing is drawn until the setting loads, so a forced theme never flashes the other one.
            val theme by app.settings.theme.collectAsStateWithLifecycle(initialValue = null)
            val dark = theme?.isDark() ?: return@setContent

            // Status and navigation bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            AscultTheme(dark) {
                val back = { screen = Screen.entries[screen.ordinal - 1] }
                BackHandler(enabled = screen != Screen.MAIN, onBack = back)
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                    },
                    label = "screen",
                ) { shown ->
                    when (shown) {
                        Screen.MAIN -> TranslationScreen(onOpenSettings = { screen = Screen.SETTINGS })
                        Screen.SETTINGS -> SettingsScreen(onBack = back, onOpenDictionary = { screen = Screen.DICTIONARY })
                        Screen.DICTIONARY -> DictionaryScreen(onBack = back)
                    }
                }
            }
        }
    }
}
