package com.taxi.meter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxi.meter.data.Fare
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.ui.theme.MeterColors

/**
 * Итог поездки сразу после «Стоп»: сумма и способ оплаты.
 *
 * Мимо не закрывается и без выбора не завершает — иначе поездка ушла бы
 * в историю без отметки, и разбивка выручки развалилась бы.
 */
@Composable
fun PaymentDialog(
    fare: Fare,
    idleMinutes: Int,
    coarseKm: Double,
    payment: PaymentMethod?,
    onSelect: (PaymentMethod?) -> Unit,
    onFinish: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Поїздку завершено", modifier = Modifier.fillMaxWidth()) },
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
                if (idleMinutes > 0) KeyValueRow("Очікування", formatMinutes(idleMinutes))
                if (coarseKm > 0.0) KeyValueRow("З них приблизно", "${fmt(coarseKm)} км")

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
                            .clickable { onSelect(if (checked) null else method) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onSelect(if (checked) null else method) },
                        )
                        Text(method.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (payment == null) ErrorBox("Виберіть спосіб оплати, щоб завершити")
            }
        },
        confirmButton = {
            // Кнопка одна, поэтому по центру, а не прижата к краю
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onFinish,
                    enabled = payment != null,
                ) { Text("Завершити поїздку") }
            }
        },
    )
}

/** Предупреждение красной плашкой: за рулём его видят краем глаза. */
@Composable
fun ErrorBox(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    )
}
