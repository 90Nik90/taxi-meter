package com.taxi.meter.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
 * Отдаёт наружу только пары «скорость — метка времени»: расстояние
 * считает [com.taxi.meter.meter.DistanceMeter]. Координаты никуда не
 * уходят и нигде не сохраняются.
 */
class GpsSource(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

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

    private var fixCount = 0
    private var rejectedCount = 0
    private var lastAccuracyM = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handle(it) }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Включена ли геолокация в самом телефоне. */
    fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        lastLocation = null
        lastGoodMs = 0
        fixCount = 0
        rejectedCount = 0
        lastAccuracyM = 0
        publish(GpsSignal.NONE)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!running) return
        running = false
        lastLocation = null
        client.removeLocationUpdates(callback)
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

    private fun handle(location: Location) {
        fixCount++
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        lastAccuracyM = accuracy.toInt()

        // Время самой точки, а не момент её получения: система охотно
        // отдаёт первой залежавшуюся точку из кеша, и если считать её
        // свежей, она вместе со следующей даст выдуманную скорость.
        val nowMs = location.elapsedRealtimeNanos / 1_000_000L
        if (SystemClock.elapsedRealtime() - nowMs > MAX_FIX_AGE_MS) {
            rejectedCount++
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
            lastFixAgoSec = ago,
        )
    }

    private companion object {
        /** Целевой период выборок, мс */
        const val INTERVAL_MS = 1000L

        /** Чаще этого обновления не нужны */
        const val MIN_INTERVAL_MS = 500L

        /**
         * Хуже этой погрешности точку не берём, м. Порог мягче, чем
         * хочется: над Киевом сигнал глушат, и в идеальные 10 метров
         * приёмник попадает не всегда.
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
