package com.taxi.meter.meter

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class MeterState {
    /** Счётчик не запущен */
    IDLE,

    /** Едем, набегают километры */
    RUNNING,

    /** Пауза — считаются минуты ожидания */
    PAUSED,
}

data class MeterSnapshot(
    val state: MeterState = MeterState.IDLE,
    /** Пройденное расстояние, км */
    val distanceKm: Double = 0.0,
    /** Время на паузе, мс */
    val idleMs: Long = 0,
    /** Текущая скорость, км/ч — для подписи на экране */
    val speedKmh: Int = 0,
    /** Есть ли свежие координаты */
    val hasFix: Boolean = false,
) {
    val isActive: Boolean get() = state == MeterState.RUNNING || state == MeterState.PAUSED

    /** Ожидание в целых минутах вверх: начатая минута оплачивается целиком. */
    val idleMinutes: Int get() = Math.ceil(idleMs / 60_000.0 - 1e-9).toInt().coerceAtLeast(0)
}

/**
 * Считает пройденное расстояние по выборкам скорости с GPS.
 *
 * Складывать расстояния между координатами нельзя: на стоянке точка
 * «плавает», и за несколько минут у светофора набегают сотни лишних
 * метров. Скорость с приёмника берётся из доплеровского сдвига и на
 * месте честно равна нулю, поэтому расстояние получается интегрированием
 * скорости методом трапеций по реальным меткам времени.
 */
class DistanceMeter {

    private val _snapshot = MutableStateFlow(MeterSnapshot())
    val snapshot: StateFlow<MeterSnapshot> = _snapshot.asStateFlow()

    private val lock = Any()

    private var state = MeterState.IDLE
    private var km = 0.0
    private var idleAccumMs = 0L

    /** Момент последней смены состояния, elapsedRealtime, мс */
    private var segmentStartMs = 0L

    /** Предыдущая выборка скорости для метода трапеций */
    private var lastSpeed: Double? = null
    private var lastSampleMs = 0L

    private var speedKmh = 0
    private var hasFix = false

    fun start() = synchronized(lock) {
        km = 0.0
        idleAccumMs = 0
        lastSpeed = null
        lastSampleMs = 0
        segmentStartMs = SystemClock.elapsedRealtime()
        state = MeterState.RUNNING
        publish()
    }

    fun pause() = synchronized(lock) {
        if (state != MeterState.RUNNING) return@synchronized
        segmentStartMs = SystemClock.elapsedRealtime()
        lastSpeed = null              // разрыв интегрирования
        state = MeterState.PAUSED
        publish()
    }

    fun resume() = synchronized(lock) {
        if (state != MeterState.PAUSED) return@synchronized
        val now = SystemClock.elapsedRealtime()
        idleAccumMs += now - segmentStartMs
        segmentStartMs = now
        lastSpeed = null
        state = MeterState.RUNNING
        publish()
    }

    /** Остановить счётчик и отдать итог. */
    fun stop(): MeterSnapshot = synchronized(lock) {
        if (state == MeterState.PAUSED) {
            idleAccumMs += SystemClock.elapsedRealtime() - segmentStartMs
        }
        lastSpeed = null
        state = MeterState.IDLE
        speedKmh = 0
        publish()
        _snapshot.value
    }

    fun reset() = synchronized(lock) {
        state = MeterState.IDLE
        km = 0.0
        idleAccumMs = 0
        lastSpeed = null
        lastSampleMs = 0
        speedKmh = 0
        publish()
    }

    /**
     * Новая выборка скорости от приёмника.
     *
     * @param kmh скорость, км/ч
     * @param atMs метка времени, SystemClock.elapsedRealtime()
     */
    fun onSpeedSample(kmh: Double, atMs: Long) = synchronized(lock) {
        hasFix = true
        speedKmh = kmh.roundToInt()

        if (state != MeterState.RUNNING) {
            lastSpeed = null
            lastSampleMs = atMs
            publish()
            return@synchronized
        }
        val prev = lastSpeed
        val dt = atMs - lastSampleMs
        lastSpeed = kmh
        lastSampleMs = atMs
        if (prev == null) {
            publish()
            return@synchronized
        }
        // Пропуски дольше MAX_GAP_MS (туннель, потеря спутников) не считаем:
        // иначе одна дырка дала бы километры из воздуха.
        if (dt > 0 && dt <= MAX_GAP_MS) {
            km += (prev + kmh) / 2.0 * (dt / 3_600_000.0)
        }
        publish()
    }

    /** Сигнал пропал: интегрировать через разрыв нельзя. */
    fun onFixLost() = synchronized(lock) {
        hasFix = false
        lastSpeed = null
        speedKmh = 0
        publish()
    }

    /** Пересчитать живые таймеры (вызывается тикером интерфейса). */
    fun tick() = synchronized(lock) { publish() }

    private fun publish() {
        val idle = idleAccumMs + if (state == MeterState.PAUSED) {
            (SystemClock.elapsedRealtime() - segmentStartMs).coerceAtLeast(0)
        } else 0L

        _snapshot.value = MeterSnapshot(
            state = state,
            distanceKm = km,
            idleMs = idle,
            speedKmh = speedKmh,
            hasFix = hasFix,
        )
    }

    private companion object {
        /** Разрыв в выборках, через который интегрировать уже нельзя */
        const val MAX_GAP_MS = 3000L
    }
}
