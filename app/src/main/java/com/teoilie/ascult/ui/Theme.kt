package com.teoilie.ascult.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.teoilie.ascult.R
import com.teoilie.ascult.settings.ThemeMode

/**
 * Google Sans Flex (OFL, `assets/licenses/`), bundled because GrapheneOS doesn't ship it. The file is
 * a variable font cut down to Latin and Romanian with only the weight axis (300–700) left.
 */
private val GoogleSansFlex = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { weight ->
        Font(R.font.google_sans_flex, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

/** Every style, emphasized ones included, in Google Sans Flex. */
private val AscultTypography = Typography(GoogleSansFlex)

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Material 3 Expressive with wallpaper colours, springy motion and Google Sans Flex. */
@Composable
fun AscultTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        motionScheme = MotionScheme.expressive(),
        typography = AscultTypography,
        content = content,
    )
}
