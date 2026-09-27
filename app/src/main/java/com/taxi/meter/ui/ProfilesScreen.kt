package com.taxi.meter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taxi.meter.data.Profile
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.ui.theme.MeterColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    activeProfileId: String?,
    /** Показывать «Змінити», «Видалити» и кнопку добавления */
    editable: Boolean,
    /** Можно ли сменить тариф: во время поездки нельзя */
    selectable: Boolean,
    onSelect: (String) -> Unit,
    onEdit: (Profile) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (editable) "Тарифні профілі" else "Вибір тарифу") },
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
        floatingActionButton = {
            if (editable) {
                FloatingActionButton(onClick = { onEdit(Profile()) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Додати профіль")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!selectable) {
                item {
                    Text(
                        text = "Триває поїздка — змінити тариф можна лише після її завершення.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeterColors.wait,
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileRow(
                    profile = profile,
                    selected = profile.id == activeProfileId,
                    editable = editable,
                    selectable = selectable,
                    onSelect = { onSelect(profile.id) },
                    onEdit = { onEdit(profile) },
                    onDelete = { pendingDelete = profile },
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Видалити профіль?") },
            text = { Text(profile.name) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(profile.id)
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
private fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    editable: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Вся карточка — кнопка выбора тарифа; «Змінити» и «Видалити» внутри
    // перехватывают нажатие на себя.
    SectionCard(
        modifier = Modifier.clickable(enabled = selectable) { onSelect() },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MeterColors.go else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Активний",
                    tint = MeterColors.go,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                TextButton(onClick = onEdit) { Text("Змінити") }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Видалити",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        KeyValueRow("Вартість 1 км", "${fmt(profile.pricePerKm)} грн")
        KeyValueRow("Простій", "${fmt(profile.pricePerIdleMinute)} грн/хв")
        KeyValueRow("Мінімальна ціна", "${fmt(profile.minPrice)} грн")
        KeyValueRow("Мінімальна відстань", "${fmt(profile.minDistanceKm, 1)} км")
    }
}

/** Экран доплат за услуги — общих для всех тарифов. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicePricesScreen(
    initial: ServicePrices,
    onSave: (ServicePrices) -> Unit,
    onBack: () -> Unit,
) {
    var children by remember { mutableStateOf(fmt(initial.children)) }
    var pets by remember { mutableStateOf(fmt(initial.pets)) }
    var luggage by remember { mutableStateOf(fmt(initial.luggage)) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Доплати за послуги") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Діють на всіх тарифах. Увімкнена послуга додає свою ціну " +
                    "до мінімальної вартості поїздки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField("Діти, грн", children) { children = it }
            NumberField("Тварини, грн", pets) { pets = it }
            NumberField("Багаж у салоні, грн", luggage) { luggage = it }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val ch = children.toDoubleOrNull()
                    val pe = pets.toDoubleOrNull()
                    val lu = luggage.toDoubleOrNull()
                    when {
                        ch == null || ch < 0 -> error = "Некоректна доплата за дітей"
                        pe == null || pe < 0 -> error = "Некоректна доплата за тварин"
                        lu == null || lu < 0 -> error = "Некоректна доплата за багаж"
                        else -> onSave(ServicePrices(children = ch, pets = pe, luggage = lu))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text("Зберегти") }
        }
    }
}

/** Экран редактирования одного профиля. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    initial: Profile,
    onSave: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var perKm by remember { mutableStateOf(fmt(initial.pricePerKm)) }
    var perIdle by remember { mutableStateOf(fmt(initial.pricePerIdleMinute)) }
    var minPrice by remember { mutableStateOf(fmt(initial.minPrice)) }
    var minKm by remember { mutableStateOf(fmt(initial.minDistanceKm, 1)) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Налаштування профілю") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Назва профілю") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("Вартість 1 км, грн", perKm) { perKm = it }
            NumberField("Вартість простою, грн/хв", perIdle) { perIdle = it }
            NumberField("Мінімальна ціна за поїздку, грн", minPrice) { minPrice = it }
            NumberField("Мін. відстань для мін. ціни, км", minKm) { minKm = it }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val km = perKm.toDoubleOrNull()
                    val idle = perIdle.toDoubleOrNull()
                    val min = minPrice.toDoubleOrNull()
                    val minD = minKm.toDoubleOrNull()
                    when {
                        name.isBlank() -> error = "Вкажіть назву профілю"
                        km == null || km < 0 -> error = "Некоректна вартість за км"
                        idle == null || idle < 0 -> error = "Некоректна вартість простою"
                        min == null || min < 0 -> error = "Некоректна мінімальна ціна"
                        minD == null || minD < 0 -> error = "Некоректна мінімальна відстань"
                        else -> onSave(
                            initial.copy(
                                name = name.trim(),
                                pricePerKm = km,
                                pricePerIdleMinute = idle,
                                minPrice = min,
                                minDistanceKm = minD,
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text("Зберегти") }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.replace(',', '.').filter { it.isDigit() || it == '.' })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
