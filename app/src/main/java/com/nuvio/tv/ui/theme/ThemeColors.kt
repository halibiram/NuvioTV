package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.domain.model.AppTheme

/**
 * Color palette for each theme.
 * Includes both accent colors and background tints for full theme customization.
 */
data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = Color.White,
    val onSecondaryVariant: Color = Color.White,
    val focusRing: Color,
    val focusBackground: Color,
    // Background colors with subtle theme tinting
    val background: Color = Color(0xFF0D0D0D),
    val backgroundElevated: Color = Color(0xFF1A1A1A),
    val backgroundCard: Color = Color(0xFF242424),
    // Surface colors
    val surface: Color = Color(0xFF1E1E1E),
    val surfaceVariant: Color = Color(0xFF2D2D2D),
    // Borders
    val border: Color = Color(0xFF333333),
    // Typography
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xFFB3B3B3),
    val textTertiary: Color = Color(0xFF808080)
)

object ThemeColors {

    val Crimson = ThemeColorPalette(
        secondary = Color(0xFFE53935),
        secondaryVariant = Color(0xFFC62828),
        focusRing = Color(0xFFFF5252),
        focusBackground = Color(0xFF3D1A1A),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1A)  // Warm red tint
    )

    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF1E88E5),
        secondaryVariant = Color(0xFF1565C0),
        focusRing = Color(0xFF42A5F5),
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),      // Cool blue tint
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24)
    )

    val Violet = ThemeColorPalette(
        secondary = Color(0xFF8E24AA),
        secondaryVariant = Color(0xFF6A1B9A),
        focusRing = Color(0xFFAB47BC),
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),      // Purple tint
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24)
    )

    val Emerald = ThemeColorPalette(
        secondary = Color(0xFF43A047),
        secondaryVariant = Color(0xFF2E7D32),
        focusRing = Color(0xFF66BB6A),
        focusBackground = Color(0xFF1A3D1E),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF1A241A)  // Green tint
    )

    val Amber = ThemeColorPalette(
        secondary = Color(0xFFFB8C00),
        secondaryVariant = Color(0xFFEF6C00),
        focusRing = Color(0xFFFFA726),
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),      // Warm amber tint
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = Color(0xFFD81B60),
        secondaryVariant = Color(0xFFC2185B),
        focusRing = Color(0xFFEC407A),
        focusBackground = Color(0xFF3D1A2D),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1F)  // Pink tint
    )

    val White = ThemeColorPalette(
        secondary = Color(0xFFF5F5F5),
        secondaryVariant = Color(0xFFE0E0E0),
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFF111111),
        focusRing = Color(0xFFFFFFFF),
        focusBackground = Color(0xFF303030),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF222222)
    )

    val OledBlack = ThemeColorPalette(
        // Accents - Pure hyper-contrast white
        secondary = Color(0xFFFFFFFF),
        secondaryVariant = Color(0xFFCCCCCC),
        onSecondary = Color(0xFF000000),
        onSecondaryVariant = Color(0xFF000000),
        
        // Focus - Luminous anti-bloom ring with an atmospheric deep obsidian background
        focusRing = Color(0xFFE8E8E8),
        focusBackground = Color(0xFF0A0A0A),
        
        // Backgrounds - Absolute pitch black for maximum OLED pixel-off state
        background = Color(0xFF000000),
        backgroundElevated = Color(0xFF000000),
        backgroundCard = Color(0xFF000000),
        
        // Surfaces - Absolute pitch black
        surface = Color(0xFF000000),
        surfaceVariant = Color(0xFF000000),
        
        // Borders - Extremely polished glass-like thin border
        border = Color(0xFF222222),
        
        // Typography - Anti-Blooming text colors. 
        // 100% white on 100% black causes astigmatism glare and HDR halation on premium OLEDs.
        // Dropping these slightly creates butter-smooth readability without eye strain.
        textPrimary = Color(0xFFEBEBEB),
        textSecondary = Color(0xFF9E9E9E),
        textTertiary = Color(0xFF707070)
    )

    fun getColorPalette(theme: AppTheme): ThemeColorPalette {
        return when (theme) {
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
            AppTheme.OLED_BLACK -> OledBlack
        }
    }
}
