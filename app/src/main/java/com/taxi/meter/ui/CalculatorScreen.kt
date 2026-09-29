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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taxi.meter.data.CalcInput
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.data.Profile
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.gps.GpsSignal
import com.taxi.meter.gps.GpsStatus
import com.taxi.meter.meter.MeterSnapshot
import com.taxi.meter.meter.MeterState
import com.taxi.meter.ui.theme.MeterColors

/**
 * Калькулятор поездки. Расстояние либо вводится руками, либо, если в
 * настройках включён счётчик, набегает само по GPS — тогда сверху полей
 * появляются Старт, Пауза и Стоп, а сами поля заполняются счётчиком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(
    profile: Profile?,
    servicePrices: ServicePrices,
    input: CalcInput,
    meter: MeterSnapshot,
    gps: GpsStatus,
    gpsEnabled: Boolean,
    tripSaved: Boolean,
    onDistanceChange: (String) -> Unit,
    onIdleChange: (String) -> Unit,
    onToggleService: (ExtraService) -> Unit,
    onPaymentChange: (PaymentMethod?) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onStartMeter: () -> Unit,
    onPauseMeter: () -> Unit,
    onResumeMeter: () -> Unit,
    onStopMeter: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStatistics: () -> Unit,
) {
    val fare = input.fare(profile, servicePrices)
    val focus = LocalFocusManager.current

    // Со счётчиком поля заполняет он: руками их не трогаем совсем,
    // а до старта в них стоят нули, а не пустота.
    val locked = gpsEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        TopAppBar(
            title = {},
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
            navigationIcon = {
                IconButton(onClick = onOpenStatistics) {
                    Icon(Icons.Filled.BarChart, contentDescription = "Статистика")
                }
            },
            // Тариф меняется нажатием на его название ниже, поэтому
            // справа остаётся только вход в настройки.
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Налаштування")
                }
            },
        )

        if (gpsEnabled && meter.isActive) {
            // Пока считаем грубо, полоса не ругается на сигнал: он и не
            // нужен. Но водитель должен видеть, что сумма приблизительная.
            if (gps.coarse) CoarseBanner()
            else if (gps.signal != GpsSignal.OK) SignalWarning(gps)
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TariffHeader(profile = profile, onClick = onOpenProfiles)

            SumReadout(fmt(fare?.total ?: 0.0))

            AmountField(
                label = "Відстань",
                suffix = "км",
                value = if (locked && input.distanceText.isEmpty()) "0.00"
                else input.distanceText,
                decimal = true,
                enabled = !locked,
                imeAction = ImeAction.Next,
                onValueChange = onDistanceChange,
                onDone = {},
            )
            AmountField(
                label = "Очікування",
                suffix = "хв",
                value = if (locked && input.idleText.isEmpty()) "0" else input.idleText,
                decimal = false,
                enabled = !locked,
                imeAction = ImeAction.Done,
                onValueChange = onIdleChange,
                onDone = { focus.clearFocus() },
            )

            if (gpsEnabled) {
                MeterControls(
                    state = meter.state,
                    speedKmh = meter.speedKmh,
                    onStart = {
                        focus.clearFocus()
                        onStartMeter()
                    },
                    onPause = onPauseMeter,
                    onResume = onResumeMeter,
                    onStop = onStopMeter,
                )
            }

            // Итог остаётся честным и после «Стоп»: видно, какая часть
            // пути посчитана без спутников.
            if (meter.coarseKm > 0.0) {
                Text(
                    text = "З них ${fmt(meter.coarseKm)} км пораховано приблизно, " +
                        "без супутників",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeterColors.accent,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            ServicesCard(
                prices = servicePrices,
                selected = input.services,
                onToggle = onToggleService,
            )

            PaymentCard(selected = input.payment, onSelect = onPaymentChange)

            Spacer(Modifier.height(4.dp))
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Со счётчиком поездка сохраняется сама по «Стоп», поэтому
            // кнопки нет вовсе — только отметка, что запись уже сделана
            // и правки в полях дописываются в неё.
            if (!gpsEnabled) {
                Button(
                    onClick = {
                        focus.clearFocus()
                        onSave()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MeterColors.go,
                        contentColor = Color(0xFF101418),
                    ),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    AutoFitText(
                        text = "ЗБЕРЕГТИ ПОЇЗДКУ",
                        maxFontSize = 16.sp,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (tripSaved) {
                SavedNote()
            }
            Button(
                onClick = {
                    focus.clearFocus()
                    onReset()
                },
                enabled = !input.isEmpty || meter.isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("СКИНУТИ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/**
 * Кнопки счётчика. В покое одна широкая «Почати відлік», в работе —
 * пауза и стоп, на паузе — продовжити и стоп.
 */
@Composable
private fun MeterControls(
    state: MeterState,
    speedKmh: Int,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                MeterState.IDLE -> MeterButton(
                    text = "ПОЧАТИ ВІДЛІК",
                    icon = Icons.Filled.PlayArrow,
                    color = MeterColors.go,
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                )

                MeterState.RUNNING -> {
                    MeterButton(
                        text = "ОЧІКУВАННЯ",
                        icon = Icons.Filled.Pause,
                        color = MeterColors.wait,
                        onClick = onPause,
                        modifier = Modifier.weight(1f),
                    )
                    MeterButton(
                        text = "СТОП",
                        icon = Icons.Filled.Stop,
                        color = MeterColors.stop,
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    )
                }

                MeterState.PAUSED -> {
                    MeterButton(
                        text = "ПОЇХАЛИ",
                        icon = Icons.Filled.PlayArrow,
                        color = MeterColors.go,
                        onClick = onResume,
                        modifier = Modifier.weight(1f),
                    )
                    MeterButton(
                        text = "СТОП",
                        icon = Icons.Filled.Stop,
                        color = MeterColors.stop,
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state == MeterState.RUNNING) {
            Text(
                text = "$speedKmh км/год",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MeterButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color(0xFF101418),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        AutoFitText(
            text = text,
            maxFontSize = 15.sp,
            modifier = Modifier.weight(1f, fill = false),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Спутников нет, считаем по вышкам и Wi-Fi — сумма приблизительная. */
@Composable
private fun CoarseBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MeterColors.accent.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ПРИБЛИЗНО · немає супутників, рахуємо по вежах і Wi-Fi",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MeterColors.accent,
        )
    }
}

/**
 * Полоса о потере сигнала. Без неё обрыв виден только по замершему
 * счётчику: километры молча перестают набегать, и за рулём это легко
 * пропустить.
 *
 * Причины разные и чинятся по-разному, поэтому полоса называет ту,
 * которая есть на самом деле, и показывает цифры приёмника.
 */
@Composable
private fun SignalWarning(gps: GpsStatus) {
    val text = when {
        !gps.locationEnabled ->
            "Геолокація вимкнена в телефоні — увімкніть її у шторці"

        !gps.gpsProviderEnabled ->
            "Супутниковий приймач вимкнено — у налаштуваннях місцезнаходження " +
                "виберіть режим «Висока точність»"

        gps.signal == GpsSignal.WEAK && gps.accuracyM > 0 ->
            "Сигнал занадто слабкий (похибка ${gps.accuracyM} м) — кілометри не рахуються"

        gps.fixCount == 0 ->
            "Чекаємо на супутники — кілометри поки не рахуються"

        else ->
            "Сигнал зник — кілометри не рахуються"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MeterColors.wait.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MeterColors.wait,
        )
        // Цифры приёмника: по ним видно, идёт ли поток координат вообще
        Text(
            text = buildString {
                append("точок: ${gps.fixCount}")
                if (gps.source.isNotEmpty()) append(" (${gps.source})")
                if (gps.rejectedCount > 0) append(" · відкинуто: ${gps.rejectedCount}")
                if (gps.accuracyM > 0) append(" · похибка: ${gps.accuracyM} м")
                if (gps.lastFixAgoSec >= 0) append(" · остання: ${gps.lastFixAgoSec} с тому")
                append(" · приймач: ${if (gps.gpsProviderEnabled) "увімк" else "вимк"}")
                gps.error?.let { append("\n$it") }
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MeterColors.wait.copy(alpha = 0.75f),
        )
    }
}

/** Название тарифа и его основные цифры; нажатие ведёт к выбору тарифа. */
@Composable
private fun TariffHeader(profile: Profile?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = profile?.name ?: "Немає тарифів",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (profile != null) {
            Text(
                text = "${fmt(profile.pricePerKm)} грн/км · мін. ${fmt(profile.minPrice)} грн " +
                    "до ${fmt(profile.minDistanceKm, 1)} км",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SumReadout(value: String) {
    SectionCard {
        Text(
            text = "ДО СПЛАТИ",
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
                text = "грн",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * Поле ввода с крупными цифрами и подписью единицы справа: за рулём
 * набирать мелкое поле неудобно.
 */
@Composable
private fun AmountField(
    label: String,
    suffix: String,
    value: String,
    decimal: Boolean,
    enabled: Boolean,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val cleaned = raw.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') }
            // Точку допускаем только одну: «12.4.5» ничего не значит
            val normalized = if (decimal) {
                val first = cleaned.indexOf('.')
                if (first < 0) cleaned
                else cleaned.substring(0, first + 1) + cleaned.substring(first + 1).replace(".", "")
            } else cleaned
            onValueChange(normalized)
        },
        enabled = enabled,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
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

/** Способ оплаты обязателен: без него поездка не попадёт в статистику. */
@Composable
private fun PaymentCard(
    selected: PaymentMethod?,
    onSelect: (PaymentMethod?) -> Unit,
) {
    SectionCard {
        Text(
            text = "СПОСІБ ОПЛАТИ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PaymentMethod.entries.forEach { method ->
            val checked = selected == method
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
    }
}

/** Счётчик уже записал поездку; правки в полях дописываются в неё. */
@Composable
private fun SavedNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MeterColors.go.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MeterColors.go,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Збережено в статистику — відмітьте оплату, запис оновиться",
            style = MaterialTheme.typography.bodySmall,
            color = MeterColors.go,
        )
    }
}
