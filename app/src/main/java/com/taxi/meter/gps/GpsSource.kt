package com.taxi.meter.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

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

    private var running = false

    /** Предыдущая точка: из неё берём скорость, если приёмник её не дал. */
    private var lastLocation: Location? = null
    private var lastAtMs = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handle(it) }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        lastLocation = null

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
    }

    private fun handle(location: Location) {
        // Грубые точки только портят расчёт: в городе между высотками
        // погрешность легко уходит за сотню метров.
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) {
            onFixLost?.invoke()
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
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

        lastLocation = location
        lastAtMs = nowMs

        // Дрожание координат на месте выглядит как 1-2 км/ч — это не движение
        val kmh = (speedMs * 3.6).let { if (it < MIN_SPEED_KMH) 0.0 else it }
        onSpeedSample?.invoke(kmh, nowMs)
    }

    private companion object {
        /** Целевой период выборок, мс */
        const val INTERVAL_MS = 1000L

        /** Чаще этого обновления не нужны */
        const val MIN_INTERVAL_MS = 500L

        /** Хуже этой погрешности точку не берём, м */
        const val MAX_ACCURACY_M = 30f

        /** Ниже этой скорости считаем, что машина стоит, км/ч */
        const val MIN_SPEED_KMH = 2.0
    }
}
