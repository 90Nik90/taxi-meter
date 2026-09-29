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
import kotlinx.coroutines.delay

private enum class Screen {
    /** Калькулятор — главный экран: расстояние, ожидание, услуги, оплата */
    CALC,

    STATISTICS,

    /** Список поездок за выбранный в статистике период */
    TRIPS,

    /** Тарифы: выбрать, поправить шестерёнкой, удалить корзиной */
    PROFILES,

    PROFILE_EDIT, SETTINGS, SERVICE_PRICES
}

@Composable
fun TaxiRoot(
    vm: MainViewModel,
    onRequestLocationPermission: () -> Unit,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trips by vm.trips.collectAsStateWithLifecycle()
    val calc by vm.calc.collectAsStateWithLifecycle()
    val meter by vm.meter.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val tripSaved by vm.tripSaved.collectAsStateWithLifecycle()

    // Активный тариф читается из хранилища; список и настройки собраны
    // выше, поэтому его смена приводит к перерисовке.
    val activeProfile = vm.activeProfile

    // Простой стек экранов: «назад» всегда возвращает туда, откуда пришли.
    // Внизу всегда калькулятор — отдельного меню нет.
    var stack by remember { mutableStateOf(listOf(Screen.CALC)) }
    val screen = stack.last()
    val go = { next: Screen -> stack = stack + next }
    val back = { if (stack.size > 1) stack = stack.dropLast(1) }

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
            Screen.CALC -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                CalculatorScreen(
                    profile = activeProfile,
                    servicePrices = settings.servicePrices,
                    input = calc,
                    meter = meter,
                    gps = gps,
                    gpsEnabled = settings.gpsEnabled,
                    tripSaved = tripSaved,
                    onDistanceChange = vm::setDistance,
                    onIdleChange = vm::setIdleMinutes,
                    onToggleService = vm::toggleService,
                    onPaymentChange = vm::setPayment,
                    onReset = vm::resetCalc,
                    onSave = { vm.saveTrip() },
                    onStartMeter = {
                        // Разрешение спрашиваем в момент, когда оно нужно:
                        // до первой поездки система о нём не спросит.
                        if (vm.hasLocationPermission()) vm.startMeter()
                        else onRequestLocationPermission()
                    },
                    onPauseMeter = vm::pauseMeter,
                    onResumeMeter = vm::resumeMeter,
                    onStopMeter = { vm.stopMeter() },
                    onOpenProfiles = { go(Screen.PROFILES) },
                    onOpenSettings = { go(Screen.SETTINGS) },
                    onOpenStatistics = { go(Screen.STATISTICS) },
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

            Screen.PROFILES -> ProfilesScreen(
                profiles = profiles,
                activeProfileId = activeProfile?.id,
                onSelect = {
                    vm.selectProfile(it)
                    // Выбор тарифа сразу возвращает к расчёту
                    stack = listOf(Screen.CALC)
                },
                onEdit = {
                    editing = it
                    go(Screen.PROFILE_EDIT)
                },
                onDelete = vm::deleteProfile,
                onBack = back,
            )

            Screen.SETTINGS -> SettingsScreen(
                gpsEnabled = settings.gpsEnabled,
                onGpsEnabledChange = { enabled ->
                    vm.setGpsEnabled(enabled)
                    if (enabled && !vm.hasLocationPermission()) onRequestLocationPermission()
                },
                coarseEnabled = settings.coarseEnabled,
                onCoarseEnabledChange = vm::setCoarseEnabled,
                onOpenProfiles = { go(Screen.PROFILES) },
                onOpenServices = { go(Screen.SERVICE_PRICES) },
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
        }
    }
}
