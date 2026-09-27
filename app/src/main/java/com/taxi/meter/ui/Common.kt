package com.taxi.meter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.Locale

fun fmt(value: Double, digits: Int = 2): String =
    String.format(Locale.US, "%.${digits}f", value)

/**
 * Текст, который сам уменьшается, пока не поместится в одну строку.
 *
 * Размер в sp растёт вместе с системной настройкой шрифта, поэтому
 * фиксированный кегль, подобранный на одном телефоне, на другом вылезает
 * за край. Высота строки задаётся явно: без неё унаследованное значение
 * оказывается меньше кегля, и строки налезают друг на друга.
 */
@Composable
fun AutoFitText(
    text: String,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 12.sp,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    var fontSize by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    var settled by remember(text, maxFontSize) { mutableStateOf(false) }

    Text(
        text = text,
        // Пока размер не устоялся, не рисуем: иначе видно, как текст прыгает
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.15f,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > minFontSize) {
                fontSize = fontSize * 0.92f
            } else {
                settled = true
            }
        },
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Псевдоним, чтобы не тащить импорт ColumnScope в каждый вызов. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
