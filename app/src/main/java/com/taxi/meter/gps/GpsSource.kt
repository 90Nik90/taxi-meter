package com.taxi.meter.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Положение телефона из всех источников, какие есть.
 *
 * Слушает сразу три: спутниковый приёмник напрямую, положение по
 * вышкам и Wi-Fi, и сглаженный поток Google Play Services. На разных
 * телефонах молчать может любой из них, а во время глушения спутников
 * остаётся только сетевой — поэтому подписок несколько, а дубли
 * отсеиваются по метке времени замера.
 *
 * Наружу отдаются два разных сигнала. Пока спутники живы — скорость,
 * которую [com.taxi.meter.meter.DistanceMeter] интегрирует в расстояние.
 * Когда спутников нет и разрешён грубый режим — сразу куски
 * расстояния между сетевыми точками.
 *
 * Координаты никуда не уходят и нигде не сохраняются.
 */
class GpsSource(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val manager = context.getSystemService(LocationManager::class.java)

    /** Выборка скорости со спутников: км/ч и метка elapsedRealtime. */
    var onSpeedSample: ((Double, Long) -> Unit)? = null

    /** Кусок пути, посчитанный грубо по сетевым точкам, км. */
    var onCoarseDistance: ((Double) -> Unit)? = null

    /** Сигнал пропал — интегрировать через разрыв нельзя. */
    var onFixLost: (() -> Unit)? = null

    private val _status = MutableStateFlow(GpsStatus())

    /** Что происходит с приёмником — для полосы на экране. */
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    private var running = false

    /** Разрешено ли считать грубо, когда спутников нет */
    private var coarseEnabled = false

    /** Слушать только сеть: спутники не запрашиваем и не ждём */
    private var networkOnly = false

    /** Считаем ли грубо прямо сейчас */
    private var coarseActive = false

    /** Предыдущая точка: из неё берём скорость, если приёмник её не дал. */
    private var lastLocation: Location? = null
    private var lastAtMs = 0L

    /** Момент последней принятой точки, elapsedRealtime */
    private var lastGoodMs = 0L

    /** Момент последней спутниковой точки — по нему решаем о переходе */
    private var lastPreciseMs = 0L

    /** Сколько спутниковых точек подряд — чтобы не выходить из грубого по одной */
    private var preciseStreak = 0

    private var startedAtMs = 0L

    /** Опорная точка грубого режима */
    private var coarseAnchor: Location? = null
    private var coarseAnchorAccuracy = 0f
    private var coarseAnchorMs = 0L

    /** Метка последней обработанной точки — по ней отсеиваем дубли */
    private var lastSeenNanos = 0L

    private var fixCount = 0
    private var rejectedCount = 0
    private var lastAccuracyM = 0
    private var source = ""
    private var error: String? = null

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handle(it, "fused") }
        }
    }

    private fun listenerFor(tag: String) = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location, tag)

        // На Android ниже 30 у этих методов нет реализации по умолчанию:
        // без них подписка падает с AbstractMethodError.
        @Deprecated("Требуется на старых версиях Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val satelliteListener = listenerFor("gps")
    private val networkListener = listenerFor("мережа")

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Включена ли геолокация в самом телефоне. */
    fun isLocationEnabled(): Boolean {
        val m = manager ?: return false
        return m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Галочка «тільки Wi-Fi та вежі».
     *
     * Во время тревоги ждать двадцать секунд молчания незачем: водитель
     * и так знает, что спутников не будет. Тогда приёмник не слушаем
     * вовсе и считаем по сети с первой точки.
     */
    fun setNetworkOnly(enabled: Boolean) {
        if (networkOnly == enabled) return
        networkOnly = enabled
        if (!running) return
        // Смена способа посреди поездки: подписки и цепочку заводим заново
        stop()
        start()
    }

    /** Галочка «рахувати приблизно, коли немає супутників». */
    fun setCoarseEnabled(enabled: Boolean) {
        coarseEnabled = enabled
        if (!enabled && coarseActive && !networkOnly) {
            coarseActive = false
            breakChains()
            publish(GpsSignal.NONE)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        startedAtMs = SystemClock.elapsedRealtime()
        lastLocation = null
        coarseAnchor = null
        // В сетевом режиме точного счёта не будет вовсе, ждать нечего
        coarseActive = networkOnly
        preciseStreak = 0
        lastGoodMs = 0
        lastPreciseMs = 0
        lastSeenNanos = 0
        fixCount = 0
        rejectedCount = 0
        lastAccuracyM = 0
        source = ""
        error = null
        publish(GpsSignal.NONE)

        // Приёмник напрямую: работает и там, где сервисы Google молчат
        if (!networkOnly) subscribe(LocationManager.GPS_PROVIDER, satelliteListener)

        // Вышки и Wi-Fi: единственное, что остаётся при глушении спутников
        subscribe(LocationManager.NETWORK_PROVIDER, networkListener)

        // И сглаженный поток Play Services, если они на телефоне есть.
        // В сетевом режиме просим их не будить приёмник.
        val priority = if (networkOnly) Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else Priority.PRIORITY_HIGH_ACCURACY
        val request = LocationRequest.Builder(priority, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        runCatching {
            fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                .addOnFailureListener { e ->
                    // Раньше отказ пропадал молча, и экран показывал
                    // «чекаємо на супутники» вместо причины.
                    error = "Play Services: ${e.message ?: e.javaClass.simpleName}"
                    publish(_status.value.signal)
                }
        }.onFailure { error = "Play Services: ${it.javaClass.simpleName}" }

        publish(GpsSignal.NONE)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(provider: String, listener: LocationListener) {
        try {
            manager?.requestLocationUpdates(
                provider,
                INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (e: Exception) {
            error = "$provider: ${e.javaClass.simpleName}"
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lastLocation = null
        coarseAnchor = null
        coarseActive = false
        runCatching { manager?.removeUpdates(satelliteListener) }
        runCatching { manager?.removeUpdates(networkListener) }
        runCatching { fused.removeLocationUpdates(fusedCallback) }
        onFixLost?.invoke()
        publish(GpsSignal.NONE)
    }

    /**
     * Перечитать состояние по часам: точки могли перестать приходить
     * вовсе, и об этом никто не сообщит — узнать можно только по молчанию.
     */
    fun refresh() {
        if (!running) return
        val quietMs = if (lastGoodMs == 0L) Long.MAX_VALUE
        else SystemClock.elapsedRealtime() - lastGoodMs

        if (quietMs > SILENCE_MS) {
            onFixLost?.invoke()
            publish(GpsSignal.NONE)
        } else {
            publish(_status.value.signal)
        }
    }

    private fun handle(location: Location, from: String) {
        // Один и тот же замер приходит разными путями — считаем его раз
        val stamp = location.elapsedRealtimeNanos
        if (stamp <= lastSeenNanos) return
        lastSeenNanos = stamp

        fixCount++
        source = from
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        lastAccuracyM = accuracy.toInt()

        // Время самой точки, а не момент её получения: система охотно
        // отдаёт первой залежавшуюся точку из кеша, и если считать её
        // свежей, она вместе со следующей даст выдуманную скорость.
        val nowMs = stamp / 1_000_000L
        if (SystemClock.elapsedRealtime() - nowMs > MAX_FIX_AGE_MS) {
            rejectedCount++
            publish(_status.value.signal)
            return
        }

        // Точная выборка — та, где приёмник сам назвал скорость: она
        // считается по доплеровскому сдвигу и на стоянке равна нулю.
        // Сетевое положение скорости не даёт, его считаем грубым.
        val precise = !networkOnly && accuracy <= MAX_ACCURACY_M && location.hasSpeed()
        updateMode(nowMs, precise)

        if (precise && !coarseActive) {
            handlePrecise(location, nowMs)
            return
        }

        if (!coarseActive) {
            // Грубый режим выключен или ещё не пришло его время —
            // такую точку просто не считаем.
            rejectedCount++
            onFixLost?.invoke()
            publish(GpsSignal.WEAK)
            return
        }

        if (accuracy > MAX_COARSE_ACCURACY_M) {
            rejectedCount++
            publish(GpsSignal.WEAK)
            return
        }

        handleCoarse(location, accuracy, nowMs)
    }

    /**
     * Решает, каким способом считать.
     *
     * В грубый режим уходим только после долгого молчания спутников:
     * под мостом и между высотками сигнал пропадает на пару секунд,
     * и дёргаться туда-сюда на каждом таком провале нельзя.
     * Обратно — после нескольких точек подряд, чтобы одна подделка
     * не выдернула нас из грубого режима.
     */
    private fun updateMode(nowMs: Long, precise: Boolean) {
        // Сетевой режим включён водителем — из него сами не выходим
        if (networkOnly) return

        if (precise) {
            lastPreciseMs = nowMs
            preciseStreak++
            if (coarseActive && preciseStreak >= PRECISE_STREAK) {
                coarseActive = false
                breakChains()
            }
            return
        }

        preciseStreak = 0
        if (coarseActive || !coarseEnabled) return

        val since = if (lastPreciseMs == 0L) startedAtMs else lastPreciseMs
        if (nowMs - since > SWITCH_TO_COARSE_MS) {
            coarseActive = true
            breakChains()
        }
    }

    /**
     * Рвёт цепочку при смене способа счёта.
     *
     * Сетевая точка смещена относительно спутниковой на десятки метров,
     * и если сложить последнюю точку одного источника с первой точкой
     * другого, в счёт попадёт сотня метров, которых не проезжали.
     * Лучше недосчитать слепой участок, чем выдумать его.
     */
    private fun breakChains() {
        lastLocation = null
        coarseAnchor = null
        onFixLost?.invoke()
    }

    private fun handlePrecise(location: Location, nowMs: Long) {
        val previous = lastLocation

        val speedMs = when {
            location.hasSpeed() -> location.speed.toDouble()

            // Запасной путь для приёмников, которые скорость не отдают
            previous != null && nowMs > lastAtMs -> {
                val meters = previous.distanceTo(location).toDouble()
                meters / ((nowMs - lastAtMs) / 1000.0)
            }

            else -> {
                lastLocation = location
                lastAtMs = nowMs
                lastGoodMs = nowMs
                publish(GpsSignal.OK)
                return
            }
        }

        val kmh = speedMs * 3.6

        // Подмена сигнала телепортирует точку за десятки километров.
        // Такую выборку считать нельзя: она одна накрутит весь тариф.
        if (kmh > MAX_SPEED_KMH) {
            rejectedCount++
            lastLocation = null
            onFixLost?.invoke()
            publish(GpsSignal.WEAK)
            return
        }

        lastLocation = location
        lastAtMs = nowMs
        lastGoodMs = nowMs

        // Дрожание координат на месте выглядит как 1-2 км/ч — это не движение
        val clean = if (kmh < MIN_SPEED_KMH) 0.0 else kmh
        onSpeedSample?.invoke(clean, nowMs)
        publish(GpsSignal.OK)
    }

    /**
     * Грубый счёт по сетевым точкам.
     *
     * Скорости у них нет, поэтому расстояние берётся прямо между
     * точками. Отрезок засчитывается, только если он заметно больше
     * погрешности обеих точек: иначе стояние в пробке накрутило бы
     * километры из одного дрожания координат.
     */
    private fun handleCoarse(location: Location, accuracy: Float, nowMs: Long) {
        val anchor = coarseAnchor
        if (anchor == null) {
            coarseAnchor = location
            coarseAnchorAccuracy = accuracy
            coarseAnchorMs = nowMs
            lastGoodMs = nowMs
            publish(GpsSignal.OK)
            return
        }

        val meters = anchor.distanceTo(location).toDouble()
        val threshold = max(
            MIN_COARSE_STEP_M,
            ((coarseAnchorAccuracy + accuracy) * COARSE_FACTOR).toDouble(),
        )

        lastGoodMs = nowMs

        if (meters < threshold) {
            // Ещё не уехали дальше собственной погрешности — это не движение
            publish(GpsSignal.OK)
            return
        }

        val dtSec = (nowMs - coarseAnchorMs) / 1000.0
        val kmh = if (dtSec > 0) meters / dtSec * 3.6 else 0.0
        if (kmh > MAX_SPEED_KMH) {
            rejectedCount++
            coarseAnchor = location
            coarseAnchorAccuracy = accuracy
            coarseAnchorMs = nowMs
            publish(GpsSignal.WEAK)
            return
        }

        onCoarseDistance?.invoke(meters / 1000.0)
        coarseAnchor = location
        coarseAnchorAccuracy = accuracy
        coarseAnchorMs = nowMs
        publish(GpsSignal.OK)
    }

    /**
     * Сколько секунд осталось до перехода на вышки.
     *
     * Ждать двадцать секунд молча нельзя: замерший счётчик и полоса
     * «кілометри не рахуються» выглядят как поломка, хотя запасной
     * способ уже на подходе.
     */
    private fun coarseCountdown(): Int {
        if (!running || coarseActive || networkOnly || !coarseEnabled) return -1
        val since = if (lastPreciseMs == 0L) startedAtMs else lastPreciseMs
        val left = SWITCH_TO_COARSE_MS - (SystemClock.elapsedRealtime() - since)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    private fun publish(signal: GpsSignal) {
        val ago = if (lastGoodMs == 0L) -1
        else ((SystemClock.elapsedRealtime() - lastGoodMs) / 1000).toInt()

        _status.value = GpsStatus(
            signal = signal,
            coarseInSec = coarseCountdown(),
            coarse = coarseActive,
            accuracyM = lastAccuracyM,
            fixCount = fixCount,
            rejectedCount = rejectedCount,
            locationEnabled = isLocationEnabled(),
            gpsProviderEnabled = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false,
            lastFixAgoSec = ago,
            source = source,
            error = error,
        )
    }

    private companion object {
        /** Целевой период выборок, мс */
        const val INTERVAL_MS = 1000L

        /** Чаще этого обновления не нужны */
        const val MIN_INTERVAL_MS = 500L

        /**
         * Хуже этой погрешности точка на точную не тянет, м. Порог мягче,
         * чем хочется: пока спутники не пойманы, система отдаёт положение
         * по вышкам, и в идеальные 10 метров приёмник попадает не сразу.
         */
        const val MAX_ACCURACY_M = 50f

        /** Грубее этого не считаем даже приблизительно, м */
        const val MAX_COARSE_ACCURACY_M = 200f

        /** Ниже этой скорости считаем, что машина стоит, км/ч */
        const val MIN_SPEED_KMH = 2.0

        /** Выше этой скорости выборка невозможна — признак подмены, км/ч */
        const val MAX_SPEED_KMH = 200.0

        /** Столько молчания — считаем, что сигнала нет, мс */
        const val SILENCE_MS = 5000L

        /** Точка старше этого — из кеша, ей верить нельзя, мс */
        const val MAX_FIX_AGE_MS = 10_000L

        /** Столько без спутников — переходим на грубый счёт, мс */
        const val SWITCH_TO_COARSE_MS = 20_000L

        /** Столько спутниковых точек подряд — возвращаемся к точному счёту */
        const val PRECISE_STREAK = 2

        /** Короче этого отрезок в грубом режиме не считаем, м */
        const val MIN_COARSE_STEP_M = 100.0

        /** Во столько раз отрезок должен превышать погрешность точек */
        const val COARSE_FACTOR = 1.5f
    }
}
