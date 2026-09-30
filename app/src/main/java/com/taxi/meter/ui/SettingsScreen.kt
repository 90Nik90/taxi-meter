package com.taxi.meter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taxi.meter.BuildConfig

/** Настройки: счётчик по GPS, тарифы и доплаты за услуги. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    gpsEnabled: Boolean,
    onGpsEnabledChange: (Boolean) -> Unit,
    coarseEnabled: Boolean,
    onCoarseEnabledChange: (Boolean) -> Unit,
    networkOnly: Boolean,
    onNetworkOnlyChange: (Boolean) -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenServices: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                SwitchLine(
                    title = "Лічильник по GPS",
                    subtitle = "Відстань рахується сама під час поїздки — " +
                        "на калькуляторі з’являються «Почати поїздку» і «Стоп»",
                    checked = gpsEnabled,
                    onCheckedChange = onGpsEnabledChange,
                )
                // Запасной путь подчинён счётчику: без него считать нечего,
                // поэтому галочка живёт в его же карточке
                if (gpsEnabled) {
                    HorizontalDivider(
                        // Карточка уже расставляет 8 dp между строками
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SwitchLine(
                        title = "Рахувати по вежах, коли зникає сигнал",
                        subtitle = "Якщо супутники замовкли, відстань рахується по вежах " +
                            "і Wi-Fi. Точність гірша, тому такі кілометри позначаються " +
                            "як приблизні",
                        checked = coarseEnabled,
                        onCheckedChange = onCoarseEnabledChange,
                    )
                }
            }
            SwitchRow(
                title = "Тільки Wi-Fi та вежі",
                subtitle = "Супутники не слухаємо зовсім. Вмикайте на час тривоги: " +
                    "відстань рахується приблизно з першої секунди, без 20 секунд " +
                    "очікування супутників",
                checked = networkOnly,
                onCheckedChange = onNetworkOnlyChange,
            )
            SettingsRow(
                title = "Тарифи",
                subtitle = "Ціна за км, простій, мінімальна ціна",
                onClick = onOpenProfiles,
            )
            SettingsRow(
                title = "Доплати за послуги",
                subtitle = "Діти, тварини, багаж у салоні",
                onClick = onOpenServices,
            )

            // Номер сборки: без него не понять, что стоит в телефоне
            Text(
                text = "Версія ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Нажатие ловит сама строка, иначе на карточке было бы два обработчика
    SectionCard {
        SwitchLine(title, subtitle, checked, onCheckedChange)
    }
}

/** Строка с выключателем; карточку вокруг неё рисует вызывающий. */
@Composable
private fun SwitchLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
