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

/**
 * Скорость с GPS-приёмника телефона.
 *
 * Слушает сразу два источника: приёмник напрямую через системный
 * LocationManager и сглаженный поток Google Play Services. На разных
 * телефонах молчать может любой из них, а точки нужны хоть откуда —
 * поэтому подписки две, а дубли отсеиваются по метке времени.
 *
 * Наружу отдаются только пары «скорость — метка времени»: расстояние
 * считает [com.taxi.meter.meter.DistanceMeter]. Координаты никуда не
 * уходят и нигде не сохраняются.
 */
class GpsSource(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val manager = context.getSystemService(LocationManager::class.java)

    /** Новая выборка: скорость в км/ч и метка elapsedRealtime. */
    var onSpeedSample: ((Double, Long) -> Unit)? = null

    /** Сигнал пропал — интегрировать через разрыв нельзя. */
    var onFixLost: (() -> Unit)? = null

    private val _status = MutableStateFlow(GpsStatus())

    /** Что происходит с приёмником — для полосы на экране. */
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    private var running = false

    /** Предыдущая точка: из неё берём скорость, если приёмник её не дал. */
    private var lastLocation: Location? = null
    private var lastAtMs = 0L

    /** Момент последней принятой точки, elapsedRealtime */
    private var lastGoodMs = 0L

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

    private val directListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location, "gps")

        // На Android ниже 30 у этих методов нет реализации по умолчанию:
        // без них подписка падает с AbstractMethodError.
        @Deprecated("Требуется на старых версиях Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Включена ли геолокация в самом телефоне. */
    fun isLocationEnabled(): Boolean {
        val m = manager ?: return false
        return m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        lastLocation = null
        lastGoodMs = 0
        lastSeenNanos = 0
        fixCount = 0
        rejectedCount = 0
        lastAccuracyM = 0
        source = ""
        error = null
        publish(GpsSignal.NONE)

        // Приёмник напрямую: работает и там, где сервисы Google молчат
        try {
            manager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MS,
                0f,
                directListener,
                Looper.getMainLooper(),
            )
        } catch (e: Exception) {
            error = "GPS: ${e.javaClass.simpleName}"
        }

        // И сглаженный поток Play Services, если они на телефоне есть
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
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

    fun stop() {
        if (!running) return
        running = false
        lastLocation = null
        runCatching { manager?.removeUpdates(directListener) }
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
        // Один и тот же замер приходит обоими путями — считаем его раз
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
            publish(GpsSignal.WEAK)
            return
        }

        // Грубые точки только портят расчёт: пока приёмник не поймал
        // спутники, система подсовывает положение по вышкам и Wi-Fi
        // с погрешностью в сотни метров — по ней километры не посчитать.
        if (accuracy > MAX_ACCURACY_M) {
            rejectedCount++
            onFixLost?.invoke()
            publish(GpsSignal.WEAK)
            return
        }

        val previous = lastLocation

        val speedMs = when {
            // Приёмник считает скорость по доплеровскому сдвигу — она
            // точнее, чем расстояние между двумя точками, и на стоянке
            // честно равна нулю.
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

    private fun publish(signal: GpsSignal) {
        val ago = if (lastGoodMs == 0L) -1
        else ((SystemClock.elapsedRealtime() - lastGoodMs) / 1000).toInt()

        _status.value = GpsStatus(
            signal = signal,
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
         * Хуже этой погрешности точку не берём, м. Порог мягче, чем
         * хочется: пока спутники не пойманы, система отдаёт положение
         * по вышкам, и в идеальные 10 метров приёмник попадает не сразу.
         */
        const val MAX_ACCURACY_M = 50f

        /** Ниже этой скорости считаем, что машина стоит, км/ч */
        const val MIN_SPEED_KMH = 2.0

        /** Выше этой скорости выборка невозможна — признак подмены, км/ч */
        const val MAX_SPEED_KMH = 200.0

        /** Столько молчания — считаем, что сигнала нет, мс */
        const val SILENCE_MS = 5000L

        /** Точка старше этого — из кеша, ей верить нельзя, мс */
        const val MAX_FIX_AGE_MS = 10_000L
    }
}
