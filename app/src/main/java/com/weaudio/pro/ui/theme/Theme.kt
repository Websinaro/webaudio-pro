package com.weaudio.pro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val Scheme = darkColorScheme(
    primary = DawColors.Accent,
    onPrimary = DawColors.Panel,
    background = DawColors.Background,
    onBackground = DawColors.TextPrimary,
    surface = DawColors.Raised,
    onSurface = DawColors.TextPrimary,
    surfaceVariant = DawColors.Raised,
    onSurfaceVariant = DawColors.TextSecondary,
    outline = DawColors.Separator,
    error = DawColors.Error,
)

private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp), large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(4.dp),
)

@Composable
fun WeAudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = CompactShapes, content = content)
}
