package com.taxi.meter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxi.meter.data.AppSettings
import com.taxi.meter.data.CalcInput
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.Profile
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.gps.GpsStatus
import com.taxi.meter.meter.MeterService
import com.taxi.meter.meter.MeterSnapshot
import com.taxi.meter.meter.MeterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val taxi = app as TaxiApp

    val profiles: StateFlow<List<Profile>> = taxi.storage.profiles
    val settings: StateFlow<AppSettings> = taxi.storage.settings
    val meter: StateFlow<MeterSnapshot> = taxi.meter.snapshot
    val gps: StateFlow<GpsStatus> = taxi.gps.status

    private val _calc = MutableStateFlow(CalcInput())
    val calc: StateFlow<CalcInput> = _calc.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val activeProfile: Profile?
        get() = taxi.storage.activeProfile

    init {
        // Собственный тикер: минуты ожидания и живой километраж должны
        // идти, даже если сервис не поднялся.
        viewModelScope.launch {
            while (true) {
                val snapshot = taxi.meter.snapshot.value
                if (snapshot.isActive) {
                    // Молчание приёмника ничем не сообщается — о нём
                    // можно узнать только по часам.
                    taxi.gps.refresh()
                    taxi.meter.tick()
                    pushMeterIntoCalc()
                }
                delay(500)
            }
        }
    }

    // --- Профили ---------------------------------------------------------

    fun selectProfile(id: String) = taxi.storage.selectProfile(id)

    fun saveProfile(profile: Profile) = taxi.storage.saveProfile(profile)

    fun deleteProfile(id: String) = taxi.storage.deleteProfile(id)

    fun saveServicePrices(prices: ServicePrices) {
        taxi.storage.updateSettings { it.copy(servicePrices = prices) }
    }

    /** Галочка «рахувати відстань по GPS» в настройках. */
    fun setGpsEnabled(enabled: Boolean) {
        taxi.storage.updateSettings { it.copy(gpsEnabled = enabled) }
        if (!enabled) stopMeter(write = false)
    }

    // --- Калькулятор -----------------------------------------------------

    fun setDistance(text: String) {
        _calc.value = _calc.value.copy(distanceText = text)
    }

    fun setIdleMinutes(text: String) {
        _calc.value = _calc.value.copy(idleText = text)
    }

    fun toggleService(service: ExtraService) {
        val current = _calc.value.services
        _calc.value = _calc.value.copy(
            services = if (service in current) current - service else current + service,
        )
    }

    fun resetCalc() {
        stopMeter(write = false)
        taxi.meter.reset()
        _calc.value = CalcInput()
    }

    // --- Счётчик ---------------------------------------------------------

    fun hasLocationPermission() = taxi.gps.hasPermission()

    /**
     * Запустить счётчик. Разрешение спрашивает экран: без него приёмник
     * молчит, и километры не набегают.
     */
    fun startMeter(): Boolean {
        if (!taxi.gps.hasPermission()) {
            _toast.value = "Дозвольте доступ до місцезнаходження"
            return false
        }
        taxi.meter.start()
        taxi.gps.start()
        MeterService.start(getApplication())
        pushMeterIntoCalc()
        return true
    }

    fun pauseMeter() {
        taxi.meter.pause()
        pushMeterIntoCalc()
    }

    fun resumeMeter() {
        taxi.meter.resume()
        pushMeterIntoCalc()
    }

    /** Остановить счётчик; итог остаётся в полях калькулятора. */
    fun stopMeter(write: Boolean = true) {
        if (taxi.meter.snapshot.value.state == MeterState.IDLE) return
        val result = taxi.meter.stop()
        taxi.gps.stop()
        MeterService.stop(getApplication())
        if (write) writeToCalc(result)
    }

    /** Живые показания счётчика видны прямо в полях калькулятора. */
    private fun pushMeterIntoCalc() {
        val snapshot = taxi.meter.snapshot.value
        if (!snapshot.isActive) return
        writeToCalc(snapshot)
    }

    private fun writeToCalc(snapshot: MeterSnapshot) {
        _calc.value = _calc.value.copy(
            distanceText = String.format(Locale.US, "%.2f", snapshot.distanceKm),
            idleText = if (snapshot.idleMinutes > 0) snapshot.idleMinutes.toString() else "",
        )
    }

    fun consumeToast() {
        _toast.value = null
    }
}
