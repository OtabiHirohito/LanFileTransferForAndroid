package com.lanfiletransfer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Windows 版 (Theme.xaml) と同じ配色。
 * Windows 版はライトテーマのみなので、Android 版もライトに統一している。
 */
object LftColors {
    val backgroundTop = Color(0xFFF4F6FD)
    val backgroundBottom = Color(0xFFE8EDF9)

    val headerStart = Color(0xFF4F46E5)
    val headerEnd = Color(0xFF7C3AED)

    val card = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFE9EDF7)

    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)
    val textMuted = Color(0xFF94A3B8)

    val divider = Color(0xFFEEF1F8)

    val accent = Color(0xFF6366F1)
    val accentDeep = Color(0xFF4F46E5)
    val accentPressed = Color(0xFF4338CA)
    val accentSoft = Color(0xFFEEF0FE)
    val gradientEnd = Color(0xFF8B5CF6)

    val inputBorder = Color(0xFFE2E8F0)
    val inputBorderHover = Color(0xFFC7D2FE)

    val track = Color(0xFFE9ECF5)
    val progressStart = Color(0xFF6366F1)
    val progressMid = Color(0xFFA855F7)
    val progressEnd = Color(0xFF22D3EE)

    val consoleBackground = Color(0xFF0F172A)
    val consoleForeground = Color(0xFFE2E8F0)
    val consoleBorder = Color(0xFF1E293B)

    val danger = Color(0xFFEF4444)
    val dangerStart = Color(0xFFF43F5E)
    val dangerEnd = Color(0xFFEF4444)
    val success = Color(0xFF10B981)
}

private val LftColorScheme = lightColorScheme(
    primary = LftColors.accent,
    onPrimary = Color.White,
    primaryContainer = LftColors.accentSoft,
    onPrimaryContainer = Color(0xFF312E81),
    secondary = LftColors.gradientEnd,
    onSecondary = Color.White,
    secondaryContainer = LftColors.accentSoft,
    onSecondaryContainer = Color(0xFF4C1D95),
    background = LftColors.backgroundTop,
    onBackground = LftColors.textPrimary,
    surface = LftColors.card,
    onSurface = LftColors.textPrimary,
    surfaceVariant = LftColors.accentSoft,
    onSurfaceVariant = LftColors.textSecondary,
    outline = Color(0xFFCBD5E1),
    outlineVariant = LftColors.divider,
    error = LftColors.danger
)

private val LftTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp)
)

@Composable
fun LanFileTransferTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LftColorScheme,
        typography = LftTypography,
        content = content
    )
}
