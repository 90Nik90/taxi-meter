package com.taxi.meter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val TaxiYellow = Color(0xFFFFD426)
private val TaxiYellowDark = Color(0xFFB99700)
private val Ink = Color(0xFF101418)
private val Surface1 = Color(0xFF1B222B)
private val Surface2 = Color(0xFF243040)
private val Go = Color(0xFF2ECC71)
private val Wait = Color(0xFFFFA726)
private val Stop = Color(0xFFE74C3C)
private val Blue = Color(0xFF3F9BF0)

private val DarkScheme = darkColorScheme(
    primary = TaxiYellow,
    onPrimary = Ink,
    primaryContainer = TaxiYellowDark,
    onPrimaryContainer = Ink,
    secondary = Surface2,
    onSecondary = Color.White,
    background = Ink,
    onBackground = Color(0xFFE6EAF0),
    surface = Surface1,
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB4BFCE),
    error = Stop,
    outline = Color(0xFF3A4757),
)

private val LightScheme = lightColorScheme(
    primary = TaxiYellowDark,
    onPrimary = Color.White,
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    error = Stop,
)

/** Цвета для кнопок и индикаторов состояния. */
object MeterColors {
    val go = Go
    val wait = Wait
    val stop = Stop
    val accent = TaxiYellow

    /** Безналичная оплата: отделяет карту от жёлтого фирменного цвета */
    val card = Blue
}

private val monoDigits = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
)

private val TaxiTypography = Typography(
    displayLarge = monoDigits.copy(fontSize = 64.sp, lineHeight = 68.sp),
    displayMedium = monoDigits.copy(fontSize = 40.sp, lineHeight = 44.sp),
    headlineSmall = monoDigits.copy(fontSize = 24.sp, lineHeight = 28.sp),
    // Подписи разделов набраны жирным, чтобы держать структуру экрана
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * По умолчанию тёмная тема независимо от системной: экран таксометра
 * почти всегда смотрят за рулём, а жёлтые цифры на светлом фоне
 * читаются плохо.
 */
@Composable
fun TaxiTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TaxiTypography,
        content = content,
    )
}
