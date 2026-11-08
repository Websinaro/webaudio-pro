package com.weaudio.pro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object DawType {
    val Label = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.0.sp)
    val Value = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    val Lcd = TextStyle(fontSize = 16.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

val AppTypography = Typography(
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = DawType.Label,
)
