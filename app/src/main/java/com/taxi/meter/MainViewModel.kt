package com.taxi.meter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxi.meter.data.AppSettings
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.Profile
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.data.TripRecord
import com.taxi.meter.meter.MeterService
import com.taxi.meter.meter.TripSnapshot
import com.taxi.meter.meter.TripState
import com.taxi.meter.obd.BtDeviceInfo
import com.taxi.meter.obd.ObdState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val taxi = app as TaxiApp

    val trip: StateFlow<TripSnapshot> = taxi.trip.snapshot
    val obd: StateFlow<ObdState> = taxi.obd.state
    val profiles: StateFlow<List<Profile>> = taxi.storage.profiles
    val settings: StateFlow<AppSettings> = taxi.storage.settings
    val trips: StateFlow<List<TripRecord>> = taxi.storage.trips

    private val _devices = MutableStateFlow<List<BtDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BtDeviceInfo>> = _devices.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Попап с итогом поездки — показывается сразу после «Стоп». */
    private val _summaryVisible = MutableStateFlow(false)
    val summaryVisible: StateFlow<Boolean> = _summaryVisible.asStateFlow()

    val activeProfile: Profile?
        get() = taxi.storage.activeProfile

    init {
        // Собственный тикер: интерфейс обновляется, даже если сервис
        // не поднялся (например, система запретила запуск из фона).
        viewModelScope.launch {
            while (true) {
                if (taxi.trip.snapshot.value.isActive) taxi.trip.tick()
                delay(250)
            }
        }
        refreshDevices()
    }

    // --- Профили ---------------------------------------------------------

    fun selectProfile(id: String) {
        taxi.storage.selectProfile(id)
        taxi.trip.setProfile(taxi.storage.activeProfile)
    }

    fun saveProfile(profile: Profile) {
        taxi.storage.saveProfile(profile)
        taxi.trip.setProfile(taxi.storage.activeProfile)
    }

    fun deleteProfile(id: String) {
        taxi.storage.deleteProfile(id)
        taxi.trip.setProfile(taxi.storage.activeProfile)
    }

    fun toggleService(service: ExtraService) = taxi.trip.toggleService(service)

    fun saveServicePrices(prices: ServicePrices) {
        taxi.storage.updateSettings { it.copy(servicePrices = prices) }
        taxi.trip.setServicePrices(prices)
    }

    fun setCalibration(value: Double) {
        taxi.storage.updateSettings { it.copy(calibration = value) }
        taxi.trip.setCalibration(value)
    }

    // --- Адаптер ---------------------------------------------------------

    fun refreshDevices() {
        _devices.value = taxi.obd.pairedDevices()
    }

    fun hasBluetoothPermission() = taxi.obd.hasBluetoothPermission()

    fun isBluetoothOn() = taxi.obd.isBluetoothOn

    fun connect(device: BtDeviceInfo) {
        taxi.storage.updateSettings {
            it.copy(deviceAddress = device.address, deviceName = device.name, demoMode = false)
        }
        taxi.obd.connect(device.address, device.name)
    }

    fun reconnectSaved() {
        val s = taxi.storage.settings.value
        if (s.demoMode) {
            taxi.obd.startDemo()
            return
        }
        val address = s.deviceAddress
        if (address == null) {
            _toast.value = "Спочатку виберіть OBD-адаптер"
            return
        }
        taxi.obd.connect(address, s.deviceName)
    }

    fun setDemoMode(enabled: Boolean) {
        taxi.storage.updateSettings { it.copy(demoMode = enabled) }
        if (enabled) taxi.obd.startDemo() else taxi.obd.disconnect()
    }

    fun disconnect() = taxi.obd.disconnect()

    // --- Таксометр -------------------------------------------------------

    fun start() {
        val profile = taxi.storage.activeProfile
        if (profile == null) {
            _toast.value = "Створіть тарифний профіль"
            return
        }
        if (!taxi.obd.state.value.isLive) {
            _toast.value = "Немає зв’язку з авто — підключіть адаптер"
            return
        }
        taxi.trip.start(profile)
        MeterService.start(getApplication())
    }

    fun pause() = taxi.trip.pause()

    fun resume() = taxi.trip.resume()

    fun stop() {
        taxi.trip.stop()
        MeterService.stop(getApplication())
        _summaryVisible.value = true
    }

    /**
     * Завершити поїздку из итогового попапа: записать её в историю
     * и обнулить счётчик.
     */
    fun finishTrip(payment: PaymentMethod) {
        recordTrip(payment)
        _summaryVisible.value = false
        taxi.trip.reset()
        taxi.trip.setProfile(taxi.storage.activeProfile)
    }

    /** Завершённая поездка уходит в историю для статистики. */
    private fun recordTrip(payment: PaymentMethod) {
        val snap = taxi.trip.snapshot.value
        val fare = snap.fare ?: return
        taxi.storage.addTrip(
            TripRecord(
                startedAtWallMs = snap.startedAtWallMs,
                finishedAtWallMs = snap.finishedAtWallMs,
                distanceKm = snap.distanceKm,
                runningMs = snap.runningMs,
                idleMs = snap.idleMs,
                total = fare.total,
                profileName = snap.profile?.name ?: "",
                services = snap.services.map { it.title },
                payment = payment.name,
                servicesTotal = fare.servicesPart,
            )
        )
    }

    /** Убрать поездку из истории и из статистики. */
    fun deleteTrip(trip: TripRecord) = taxi.storage.deleteTrip(trip.finishedAtWallMs)

    fun consumeToast() {
        _toast.value = null
    }

    /**
     * Менять тариф можно только когда поездки нет. На завершённой тоже
     * нельзя: смена профиля пересчитала бы уже показанную сумму.
     */
    val canSelectProfile: Boolean
        get() = taxi.trip.snapshot.value.state == TripState.IDLE

    val canEditProfiles: Boolean
        get() = canSelectProfile
}
