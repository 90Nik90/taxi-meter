package com.taxi.meter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taxi.meter.data.AppSettings
import com.taxi.meter.data.CalcInput
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.Profile
import com.taxi.meter.data.PaymentMethod
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.data.TripRecord
import com.taxi.meter.data.calculateFare
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

/** Сообщение внизу экрана; предупреждения показываются красным. */
data class ToastMessage(val text: String, val isError: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val taxi = app as TaxiApp

    val profiles: StateFlow<List<Profile>> = taxi.storage.profiles
    val settings: StateFlow<AppSettings> = taxi.storage.settings
    val trips: StateFlow<List<TripRecord>> = taxi.storage.trips
    val meter: StateFlow<MeterSnapshot> = taxi.meter.snapshot
    val gps: StateFlow<GpsStatus> = taxi.gps.status

    private val _calc = MutableStateFlow(CalcInput())
    val calc: StateFlow<CalcInput> = _calc.asStateFlow()

    private val _toast = MutableStateFlow<ToastMessage?>(null)
    val toast: StateFlow<ToastMessage?> = _toast.asStateFlow()

    /** Поездка уже записана счётчиком и правки дописываются в неё. */
    private val _tripSaved = MutableStateFlow(false)
    val tripSaved: StateFlow<Boolean> = _tripSaved.asStateFlow()

    /** Ключ той самой записи; null — дописывать нечего. */
    private var lastSavedKey: Long? = null

    /** Попап со способом оплаты после «Стоп». */
    private val _paymentDialog = MutableStateFlow(false)
    val paymentDialog: StateFlow<Boolean> = _paymentDialog.asStateFlow()

    /** Реальное начало поездки по часам; 0 — считали руками. */
    private var meterStartedAtWallMs = 0L

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

    /** Галочка «рахувати приблизно, коли немає супутників». */
    fun setCoarseEnabled(enabled: Boolean) {
        taxi.storage.updateSettings { it.copy(coarseEnabled = enabled) }
        taxi.gps.setCoarseEnabled(enabled)
    }

    // --- Калькулятор -----------------------------------------------------

    fun setDistance(text: String) {
        _calc.value = _calc.value.copy(distanceText = text)
        syncLastTrip()
    }

    fun setIdleMinutes(text: String) {
        _calc.value = _calc.value.copy(idleText = text)
        syncLastTrip()
    }

    fun toggleService(service: ExtraService) {
        val current = _calc.value.services
        _calc.value = _calc.value.copy(
            services = if (service in current) current - service else current + service,
        )
        syncLastTrip()
    }

    fun setPayment(method: PaymentMethod?) {
        _calc.value = _calc.value.copy(payment = method)
        syncLastTrip()
    }

    fun resetCalc() {
        stopMeter(write = false)
        taxi.meter.reset()
        _calc.value = CalcInput()
        _paymentDialog.value = false
        meterStartedAtWallMs = 0L
        forgetSavedTrip()
    }

    /**
     * Сохранить посчитанную поездку в историю.
     *
     *  true, если запись прошла; false — если чего-то не хватает,
     * и тогда причина уже показана сообщением.
     */
    fun saveTrip(): Boolean {
        val profile = taxi.storage.activeProfile
        if (profile == null) {
            _toast.value = ToastMessage("Створіть тарифний профіль", isError = true)
            return false
        }
        val input = _calc.value
        if (input.distanceKm <= 0.0) {
            _toast.value = ToastMessage("Вкажіть відстань поїздки", isError = true)
            return false
        }
        if (input.payment == null) {
            _toast.value = ToastMessage("Виберіть спосіб оплати", isError = true)
            return false
        }

        val record = buildRecord(System.currentTimeMillis(), profile, input, metered = false)
        taxi.storage.addTrip(record)
        _toast.value = ToastMessage("Збережено: ${String.format(Locale.US, "%.2f", record.total)} грн")
        resetCalc()
        return true
    }

    /**
     * Автосохранение по «Стоп»: поездку, посчитанную счётчиком,
     * водителю не нужно сохранять руками.
     */
    /**
     * Завершить поездку из попапа: записать в историю и закрыть окно.
     *
     * Без способа оплаты кнопка неактивна, так что сюда мы попадаем
     * только с отметкой.
     */
    fun finishTrip() {
        _paymentDialog.value = false
        recordTrip(metered = true)
    }

    private fun recordTrip(metered: Boolean) {
        val profile = taxi.storage.activeProfile ?: return
        val input = _calc.value
        if (input.distanceKm <= 0.0) return

        val key = System.currentTimeMillis()
        val record = buildRecord(key, profile, input, metered)
        lastSavedKey = key
        taxi.storage.addTrip(record)
        _tripSaved.value = true
        _toast.value = ToastMessage("Збережено: ${String.format(Locale.US, "%.2f", record.total)} грн")
    }

    /**
     * Запись из того, что сейчас в калькуляторе.
     *
     * У поездки со счётчиком есть настоящие начало и конец; у расчёта
     * руками — только момент записи. Признак передаётся явно: состояние
     * счётчика доживает до следующего расчёта и соврало бы.
     */
    private fun buildRecord(
        key: Long,
        profile: Profile,
        input: CalcInput,
        metered: Boolean,
    ): TripRecord {
        val fare = profile.calculateFare(
            distanceKm = input.distanceKm,
            idleSeconds = input.idleSeconds,
            services = input.services,
            servicePrices = taxi.storage.settings.value.servicePrices,
        )
        return TripRecord(
            startedAtWallMs = if (metered && meterStartedAtWallMs > 0L) meterStartedAtWallMs
            else key,
            finishedAtWallMs = key,
            distanceKm = fare.distanceKm,
            runningMs = 0L,
            idleMs = input.idleSeconds * 1000L,
            total = fare.total,
            profileName = profile.name,
            services = input.services.map { it.title },
            payment = input.payment?.name ?: "",
            servicesTotal = fare.servicesPart,
            coarseKm = taxi.meter.snapshot.value.coarseKm,
            metered = metered,
        )
    }

    /**
     * Пока запись остаётся текущей, правки дописываются в неё.
     *
     * Способ оплаты водитель отмечает уже после остановки, а километры
     * иногда поправляет руками — заводить на это вторую запись незачем.
     */
    private fun syncLastTrip() {
        val key = lastSavedKey ?: return
        val profile = taxi.storage.activeProfile
        val previous = taxi.storage.trips.value.firstOrNull { it.finishedAtWallMs == key }
        val updated = profile != null && previous != null && taxi.storage.replaceTrip(
            key,
            buildRecord(key, profile, _calc.value, previous.metered),
        )
        if (!updated) forgetSavedTrip()
    }

    private fun forgetSavedTrip() {
        lastSavedKey = null
        _tripSaved.value = false
    }

    /** Убрать поездку из истории и из статистики. */
    fun deleteTrip(trip: TripRecord) = taxi.storage.deleteTrip(trip.finishedAtWallMs)

    // --- Счётчик ---------------------------------------------------------

    fun hasLocationPermission() = taxi.gps.hasPermission()

    /**
     * Запустить счётчик. Разрешение спрашивает экран: без него приёмник
     * молчит, и километры не набегают.
     */
    fun startMeter(): Boolean {
        if (!taxi.gps.hasPermission()) {
            _toast.value = ToastMessage("Дозвольте доступ до місцезнаходження", isError = true)
            return false
        }
        forgetSavedTrip()
        meterStartedAtWallMs = System.currentTimeMillis()
        taxi.meter.start()
        taxi.gps.setCoarseEnabled(taxi.storage.settings.value.coarseEnabled)
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
        if (write) {
            writeToCalc(result)
            // Поездка уходит в историю не сразу: сперва водитель
            // отмечает, чем с ним расплатились.
            if (taxi.storage.activeProfile != null && _calc.value.distanceKm > 0.0) {
                _paymentDialog.value = true
            }
        }
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
