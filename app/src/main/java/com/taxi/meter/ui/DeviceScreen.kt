package com.taxi.meter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taxi.meter.obd.BtDeviceInfo
import com.taxi.meter.obd.ConnectionState
import com.taxi.meter.obd.ObdState
import com.taxi.meter.ui.theme.MeterColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    obd: ObdState,
    devices: List<BtDeviceInfo>,
    selectedAddress: String?,
    hasPermission: Boolean,
    bluetoothOn: Boolean,
    onRefresh: () -> Unit,
    onConnect: (BtDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("OBD-адаптер") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Оновити список")
                    }
                },
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
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        when (obd.connection) {
                            ConnectionState.CONNECTED -> MeterColors.go
                            ConnectionState.CONNECTING -> MeterColors.wait
                            ConnectionState.ERROR -> MeterColors.stop
                            ConnectionState.DEMO -> MeterColors.accent
                            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (obd.connection) {
                            ConnectionState.CONNECTED -> "Підключено"
                            ConnectionState.CONNECTING -> "Підключення..."
                            ConnectionState.ERROR -> "Помилка"
                            ConnectionState.DEMO -> "Демо-режим"
                            ConnectionState.DISCONNECTED -> "Не підключено"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (obd.connection != ConnectionState.DISCONNECTED) {
                        OutlinedButton(onClick = onDisconnect) { Text("Відключити") }
                    }
                }
                obd.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (obd.connection == ConnectionState.CONNECTING) MeterColors.wait
                        else MeterColors.stop,
                    )
                }
                if (obd.connection == ConnectionState.ERROR) {
                    Text(
                        text = "Зв'язок відновиться сам, щойно адаптер відповість.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                obd.protocol?.let { KeyValueRow("Протокол", it) }
                if (obd.isLive) {
                    KeyValueRow("Швидкість", "${obd.speedKmh} км/год")
                    KeyValueRow("Частота опитування", "${fmt(obd.pollHz, 1)} Гц")
                    obd.rpm?.let { KeyValueRow("Оберти", "$it об/хв") }
                    obd.voltage?.let { KeyValueRow("Напруга", "${fmt(it, 1)} В") }
                    obd.distanceSinceClearKm?.let {
                        KeyValueRow("Пробіг з обнулення помилок", "$it км")
                    }
                }
            }

            if (!hasPermission) {
                SectionCard {
                    Text("Немає доступу до Bluetooth", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Дозвольте застосунку використовувати Bluetooth, щоб бачити список адаптерів.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRequestPermissions) { Text("Дозволити") }
                }
            } else if (!bluetoothOn) {
                SectionCard {
                    Text("Bluetooth вимкнено", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Увімкніть Bluetooth у налаштуваннях телефона.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                Text("Спарені пристрої", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Адаптер ELM327 спочатку спарюється в налаштуваннях Bluetooth " +
                        "телефона (зазвичай PIN 1234 або 0000). Потім виберіть його тут.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (devices.isEmpty()) {
                    Text(
                        "Список порожній.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnect(device) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                device.address,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (device.address == selectedAddress) {
                            Text(
                                "вибраний",
                                style = MaterialTheme.typography.labelSmall,
                                color = MeterColors.accent,
                            )
                        }
                    }
                }
            }

            SectionCard {
                Text("Hyundai Sonata 2017", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Штатний одометр через стандартні OBD-II PID недоступний — його " +
                        "значення зберігається в приладовій панелі й через діагностичний " +
                        "роз’єм у відкритому вигляді не віддається. Тому пробіг поїздки " +
                        "рахується інтегруванням швидкості з ЕБУ (PID 010D) із частотою " +
                        "10-20 Гц: розбіжність з одометром зазвичай 1-3 %.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Запалювання має бути увімкнене, інакше ЕБУ не відповідає.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeterColors.wait,
                )
            }

            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}
