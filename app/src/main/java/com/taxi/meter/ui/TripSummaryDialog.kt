package com.taxi.meter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.meter.TripSnapshot
import com.taxi.meter.meter.formatClock
import com.taxi.meter.meter.formatDuration
import com.taxi.meter.ui.theme.MeterColors

/**
 * Итог поездки поверх экрана сразу после «Стоп»: сумма к оплате и всё,
 * из чего она сложилась. Закрывается, не сбрасывая поездку — итог
 * остаётся на главном экране до нажатия «Новая поездка».
 */
@Composable
fun TripSummaryDialog(
    trip: TripSnapshot,
    onFinish: (PaymentMethod) -> Unit,
) {
    val fare = trip.fare ?: return
    var payment by remember { mutableStateOf<PaymentMethod?>(null) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        // Попап не закрывается «мимо»: поездку нужно завершить явно,
        // иначе она не попадёт в статистику.
        onDismissRequest = {},
        icon = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MeterColors.go,
            )
        },
        title = { Text("Поїздку завершено") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AutoFitText(
                        text = fmt(fare.total),
                        maxFontSize = 40.sp,
                        modifier = Modifier.weight(1f, fill = false),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MeterColors.accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "грн",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }

                HorizontalDivider()

                KeyValueRow("Відстань", "${fmt(fare.distanceKm)} км")
                KeyValueRow("Простій", formatDuration(trip.idleMs))
                KeyValueRow("Початок", formatClock(trip.startedAtWallMs))
                KeyValueRow("Кінець", formatClock(trip.finishedAtWallMs))
                // Длительность считается вместе с простоем
                KeyValueRow("Тривалість поїздки", formatDuration(trip.totalMs))

                HorizontalDivider()

                Text(
                    text = "СПОСІБ ОПЛАТИ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PaymentMethod.entries.forEach { method ->
                    val checked = payment == method
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                payment = if (checked) null else method
                                showError = false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                payment = if (checked) null else method
                                showError = false
                            },
                        )
                        Text(method.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (showError) {
                    Text(
                        text = "Виберіть спосіб оплати",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // Кнопка одна, поэтому по центру, а не прижата к краю
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = {
                        val chosen = payment
                        if (chosen == null) showError = true else onFinish(chosen)
                    },
                ) { Text("Завершити поїздку") }
            }
        },
    )
}
