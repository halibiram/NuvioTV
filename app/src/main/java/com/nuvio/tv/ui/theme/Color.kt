package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme

/**
 * Dynamic color scheme that changes based on selected theme.
 * Background colors have subtle theme tinting.
 * Accent colors (secondary, focus) change per theme.
 */
class NuvioColorScheme(palette: ThemeColorPalette) {
    // Primary Background - Theme dependent with subtle tinting
    val Background = palette.background
    val BackgroundElevated = palette.backgroundElevated
    val BackgroundCard = palette.backgroundCard

    // Surface colors - Theme dependent
    val Surface = palette.surface
    val SurfaceVariant = palette.surfaceVariant

    // Primary accent - Neutral Grey (constant)
    val Primary = Color(0xFF9E9E9E)
    val PrimaryVariant = Color(0xFF6F6F6F)
    val OnPrimary = Color(0xFFFFFFFF)

    // Secondary accent - Theme dependent
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = palette.onSecondary
    val OnSecondaryVariant = palette.onSecondaryVariant

    // Text colors - Theme dependent (prevents OLED blooming)
    val TextPrimary = palette.textPrimary
    val TextSecondary = palette.textSecondary
    val TextTertiary = palette.textTertiary
    val TextDisabled = Color(0xFF4D4D4D)

    // Focus states - Theme dependent
    val FocusRing = palette.focusRing
    val FocusBackground = palette.focusBackground

    // Status colors (constant)
    val Rating = Color(0xFFFFD700)
    val Error = Color(0xFFCF6679)
    val Success = Color(0xFF4CAF50)

    // Borders
    val Border = palette.border
    val BorderFocused = palette.focusRing
}

/**
 * Legacy NuvioColors object for backwards compatibility.
 * Components should migrate to using NuvioTheme.colors instead.
 * This object provides the current theme's colors via composition local.
 */
object NuvioColors {
    // Dynamic background colors - Theme dependent with subtle tinting
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundCard

    // Surface colors - Theme dependent
    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SurfaceVariant

    // Primary accent - Neutral Grey (constant)
    val Primary = Color(0xFF9E9E9E)
    val PrimaryVariant = Color(0xFF6F6F6F)
    val OnPrimary = Color(0xFFFFFFFF)

    // Text colors - Theme dependent (prevents OLED blooming)
    val TextPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.TextPrimary

    val TextSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.TextSecondary

    val TextTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.TextTertiary

    val TextDisabled = Color(0xFF4D4D4D)

    // Status colors (constant)
    val Rating = Color(0xFFFFD700)
    val Error = Color(0xFFCF6679)
    val Success = Color(0xFF4CAF50)

    // Borders (non-focus constant but theme dependent)
    val Border: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Border

    // Dynamic accent colors - Theme dependent
    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BorderFocused
}
