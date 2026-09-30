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

    /** Последняя поездка ушла в статистику — на экране отметка об этом. */
    private val _tripSaved = MutableStateFlow(false)
    val tripSaved: StateFlow<Boolean> = _tripSaved.asStateFlow()

    /**
     * Показать выбор тарифа: каждый новый заход начинается с него.
     *
     * При холодном старте модель создаётся заново, поэтому true здесь
     * и означает «приложение только что открыли».
     */
    private val _pickTariff = MutableStateFlow(true)
    val pickTariff: StateFlow<Boolean> = _pickTariff.asStateFlow()

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
        // Способы считать друг друга исключают: включили один — гасим второй
        taxi.storage.updateSettings {
            it.copy(gpsEnabled = enabled, networkOnly = it.networkOnly && !enabled)
        }
        taxi.gps.setNetworkOnly(taxi.storage.settings.value.networkOnly)
        // Счётчик остаётся, пока включён хоть один способ считать
        if (!meterEnabled) stopMeter(write = false)
    }

    /** Счётчик есть, если разрешены спутники или сеть. */
    private val meterEnabled: Boolean
        get() = taxi.storage.settings.value.let { it.gpsEnabled || it.networkOnly }

    /** Галочка «рахувати приблизно, коли немає супутників». */
    fun setCoarseEnabled(enabled: Boolean) {
        taxi.storage.updateSettings { it.copy(coarseEnabled = enabled) }
        taxi.gps.setCoarseEnabled(enabled)
    }

    /** Галочка «тільки Wi-Fi та вежі»: спутники не слушаем вовсе. */
    fun setNetworkOnly(enabled: Boolean) {
        taxi.storage.updateSettings {
            it.copy(networkOnly = enabled, gpsEnabled = it.gpsEnabled && !enabled)
        }
        taxi.gps.setNetworkOnly(enabled)
        if (!meterEnabled) stopMeter(write = false)
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

    fun setPayment(method: PaymentMethod?) {
        _calc.value = _calc.value.copy(payment = method)
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
        taxi.storage.addTrip(record)
        _toast.value = ToastMessage("Збережено: ${String.format(Locale.US, "%.2f", record.total)} грн")
        // Поездка закрыта: всё, что набрал водитель, уже в записи. На экране
        // это только мешает — половина перекочевала бы в следующую поездку.
        _calc.value = CalcInput()
        _tripSaved.value = true
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

    private fun forgetSavedTrip() {
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
        // Без тарифа считать нечего: сумма всё равно не посчитается
        if (taxi.storage.activeProfile == null) {
            _toast.value = ToastMessage("Створіть тарифний профіль", isError = true)
            return false
        }
        if (!taxi.gps.hasPermission()) {
            _toast.value = ToastMessage("Дозвольте доступ до місцезнаходження", isError = true)
            return false
        }
        forgetSavedTrip()
        // Отметку оплаты не трогаем: водитель мог поставить её заранее,
        // а из прошлой поездки она не придёт — та обнулила калькулятор
        meterStartedAtWallMs = System.currentTimeMillis()
        taxi.meter.start()
        taxi.gps.setCoarseEnabled(taxi.storage.settings.value.coarseEnabled)
        taxi.gps.setNetworkOnly(taxi.storage.settings.value.networkOnly)
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

    /** Экран выбора тарифа показан — флаг больше не нужен. */
    fun tariffPickShown() {
        _pickTariff.value = false
    }

    /**
     * Вернулись из фона. Короткая отлучка — это та же смена, а вот
     * после долгой паузы водитель обычно начинает новую поездку,
     * и тариф стоит выбрать заново.
     *
     * Полчаса — не требование системы, а общепринятая мера: столько
     * же держит сессию Firebase Analytics.
     */
    fun onReturnedFromBackground(awayMs: Long) {
        if (awayMs < SESSION_TIMEOUT_MS) return
        if (taxi.meter.snapshot.value.isActive) return
        _pickTariff.value = true
    }

    fun consumeToast() {
        _toast.value = null
    }

    private companion object {
        /** Столько отсутствия — и заход считается новым, мс */
        const val SESSION_TIMEOUT_MS = 30L * 60 * 1000
    }
}
