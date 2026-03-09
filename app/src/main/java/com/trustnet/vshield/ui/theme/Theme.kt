package com.trustnet.vshield.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = VShieldBlue,
    secondary = VShieldGreen,
    tertiary = VShieldGreen,
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6)
)

private val LightColorScheme = lightColorScheme(
    primary = VShieldBlue,          // Màu xanh dương cho nút bấm chính
    secondary = VShieldGreen,        // Màu xanh lá cho các thành phần phụ
    tertiary = VShieldGreen,
    primaryContainer = VShieldBlueContainer, // Nền xanh nhạt khi VPN bật
    secondaryContainer = VShieldGreenContainer,
    background = Color(0xFFFBFCFF),
    surface = Color(0xFFFBFCFF),
    onPrimary = Color.White,
    onSecondary = Color.White
)

@Composable
fun VshieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Tắt dynamic để ưu tiên màu thương hiệu của bạn
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}