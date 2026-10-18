package com.teoilie.ascult.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

private val AscultMotion = MotionScheme.expressive()

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
    val target = remember(dark, context) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
    MaterialExpressiveTheme(
        colorScheme = animateColorScheme(target),
        motionScheme = AscultMotion,
        typography = AscultTypography,
        content = content,
    )
}

/**
 * Fades every colour of the scheme to [target] together. Some Material components animate their own
 * colours and others don't; switching the scheme in one frame let them drift apart for a moment (old
 * theme's cards on the new theme's background), which showed as a flash. Moving the whole scheme
 * keeps them in step.
 */
@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        // Start from what is on screen, so a switch in the middle of a fade carries on smoothly.
        from = from.lerp(to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, AscultMotion.fastEffectsSpec())
    }
    return if (progress.value == 1f) to else from.lerp(to, progress.value)
}

/** Each colour [fraction] of the way from this scheme to [to]. */
internal fun ColorScheme.lerp(to: ColorScheme, fraction: Float): ColorScheme {
    fun mix(from: Color, to: Color) = lerp(from, to, fraction)
    return copy(
        primary = mix(primary, to.primary),
        onPrimary = mix(onPrimary, to.onPrimary),
        primaryContainer = mix(primaryContainer, to.primaryContainer),
        onPrimaryContainer = mix(onPrimaryContainer, to.onPrimaryContainer),
        inversePrimary = mix(inversePrimary, to.inversePrimary),
        secondary = mix(secondary, to.secondary),
        onSecondary = mix(onSecondary, to.onSecondary),
        secondaryContainer = mix(secondaryContainer, to.secondaryContainer),
        onSecondaryContainer = mix(onSecondaryContainer, to.onSecondaryContainer),
        tertiary = mix(tertiary, to.tertiary),
        onTertiary = mix(onTertiary, to.onTertiary),
        tertiaryContainer = mix(tertiaryContainer, to.tertiaryContainer),
        onTertiaryContainer = mix(onTertiaryContainer, to.onTertiaryContainer),
        background = mix(background, to.background),
        onBackground = mix(onBackground, to.onBackground),
        surface = mix(surface, to.surface),
        onSurface = mix(onSurface, to.onSurface),
        surfaceVariant = mix(surfaceVariant, to.surfaceVariant),
        onSurfaceVariant = mix(onSurfaceVariant, to.onSurfaceVariant),
        surfaceTint = mix(surfaceTint, to.surfaceTint),
        inverseSurface = mix(inverseSurface, to.inverseSurface),
        inverseOnSurface = mix(inverseOnSurface, to.inverseOnSurface),
        error = mix(error, to.error),
        onError = mix(onError, to.onError),
        errorContainer = mix(errorContainer, to.errorContainer),
        onErrorContainer = mix(onErrorContainer, to.onErrorContainer),
        outline = mix(outline, to.outline),
        outlineVariant = mix(outlineVariant, to.outlineVariant),
        scrim = mix(scrim, to.scrim),
        surfaceBright = mix(surfaceBright, to.surfaceBright),
        surfaceDim = mix(surfaceDim, to.surfaceDim),
        surfaceContainer = mix(surfaceContainer, to.surfaceContainer),
        surfaceContainerHigh = mix(surfaceContainerHigh, to.surfaceContainerHigh),
        surfaceContainerHighest = mix(surfaceContainerHighest, to.surfaceContainerHighest),
        surfaceContainerLow = mix(surfaceContainerLow, to.surfaceContainerLow),
        surfaceContainerLowest = mix(surfaceContainerLowest, to.surfaceContainerLowest),
        primaryFixed = mix(primaryFixed, to.primaryFixed),
        primaryFixedDim = mix(primaryFixedDim, to.primaryFixedDim),
        onPrimaryFixed = mix(onPrimaryFixed, to.onPrimaryFixed),
        onPrimaryFixedVariant = mix(onPrimaryFixedVariant, to.onPrimaryFixedVariant),
        secondaryFixed = mix(secondaryFixed, to.secondaryFixed),
        secondaryFixedDim = mix(secondaryFixedDim, to.secondaryFixedDim),
        onSecondaryFixed = mix(onSecondaryFixed, to.onSecondaryFixed),
        onSecondaryFixedVariant = mix(onSecondaryFixedVariant, to.onSecondaryFixedVariant),
        tertiaryFixed = mix(tertiaryFixed, to.tertiaryFixed),
        tertiaryFixedDim = mix(tertiaryFixedDim, to.tertiaryFixedDim),
        onTertiaryFixed = mix(onTertiaryFixed, to.onTertiaryFixed),
        onTertiaryFixedVariant = mix(onTertiaryFixedVariant, to.onTertiaryFixedVariant),
    )
}
