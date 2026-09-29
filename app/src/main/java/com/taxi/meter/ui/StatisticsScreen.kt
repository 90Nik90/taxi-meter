package com.taxi.meter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.taxi.meter.data.Bucket
import com.taxi.meter.data.PeriodStats
import com.taxi.meter.data.ProfileSlice
import com.taxi.meter.data.StatsPeriod
import com.taxi.meter.data.TripRecord
import com.taxi.meter.data.DaySlice
import com.taxi.meter.data.buildDays
import com.taxi.meter.data.buildStats
import com.taxi.meter.data.dayTitle
import com.taxi.meter.data.tripsOfDay
import com.taxi.meter.ui.theme.MeterColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    trips: List<TripRecord>,
    period: StatsPeriod,
    nowWallMs: Long,
    onPeriodChange: (StatsPeriod) -> Unit,
    onOpenTrips: () -> Unit,
    onBack: () -> Unit,
) {
    val stats = buildStats(trips, period, nowWallMs)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    StatsPeriod.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = period == value,
                            onClick = { onPeriodChange(value) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                StatsPeriod.entries.size,
                            ),
                            label = { Text(value.title, fontSize = 13.sp) },
                        )
                    }
                }
            }

            item {
                Text(
                    text = period.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            if (stats.tripCount == 0) {
                item { EmptyState() }
            } else {
                item { TotalsCard(stats) }
                if (stats.byProfile.size > 1) {
                    item { ProfilesCard(stats.byProfile) }
                }
                if (stats.buckets.any { it.value > 0.0 }) {
                    item { RevenueChart(stats.bucketTitle, stats.buckets) }
                }
                item { BreakdownCard(stats, period) }

                item {
                    // Список поездок живёт на своём экране: он длинный
                    // и заслоняет сводку, ради которой сюда заходят
                    SectionCard(modifier = Modifier.clickable { onOpenTrips() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ПОЇЗДКИ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.04.em,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Відкрити список",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Отдельный экран со списком поездок за выбранный в статистике период. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    trips: List<TripRecord>,
    period: StatsPeriod,
    nowWallMs: Long,
    onOpenDay: (Long) -> Unit,
    onDeleteTrip: (TripRecord) -> Unit,
    onBack: () -> Unit,
) {
    val stats = buildStats(trips, period, nowWallMs)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Поїздки · ${stats.tripCount}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        // За день список короткий и показывается сразу. За неделю и
        // месяц поездок слишком много — сперва дни, поездки внутри дня.
        if (period == StatsPeriod.DAY) {
            TripList(
                trips = stats.trips,
                hint = period.hint,
                showDate = false,
                padding = padding,
                onDeleteTrip = onDeleteTrip,
            )
        } else {
            DayList(
                days = buildDays(stats.trips, period, nowWallMs),
                hint = period.hint,
                padding = padding,
                onOpenDay = onOpenDay,
            )
        }
    }
}

/** Экран одного дня: только его поездки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayTripsScreen(
    trips: List<TripRecord>,
    dayMs: Long,
    onDeleteTrip: (TripRecord) -> Unit,
    onBack: () -> Unit,
) {
    val ofDay = tripsOfDay(trips, dayMs)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("${dayTitle(dayMs)} · ${ofDay.size}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        TripList(
            trips = ofDay,
            hint = null,
            showDate = false,
            padding = padding,
            onDeleteTrip = onDeleteTrip,
        )
    }
}

/** Дни периода с суммой за каждый; пустые не нажимаются. */
@Composable
private fun DayList(
    days: List<DaySlice>,
    hint: String,
    padding: PaddingValues,
    onOpenDay: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        if (days.isEmpty()) {
            item { EmptyState() }
        } else {
            items(days, key = { it.dayMs }) { day -> DayRow(day, onOpenDay) }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DayRow(day: DaySlice, onOpenDay: (Long) -> Unit) {
    val empty = day.trips == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (empty) Modifier
                else Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = if (day.isToday) MeterColors.go
                        else MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.medium,
                    )
                    .clickable { onOpenDay(day.dayMs) }
            )
            .padding(horizontal = 14.dp, vertical = if (empty) 8.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = day.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (empty) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${day.trips}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${fmt(day.revenue)} грн",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MeterColors.accent,
            )
        }
    }
}

/** Список карточек поездок с подтверждением удаления. */
@Composable
private fun TripList(
    trips: List<TripRecord>,
    hint: String?,
    showDate: Boolean,
    padding: PaddingValues,
    onDeleteTrip: (TripRecord) -> Unit,
) {
    var expandedTripKey by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<TripRecord?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hint != null) {
            item {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (trips.isEmpty()) {
            item { EmptyState() }
        } else {
            items(trips, key = { it.finishedAtWallMs }) { trip ->
                TripCard(
                    trip = trip,
                    expanded = expandedTripKey == trip.finishedAtWallMs,
                    showDate = showDate,
                    onClick = {
                        expandedTripKey =
                            if (expandedTripKey == trip.finishedAtWallMs) null
                            else trip.finishedAtWallMs
                    },
                    onDelete = { pendingDelete = trip },
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    pendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Видалити поїздку?") },
            text = {
                Text(
                    "${formatDate(trip.finishedAtWallMs)} · ${fmt(trip.total)} грн\n" +
                        "Вона зникне з історії, і статистика перерахується."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteTrip(trip)
                    if (expandedTripKey == trip.finishedAtWallMs) expandedTripKey = null
                    pendingDelete = null
                }) { Text("Видалити") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Скасувати") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    SectionCard {
        Text("За період немає поїздок", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Збережіть перший розрахунок — усе порахується само.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TotalsCard(stats: PeriodStats) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            AutoFitText(
                text = fmt(stats.revenue),
                maxFontSize = 44.sp,
                modifier = Modifier.weight(1f, fill = false),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MeterColors.accent,
            )
            Text(
                text = "грн",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }

        // Как разошлась выручка по способам оплаты
        PaymentSplit(stats)

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniTile("ПОЇЗДОК", stats.tripCount.toString(), Modifier.weight(1f))
            MiniTile("КМ", fmt(stats.distanceKm, 1), Modifier.weight(1f))
        }
    }
}

/** Кольцевая диаграмма долей оплаты и подписи с суммами под ней. */
@Composable
private fun PaymentSplit(stats: PeriodStats) {
    // Доли считаются от оплаченного, а не от всей выручки: иначе поездки
    // без отметки об оплате оставляли бы в кольце пустой сектор.
    val base = stats.cash + stats.card
    if (base <= 0.0) return
    val cashShare = (stats.cash / base).toFloat().coerceIn(0f, 1f)
    val cardShare = (1f - cashShare).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(148.dp)
        ) {
            val stroke = 26.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val style = Stroke(width = stroke, cap = StrokeCap.Butt)

            // Зазор между секторами, чтобы они не сливались
            val gap = if (cashShare > 0f && cardShare > 0f) 4f else 0f
            var start = -90f
            listOf(cashShare to MeterColors.go, cardShare to MeterColors.card)
                .forEach { (share, color) ->
                    if (share <= 0f) return@forEach
                    val full = 360f * share
                    drawArc(
                        color = color,
                        startAngle = start + gap / 2f,
                        sweepAngle = (full - gap).coerceAtLeast(1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = style,
                    )
                    start += full
                }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaymentLegendRow(MeterColors.go, "Готівка", cashShare, stats.cash)
            PaymentLegendRow(MeterColors.card, "Картка", cardShare, stats.card)
        }
    }
}

@Composable
private fun PaymentLegendRow(color: Color, name: String, share: Float, value: Double) {
    val leader = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${(share * 100).roundToInt()} %",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Пунктирная выноска до суммы
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .padding(horizontal = 8.dp)
        ) {
            drawLine(
                color = leader,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)),
            )
        }

        Text(
            text = "${fmt(value)} грн",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BreakdownCard(stats: PeriodStats, period: StatsPeriod) {
    SectionCard {
        Text(
            text = "ДОДАТКОВО",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow("Найдорожча", "${fmt(stats.maxTrip)} грн")
        if (stats.idleMs > 0L) {
            KeyValueRow("Очікування", formatMinutes((stats.idleMs / 60_000L).toInt()))
        }
        if (stats.unpaid > 0.0) {
            // Старые записи, сделанные до обязательного выбора оплаты
            KeyValueRow("Без позначки про оплату", "${fmt(stats.unpaid)} грн")
        }
        if (period != StatsPeriod.DAY && stats.daysWithTrips > 1) {
            HorizontalDivider()
            KeyValueRow("Днів із поїздками", stats.daysWithTrips.toString())
            KeyValueRow("У середньому за день", "${fmt(stats.revenuePerDay)} грн")
            KeyValueRow("Поїздок на день", fmt(stats.tripsPerDay, 1))
        }
    }
}

/** Столбчатый график выручки без внешних библиотек. */
@Composable
private fun RevenueChart(title: String, buckets: List<Bucket>) {
    val maxValue = buckets.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: return
    var picked by remember(buckets) { mutableStateOf<Int?>(null) }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "макс. ${fmt(maxValue)} грн",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Подпись над выбранным столбиком: столбиков много, подписать
        // каждый негде, поэтому сумма показывается по нажатию.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val full = maxWidth
            Column {
                Box(modifier = Modifier.fillMaxWidth().height(TIP_HEIGHT)) {
                    picked?.let { index ->
                        val bucket = buckets[index]
                        val center = full * ((index + 0.5f) / buckets.size)
                        val offset = (center - TIP_WIDTH / 2)
                            .coerceIn(0.dp, (full - TIP_WIDTH).coerceAtLeast(0.dp))
                        BucketTip(bucket, Modifier.offset(x = offset).width(TIP_WIDTH))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    buckets.forEachIndexed { index, bucket ->
                        val ratio = (bucket.value / maxValue).toFloat().coerceIn(0f, 1f)
                        val selected = picked == index
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { picked = if (selected) null else index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Ложе столбика: по нему видно, где кончается
                            // один день и начинается другой
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(BAR_AREA)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (selected) MeterColors.accent.copy(alpha = 0.18f)
                                        else Color.White.copy(alpha = 0.04f)
                                    ),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // Минимум 2dp, чтобы пустой день был виден чертой
                                        .height((2 + 80 * ratio).dp)
                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                        .background(
                                            when {
                                                bucket.value <= 0.0 ->
                                                    MaterialTheme.colorScheme.outline
                                                bucket.emphasized -> MeterColors.go
                                                else -> MeterColors.accent
                                            }
                                        )
                                )
                            }
                            Text(
                                text = bucket.label,
                                fontSize = 9.sp,
                                maxLines = 1,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TIP_WIDTH = 96.dp
private val TIP_HEIGHT = 40.dp
private val BAR_AREA = 82.dp

/** Дата и сумма над выбранным столбиком. */
@Composable
private fun BucketTip(bucket: Bucket, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dayTitle(bucket.dayMs),
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutoFitText(
            text = "${fmt(bucket.value)} грн",
            maxFontSize = 13.sp,
            minFontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MeterColors.accent,
        )
    }
}

@Composable
private fun ProfilesCard(slices: List<ProfileSlice>) {
    SectionCard {
        Text(
            text = "ЗА ТАРИФАМИ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        slices.forEach { slice ->
            KeyValueRow(
                label = "${slice.name} · ${slice.trips}",
                value = "${fmt(slice.revenue)} грн",
            )
        }
    }
}

@Composable
private fun TripCard(
    trip: TripRecord,
    expanded: Boolean,
    showDate: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Место под значок занято всегда, даже когда значка нет:
            // иначе строки в списке не выстраиваются в колонку.
            Box(
                modifier = Modifier.size(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!trip.metered) {
                    Icon(
                        Icons.Filled.Calculate,
                        contentDescription = "Розраховано калькулятором",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showDate) formatDate(trip.finishedAtWallMs)
                        else formatClock(trip.finishedAtWallMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "${fmt(trip.total)} грн",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MeterColors.accent,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            append(
                                if (trip.metered) formatDuration(trip.durationMs)
                                else "розрахунок"
                            )
                            if (trip.paymentTitle.isNotBlank()) append(" · ${trip.paymentTitle}")
                            if (trip.profileName.isNotBlank()) append(" · ${trip.profileName}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Видалити поїздку",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                // У поездки со счётчиком есть настоящие начало и конец;
                // у расчёта руками — только момент записи.
                if (trip.metered) {
                    KeyValueRow("Початок", formatClock(trip.startedAtWallMs))
                    KeyValueRow("Кінець", formatClock(trip.finishedAtWallMs))
                    KeyValueRow("Тривалість", formatDuration(trip.durationMs))
                } else {
                    KeyValueRow("Час розрахунку", formatClock(trip.finishedAtWallMs))
                }
                if (trip.profileName.isNotBlank()) KeyValueRow("Тариф", trip.profileName)
                KeyValueRow("Вартість", "${fmt(trip.total)} грн")
                if (trip.paymentTitle.isNotBlank()) KeyValueRow("Оплата", trip.paymentTitle)
            }
        }
    }
}

/** Компактная плитка с подписью. */
@Composable
private fun MiniTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AutoFitText(
            text = label,
            maxFontSize = 9.sp,
            minFontSize = 7.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutoFitText(
            text = value,
            maxFontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
