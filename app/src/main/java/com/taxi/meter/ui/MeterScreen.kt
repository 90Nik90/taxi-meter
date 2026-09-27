package com.taxi.meter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.meter.TripSnapshot
import com.taxi.meter.meter.TripState
import com.taxi.meter.meter.formatDuration
import com.taxi.meter.obd.ConnectionState
import com.taxi.meter.obd.ObdState
import com.taxi.meter.ui.theme.MeterColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(
    trip: TripSnapshot,
    obd: ObdState,
    activeProfileName: String?,
    onToggleService: (ExtraService) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            // Заголовок пустой: на главном экране и так понятно, где находишься
            title = {},
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            navigationIcon = {
                IconButton(onClick = onOpenMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "Головне меню")
                }
            },
            actions = {
                IconButton(onClick = onOpenProfiles) {
                    Icon(Icons.Filled.LocalOffer, contentDescription = "Тарифи")
                }
            },
        )

        ConnectionWarning(obd, trip)

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Тариф меняется только на экране профилей, здесь он просто назван.
            Text(
                text = activeProfileName ?: "Немає профілів",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            BigReadout("СУМА", fmt(trip.fare?.total ?: 0.0), "грн")
            BigReadout("ПРОЙДЕНО", fmt(trip.distanceKm), "км")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "У ДОРОЗІ",
                    value = formatDuration(trip.runningMs),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "ПРОСТІЙ",
                    value = formatDuration(trip.idleMs),
                    modifier = Modifier.weight(1f),
                    valueColor = if (trip.state == TripState.PAUSED) MeterColors.wait
                    else MaterialTheme.colorScheme.onSurface,
                )
            }

            ServicesCard(
                prices = trip.servicePrices,
                selected = trip.services,
                onToggle = onToggleService,
            )

            Spacer(Modifier.height(4.dp))
        }

        Controls(
            state = trip.state,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
        )
    }
}

/**
 * Полоса о потере связи. Без неё обрыв виден только по замершему счётчику:
 * километры молча перестают набегать, и за рулём это легко пропустить.
 */
@Composable
private fun ConnectionWarning(obd: ObdState, trip: TripSnapshot) {
    if (obd.isLive) return

    val connecting = obd.connection == ConnectionState.CONNECTING
    val color = if (connecting) MeterColors.wait else MeterColors.stop
    val text = when {
        connecting -> "Перепідключення до авто — пробіг не рахується"
        trip.isActive -> "Немає зв’язку з авто — пробіг не рахується"
        else -> "Немає зв’язку з авто. Підключіть адаптер у налаштуваннях"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * Дополнительные услуги. Каждая включённая добавляет свою цену
 * к минимальной стоимости поездки.
 */
@Composable
private fun ServicesCard(
    prices: ServicePrices,
    selected: Set<ExtraService>,
    onToggle: (ExtraService) -> Unit,
) {
    SectionCard {
        Text(
            text = "ДОДАТКОВІ ПОСЛУГИ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExtraService.entries.forEach { service ->
            val checked = service in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(service) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggle(service) })
                Text(
                    text = service.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${fmt(service.priceIn(prices))} грн",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (checked) MeterColors.accent
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BigReadout(label: String, value: String, unit: String) {
    SectionCard {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            AutoFitText(
                text = value,
                maxFontSize = 60.sp,
                modifier = Modifier.weight(1f, fill = false),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MeterColors.accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    state: TripState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            TripState.IDLE -> BigButton(
                text = "СТАРТ",
                icon = Icons.Filled.PlayArrow,
                color = MeterColors.go,
                onClick = onStart,
                modifier = Modifier.weight(1f),
            )

            TripState.RUNNING -> {
                BigButton(
                    text = "ПАУЗА",
                    icon = Icons.Filled.Pause,
                    color = MeterColors.wait,
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                )
                BigButton(
                    text = "СТОП",
                    icon = Icons.Filled.Stop,
                    color = MeterColors.stop,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                )
            }

            TripState.PAUSED -> {
                BigButton(
                    text = "ПРОДОВЖИТИ",
                    icon = Icons.Filled.PlayArrow,
                    color = MeterColors.go,
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                )
                BigButton(
                    text = "СТОП",
                    icon = Icons.Filled.Stop,
                    color = MeterColors.stop,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                )
            }

            // Поїздку завершено: дальше ведёт итоговый попап поверх экрана
            TripState.FINISHED -> Unit
        }
    }
}

@Composable
private fun BigButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color(0xFF101418),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        AutoFitText(
            text = text,
            maxFontSize = 15.sp,
            modifier = Modifier.weight(1f, fill = false),
            fontWeight = FontWeight.Bold,
        )
    }
}
