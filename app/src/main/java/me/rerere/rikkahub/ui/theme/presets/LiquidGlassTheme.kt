// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * 液态玻璃主题：冷色高光 + 低饱和容器色。
 *
 * 配合 [me.rerere.rikkahub.ui.components.ui.LiquidGlassSurface] 使用时，
 * 半透明玻璃卡片在纯黑背景（AMOLED）与浅色背景上都能保持足够对比度。
 */
val LiquidGlassThemePreset by lazy {
    PresetTheme(
        id = "liquid_glass",
        name = {
            Text("Liquid Glass")
        },
        standardLight = liquidGlassLightScheme,
        standardDark = liquidGlassDarkScheme,
    )
}

private val primaryLight = Color(0xFF0E7490)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFC4EEFA)
private val onPrimaryContainerLight = Color(0xFF06323F)
private val secondaryLight = Color(0xFF6D4FC4)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE9DEFF)
private val onSecondaryContainerLight = Color(0xFF241447)
private val tertiaryLight = Color(0xFFB0367F)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFFD8EE)
private val onTertiaryContainerLight = Color(0xFF3A0A28)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF410002)
private val backgroundLight = Color(0xFFF6F8FC)
private val onBackgroundLight = Color(0xFF171A21)
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = Color(0xFF171A21)
private val surfaceVariantLight = Color(0xFFDCE0EA)
private val onSurfaceVariantLight = Color(0xFF43474E)
private val outlineLight = Color(0xFF73777F)
private val outlineVariantLight = Color(0xFFC3C6CF)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2C2F36)
private val inverseOnSurfaceLight = Color(0xFFF0F1F6)
private val inversePrimaryLight = Color(0xFF7DD4F0)
private val surfaceDimLight = Color(0xFFD7DAE2)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF7F9FD)
private val surfaceContainerLight = Color(0xFFF1F4FA)
private val surfaceContainerHighLight = Color(0xFFEAEEF6)
private val surfaceContainerHighestLight = Color(0xFFE3E8F1)

private val primaryDark = Color(0xFF8FE3FF)
private val onPrimaryDark = Color(0xFF04202B)
private val primaryContainerDark = Color(0xFF1B4E63)
private val onPrimaryContainerDark = Color(0xFFC9F2FF)
private val secondaryDark = Color(0xFFBEA8FF)
private val onSecondaryDark = Color(0xFF241447)
private val secondaryContainerDark = Color(0xFF3B2E6B)
private val onSecondaryContainerDark = Color(0xFFE7DEFF)
private val tertiaryDark = Color(0xFFFF9AD5)
private val onTertiaryDark = Color(0xFF3A0A28)
private val tertiaryContainerDark = Color(0xFF5C2450)
private val onTertiaryContainerDark = Color(0xFFFFD8EE)
private val errorDark = Color(0xFFFF6B6B)
private val onErrorDark = Color(0xFF3A0000)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF05060A)
private val onBackgroundDark = Color(0xFFE6E9F2)
private val surfaceDark = Color(0xFF0A0C12)
private val onSurfaceDark = Color(0xFFE6E9F2)
private val surfaceVariantDark = Color(0xFF3A3F4C)
private val onSurfaceVariantDark = Color(0xFFBFC6D6)
private val outlineDark = Color(0xFF8B93A6)
private val outlineVariantDark = Color(0xFF3A3F4C)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE3E6EF)
private val inverseOnSurfaceDark = Color(0xFF1A1C22)
private val inversePrimaryDark = Color(0xFF2C6B85)
private val surfaceDimDark = Color(0xFF05060A)
private val surfaceBrightDark = Color(0xFF33384A)
private val surfaceContainerLowestDark = Color(0xFF05060A)
private val surfaceContainerLowDark = Color(0xFF0C0F18)
private val surfaceContainerDark = Color(0xFF11141E)
private val surfaceContainerHighDark = Color(0xFF181C29)
private val surfaceContainerHighestDark = Color(0xFF202434)

private val liquidGlassLightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val liquidGlassDarkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
