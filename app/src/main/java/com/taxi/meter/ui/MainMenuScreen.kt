package com.taxi.meter.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.taxi.meter.meter.TripSnapshot
import com.taxi.meter.meter.TripState
import com.taxi.meter.meter.formatDuration
import com.taxi.meter.ui.theme.MeterColors

/**
 * Головне меню. Пока поездка идёт, верхняя кнопка желтеет и показывает
 * её сводку; нажатие возвращает к таксометру.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    trip: TripSnapshot,
    onStartTrip: () -> Unit,
    onOpenMeter: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val active = trip.state != TripState.IDLE

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { if (active) onOpenMeter() else onStartTrip() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = 20.dp, horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) MeterColors.accent else MeterColors.go,
                    contentColor = Color(0xFF101418),
                ),
            ) {
                if (active) ActiveTripLabel(trip) else StartTripLabel()
            }

            MenuButton(
                text = "Статистика",
                icon = Icons.Filled.BarChart,
                onClick = onOpenStatistics,
            )
            MenuButton(
                text = "Налаштування",
                icon = Icons.Filled.Settings,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun StartTripLabel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Две трети ширины кнопки; пропорции силуэта 2:1
        AnimatedCar(
            moving = false,
            modifier = Modifier
                .fillMaxWidth(0.67f)
                .aspectRatio(2f),
        )
        AutoFitText(
            text = "ПОЧАТИ ПОЇЗДКУ",
            maxFontSize = TRIP_LABEL_SIZE,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Сводка поездки на кнопке: состояние сверху, отступ, затем значения
 * столбиком — все строки одного размера.
 */
@Composable
private fun ActiveTripLabel(trip: TripSnapshot) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TRIP_LABEL_GAP),
    ) {
        // Едет, пока идёт поездка; на паузе замирает
        AnimatedCar(
            moving = trip.state == TripState.RUNNING,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .aspectRatio(2f),
        )
        AutoFitText(
            text = when (trip.state) {
                TripState.PAUSED -> "ПАУЗА · ПРОСТІЙ"
                TripState.FINISHED -> "ЗАВЕРШЕНА"
                else -> "ПОЇЗДКА ТРИВАЄ"
            },
            maxFontSize = TRIP_LABEL_SIZE,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.04.em,
        )
        TripValue("${fmt(trip.fare?.total ?: 0.0)} грн")
        TripValue("${fmt(trip.distanceKm)} км")
        TripValue(formatDuration(trip.totalMs))
    }
}

@Composable
private fun TripValue(text: String) {
    AutoFitText(
        text = text,
        maxFontSize = TRIP_LABEL_SIZE,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

private val TRIP_LABEL_SIZE = 36.sp
private val TRIP_LABEL_GAP = 24.dp

@Composable
private fun MenuButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
