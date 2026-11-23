package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class NocSemanticColors(
    val voidBackground: Color = NocVoid,
    val terminalBackground: Color = NocTerminalBg,
    val cardSurface: Color = NocCard,
    val elevatedSurface: Color = NocElevated,
    val hairlineBorder: Color = NocBorder,
    val activeBorder: Color = NocBorderActive,
    val healthy: Color = NocEmerald,
    val healthyContainer: Color = NocEmeraldDim,
    val network: Color = NocCyan,
    val networkContainer: Color = NocCyanDim,
    val warning: Color = NocAmber,
    val warningContainer: Color = NocAmberDim,
    val critical: Color = NocCrimson,
    val criticalContainer: Color = NocCrimsonDim,
    val textMuted: Color = NocTextMuted
)

val LocalNocColors = staticCompositionLocalOf { NocSemanticColors() }

private val SentinelDarkColorScheme = darkColorScheme(
    primary = NocEmerald,
    onPrimary = Color(0xFF032116),
    primaryContainer = NocEmeraldDim,
    onPrimaryContainer = NocEmerald,
    secondary = NocCyan,
    onSecondary = Color(0xFF042233),
    secondaryContainer = NocCyanDim,
    onSecondaryContainer = NocCyan,
    tertiary = NocAmber,
    onTertiary = Color(0xFF281802),
    tertiaryContainer = NocAmberDim,
    onTertiaryContainer = NocAmber,
    error = NocCrimson,
    onError = Color(0xFFFFFFFF),
    errorContainer = NocCrimsonDim,
    onErrorContainer = Color(0xFFFFB4C0),
    background = NocVoid,
    onBackground = NocTextPrimary,
    surface = NocSurface,
    onSurface = NocTextPrimary,
    surfaceVariant = NocCard,
    onSurfaceVariant = NocTextSecondary,
    surfaceContainerLowest = NocTerminalBg,
    surfaceContainerLow = NocSurface,
    surfaceContainer = NocCard,
    surfaceContainerHigh = NocElevated,
    surfaceContainerHighest = Color(0xFF253243),
    outline = NocBorder,
    outlineVariant = Color(0xFF1A2431)
)

private val SentinelShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun SentinelVpsTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalNocColors provides NocSemanticColors()) {
        MaterialTheme(
            colorScheme = SentinelDarkColorScheme,
            typography = SentinelTypography,
            shapes = SentinelShapes,
            content = content
        )
    }
}
