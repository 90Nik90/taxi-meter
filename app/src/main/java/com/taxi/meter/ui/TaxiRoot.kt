package com.taxi.meter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taxi.meter.MainViewModel
import com.taxi.meter.data.Profile
import com.taxi.meter.data.StatsPeriod
import com.taxi.meter.meter.TripState
import com.taxi.meter.obd.ConnectionState
import kotlinx.coroutines.delay

private enum class Screen {
    MENU, METER, STATISTICS,

    /** Список поездок за выбранный в статистике период */
    TRIPS,

    SETTINGS,

    /** Вибір тарифу с таксометра: только список */
    PROFILE_PICKER,

    /** Тарифы из настроек: список плюс создание, правка и удаление */
    PROFILES,

    PROFILE_EDIT, SERVICE_PRICES, DEVICES
}

@Composable
fun TaxiRoot(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
) {
    val trip by vm.trip.collectAsStateWithLifecycle()
    val obd by vm.obd.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val summaryVisible by vm.summaryVisible.collectAsStateWithLifecycle()

    // Простой стек экранов: «назад» всегда возвращает туда, откуда пришли.
    var stack by remember { mutableStateOf(listOf(Screen.MENU)) }
    val screen = stack.last()
    val go = { next: Screen -> stack = stack + next }
    val back = { if (stack.size > 1) stack = stack.dropLast(1) }

    // Вибір тарифу всегда приводит на таксометр, откуда бы ни зашли.
    val goMeter = {
        val index = stack.indexOf(Screen.METER)
        stack = if (index >= 0) stack.take(index + 1) else listOf(Screen.MENU, Screen.METER)
    }

    var editing by remember { mutableStateOf<Profile?>(null) }

    // Период статистики живёт выше экрана: он нужен и списку поездок
    var statsPeriod by remember { mutableStateOf(StatsPeriod.DAY) }

    // Часы для границ периодов статистики
    var nowWallMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowWallMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = stack.size > 1) {
        if (stack.last() == Screen.PROFILE_EDIT) editing = null
        back()
    }

    // Один раз при запуске поднимаем связь с ранее выбранным адаптером.
    LaunchedEffect(Unit) {
        if (obd.connection == ConnectionState.DISCONNECTED &&
            (settings.demoMode || settings.deviceAddress != null)
        ) {
            vm.reconnectSaved()
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (screen) {
            Screen.MENU -> MainMenuScreen(
                trip = trip,
                onStartTrip = {
                    // Поездка начинается с выбора тарифа; «назад» отсюда —
                    // сразу таксометр с тем тарифом, что был выбран раньше.
                    stack = listOf(Screen.MENU, Screen.METER, Screen.PROFILE_PICKER)
                },
                onOpenMeter = { go(Screen.METER) },
                onOpenStatistics = { go(Screen.STATISTICS) },
                onOpenSettings = { go(Screen.SETTINGS) },
            )

            Screen.METER -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                MeterScreen(
                    trip = trip,
                    obd = obd,
                    activeProfileName = vm.activeProfile?.name,
                    onToggleService = vm::toggleService,
                    onStart = vm::start,
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = vm::stop,
                    onOpenProfiles = { go(Screen.PROFILE_PICKER) },
                    onOpenMenu = back,
                )
            }

            Screen.STATISTICS -> StatisticsScreen(
                trips = trips,
                period = statsPeriod,
                nowWallMs = nowWallMs,
                onPeriodChange = { statsPeriod = it },
                onOpenTrips = { go(Screen.TRIPS) },
                onBack = back,
            )

            Screen.TRIPS -> TripsScreen(
                trips = trips,
                period = statsPeriod,
                nowWallMs = nowWallMs,
                onDeleteTrip = vm::deleteTrip,
                onBack = back,
            )

            Screen.SETTINGS -> SettingsScreen(
                adapterStatus = when (obd.connection) {
                    ConnectionState.CONNECTED -> obd.deviceName ?: "Підключено"
                    ConnectionState.CONNECTING -> "Підключення..."
                    ConnectionState.ERROR -> "Помилка зв’язку"
                    ConnectionState.DEMO -> "Демо-режим"
                    ConnectionState.DISCONNECTED -> "Не підключено"
                },
                onOpenProfiles = { go(Screen.PROFILES) },
                onOpenServices = { go(Screen.SERVICE_PRICES) },
                onOpenDevices = {
                    vm.refreshDevices()
                    go(Screen.DEVICES)
                },
                onBack = back,
            )

            // Список без правки: только переключить тариф
            Screen.PROFILE_PICKER -> ProfilesScreen(
                profiles = profiles,
                activeProfileId = vm.activeProfile?.id,
                editable = false,
                selectable = vm.canSelectProfile,
                onSelect = {
                    vm.selectProfile(it)
                    goMeter()
                },
                onEdit = {},
                onDelete = {},
                onBack = back,
            )

            Screen.PROFILES -> ProfilesScreen(
                profiles = profiles,
                activeProfileId = vm.activeProfile?.id,
                editable = vm.canEditProfiles,
                selectable = vm.canSelectProfile,
                onSelect = {
                    vm.selectProfile(it)
                    goMeter()
                },
                onEdit = {
                    editing = it
                    go(Screen.PROFILE_EDIT)
                },
                onDelete = vm::deleteProfile,
                onBack = back,
            )

            Screen.SERVICE_PRICES -> ServicePricesScreen(
                initial = settings.servicePrices,
                onSave = {
                    vm.saveServicePrices(it)
                    back()
                },
                onBack = back,
            )

            Screen.PROFILE_EDIT -> {
                val profile = editing
                LaunchedEffect(profile) {
                    if (profile == null) back()
                }
                if (profile != null) {
                    ProfileEditScreen(
                        initial = profile,
                        onSave = {
                            vm.saveProfile(it)
                            editing = null
                            back()
                        },
                        onBack = {
                            editing = null
                            back()
                        },
                    )
                }
            }

            Screen.DEVICES -> DeviceScreen(
                obd = obd,
                devices = devices,
                selectedAddress = settings.deviceAddress,
                hasPermission = vm.hasBluetoothPermission(),
                bluetoothOn = vm.isBluetoothOn(),
                onRefresh = vm::refreshDevices,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
                onRequestPermissions = onRequestPermissions,
                onBack = back,
            )
        }

        if (summaryVisible && trip.state == TripState.FINISHED) {
            TripSummaryDialog(
                trip = trip,
                onFinish = { payment ->
                    vm.finishTrip(payment)
                    // После расчёта — снова выбор тарифа под следующего клиента
                    stack = listOf(Screen.MENU, Screen.METER, Screen.PROFILE_PICKER)
                },
            )
        }
    }
}
