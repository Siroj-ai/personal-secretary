package com.personalsecretary.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ObsidianBg = Color(0xFF0B0F17)
val CardSurface = Color(0xFF131A26)
val CardSurfaceElevated = Color(0xFF192233)
val CardBorder = Color(0xFF233046)
val AccentGold = Color(0xFFD4AF6A)
val AccentGoldSoft = Color(0xFF2E2618)
val EmeraldAccent = Color(0xFF34D399)
val SkyAccent = Color(0xFF38BDF8)
val CoralAccent = Color(0xFFF87171)
val VioletAccent = Color(0xFFA78BFA)
val TextPrimary = Color(0xFFF3F6FC)
val TextSecondary = Color(0xFFB5C0D4)
val TextMuted = Color(0xFF7B8AA3)

private val ExecutiveColorScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = Color(0xFF181207),
    primaryContainer = AccentGoldSoft,
    onPrimaryContainer = AccentGold,
    secondary = EmeraldAccent,
    onSecondary = Color(0xFF062018),
    tertiary = SkyAccent,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = CardSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = CardBorder,
    error = CoralAccent,
    onError = Color(0xFF2A0808)
)

private val ExecutiveTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun PersonalSecretaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ExecutiveColorScheme,
        typography = ExecutiveTypography,
        content = content
    )
}
