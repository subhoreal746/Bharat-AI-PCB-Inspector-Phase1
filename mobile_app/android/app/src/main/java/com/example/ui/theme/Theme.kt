package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Immutable
data class PcbSemanticColors(
    val isDark: Boolean,
    val canvas: Color,
    val surfaceCard: Color,
    val surfaceSubtle: Color,
    val surfaceLow: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // Shared primary action & navigation tokens (preserved for AdaptiveNavigationShell)
    val anchorSurface: Color,
    val onAnchorSurface: Color,
    val anchorSubtleText: Color,
    val anchorDivider: Color,
    // Navigation bar / rail tokens
    val navContainer: Color,
    val navBorder: Color,
    val navSelectedPill: Color,
    val onNavSelectedPill: Color,
    val navUnselected: Color,
    // Secondary tonal action tokens
    val tonalButtonSurface: Color,
    val onTonalButtonSurface: Color,
    // Semantic status colors
    val passFg: Color,
    val passBg: Color,
    val reviewFg: Color,
    val reviewBg: Color,
    val rejectFg: Color,
    val rejectBg: Color,
    val infoFg: Color,
    val infoBg: Color,
    val incompleteFg: Color,
    val incompleteBg: Color
)

private val LightPcbColors = PcbSemanticColors(
    isDark = false,
    canvas = LightCanvas,
    surfaceCard = WhiteSurface,
    surfaceSubtle = SecondarySurface,
    surfaceLow = LightSurfaceLow,
    divider = LightDivider,
    textPrimary = DeepestNavy,
    textSecondary = Slate,
    textMuted = MutedTextLight,
    anchorSurface = DeepNavy,
    onAnchorSurface = WhiteSurface,
    anchorSubtleText = PaleGrey,
    anchorDivider = Color(0xFF2B3F4E),
    navContainer = WhiteSurface,
    navBorder = LightDivider,
    navSelectedPill = DeepNavy,
    onNavSelectedPill = WhiteSurface,
    navUnselected = Slate,
    tonalButtonSurface = SecondarySurface,
    onTonalButtonSurface = DeepNavy,
    passFg = StatusPassLight,
    passBg = StatusPassLightBg,
    reviewFg = StatusReviewLight,
    reviewBg = StatusReviewLightBg,
    rejectFg = StatusRejectLight,
    rejectBg = StatusRejectLightBg,
    infoFg = StatusInfoLight,
    infoBg = StatusInfoLightBg,
    incompleteFg = StatusIncompleteLight,
    incompleteBg = StatusIncompleteLightBg
)

private val DarkPcbColors = PcbSemanticColors(
    isDark = true,
    canvas = DeepestNavy,
    surfaceCard = DeepNavy,
    surfaceSubtle = BlueGrey,
    surfaceLow = DarkSurfaceLow,
    divider = DarkDivider,
    textPrimary = PrimaryTextDark,
    textSecondary = MutedGrey,
    textMuted = MutedTextDark,
    anchorSurface = DarkAnchorSurface,
    onAnchorSurface = DeepestNavy,
    anchorSubtleText = MutedGrey,
    anchorDivider = DarkDivider,
    navContainer = DeepNavy,
    navBorder = BlueGrey,
    navSelectedPill = DarkAnchorSurface,
    onNavSelectedPill = DeepestNavy,
    navUnselected = MutedGrey,
    tonalButtonSurface = DarkSurfaceHigh,
    onTonalButtonSurface = PrimaryTextDark,
    passFg = StatusPassDark,
    passBg = StatusPassDarkBg,
    reviewFg = StatusReviewDark,
    reviewBg = StatusReviewDarkBg,
    rejectFg = StatusRejectDark,
    rejectBg = StatusRejectDarkBg,
    infoFg = StatusInfoDark,
    infoBg = StatusInfoDarkBg,
    incompleteFg = StatusIncompleteDark,
    incompleteBg = StatusIncompleteDarkBg
)

val LocalPcbColors = staticCompositionLocalOf { LightPcbColors }

private val LightMaterialColorScheme = lightColorScheme(
    primary = DeepNavy,
    onPrimary = WhiteSurface,
    primaryContainer = SecondarySurface,
    onPrimaryContainer = DeepestNavy,
    secondary = BlueGrey,
    onSecondary = WhiteSurface,
    secondaryContainer = SecondarySurface,
    onSecondaryContainer = DeepNavy,
    tertiary = Slate,
    onTertiary = WhiteSurface,
    background = LightCanvas,
    onBackground = DeepestNavy,
    surface = LightCanvas,
    onSurface = DeepestNavy,
    surfaceVariant = SecondarySurface,
    onSurfaceVariant = Slate,
    surfaceContainerLowest = WhiteSurface,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = WhiteSurface,
    surfaceContainerHigh = SecondarySurface,
    surfaceContainerHighest = LightSurfaceHigh,
    outline = Slate,
    outlineVariant = LightDivider,
    error = StatusRejectLight,
    onError = WhiteSurface,
    errorContainer = StatusRejectLightBg,
    onErrorContainer = StatusRejectLight
)

private val DarkMaterialColorScheme = darkColorScheme(
    primary = PrimaryTextDark,
    onPrimary = DeepestNavy,
    primaryContainer = DarkSurfaceHigh,
    onPrimaryContainer = PrimaryTextDark,
    secondary = MutedGrey,
    onSecondary = DeepestNavy,
    secondaryContainer = DarkSurfaceHigh,
    onSecondaryContainer = PrimaryTextDark,
    tertiary = MutedTextDark,
    onTertiary = DeepestNavy,
    background = DeepestNavy,
    onBackground = PrimaryTextDark,
    surface = DeepestNavy,
    onSurface = PrimaryTextDark,
    surfaceVariant = BlueGrey,
    onSurfaceVariant = MutedGrey,
    surfaceContainerLowest = DeepestNavy,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DeepNavy,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = BlueGrey,
    outline = Slate,
    outlineVariant = DarkDivider,
    error = StatusRejectDark,
    onError = DeepestNavy,
    errorContainer = StatusRejectDarkBg,
    onErrorContainer = StatusRejectDark
)

@Composable
fun BharatPcbTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkMaterialColorScheme else LightMaterialColorScheme
    val semanticColors = if (isDark) DarkPcbColors else LightPcbColors

    CompositionLocalProvider(LocalPcbColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BharatTypography,
            shapes = BharatShapes,
            content = content
        )
    }
}
