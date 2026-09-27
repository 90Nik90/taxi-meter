package com.taxi.meter.meter

import android.os.SystemClock
import com.taxi.meter.data.ExtraService
import com.taxi.meter.data.Fare
import com.taxi.meter.data.Profile
import com.taxi.meter.data.ServicePrices
import com.taxi.meter.data.calculateFare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TripState {
    /** Таксометр не запущен */
    IDLE,

    /** Идёт поездка, считается расстояние */
    RUNNING,

    /** Пауза, считается время простоя */
    PAUSED,

    /** Поїздку завершено, показан итог */
    FINISHED,
}

data class TripSnapshot(
    val state: TripState = TripState.IDLE,
    /** Пройденное расстояние с учётом калибровки, км */
    val distanceKm: Double = 0.0,
    /** Время в движении (таксометр запущен), мс */
    val runningMs: Long = 0,
    /** Время простоя (пауза), мс */
    val idleMs: Long = 0,
    /** Момент нажатия «Старт» по часам телефона; 0 — поездки не было */
    val startedAtWallMs: Long = 0,
    /** Момент нажатия «Стоп» по часам телефона; 0 — поездка не завершена */
    val finishedAtWallMs: Long = 0,
    /** Тариф, по которому считается поездка */
    val profile: Profile? = null,
    /** Включённые дополнительные услуги */
    val services: Set<ExtraService> = emptySet(),
    /** Цены услуг, общие для всех тарифов */
    val servicePrices: ServicePrices = ServicePrices(),
    /** Текущая или итоговая стоимость */
    val fare: Fare? = null,
) {
    val totalMs: Long get() = runningMs + idleMs
    val isActive: Boolean get() = state == TripState.RUNNING || state == TripState.PAUSED
}

/**
 * Считает поездку: интегрирует скорость с ЭБУ в расстояние и ведёт
 * два таймера (движение и простой).
 *
 * Одометр Hyundai Sonata 2017 недоступен через стандартные OBD-II PID,
 * поэтому фактический пробег получается интегрированием PID 010D
 * (скорость авто) методом трапеций по реальным меткам времени.
 * Коэффициент калибровки позволяет свести результат с одометром.
 */
class TripEngine {

    private val _snapshot = MutableStateFlow(TripSnapshot())
    val snapshot: StateFlow<TripSnapshot> = _snapshot.asStateFlow()

    private val lock = Any()

    private var state = TripState.IDLE
    private var profile: Profile? = null
    private var calibration: Double = 1.0

    /** Услуги живут между поездками и сбрасываются после завершения поездки. */
    private var services: Set<ExtraService> = emptySet()

    /** Цены услуг общие для всех тарифов, приходят из настроек. */
    private var servicePrices = ServicePrices()

    /** Сырое (не калиброванное) расстояние, км */
    private var rawKm = 0.0

    private var runningAccumMs = 0L
    private var idleAccumMs = 0L

    /** Момент последней смены состояния, elapsedRealtime, мс */
    private var segmentStartMs = 0L

    /**
     * Начало и конец поездки по стенным часам телефона. Для длительностей
     * они не годятся (пользователь может перевести время), поэтому
     * таймеры по-прежнему считаются по elapsedRealtime.
     */
    private var startedAtWallMs = 0L
    private var finishedAtWallMs = 0L

    /** Предыдущая выборка скорости для метода трапеций */
    private var lastSpeed: Int? = null
    private var lastSampleMs = 0L

    fun setProfile(p: Profile?) = synchronized(lock) {
        profile = p
        publish()
    }

    fun setServicePrices(prices: ServicePrices) = synchronized(lock) {
        servicePrices = prices
        publish()
    }

    /** Включить или выключить дополнительную услугу. */
    fun toggleService(service: ExtraService) = synchronized(lock) {
        services = if (service in services) services - service else services + service
        publish()
    }

    fun setCalibration(k: Double) = synchronized(lock) {
        calibration = if (k > 0.1 && k < 5.0) k else 1.0
        publish()
    }

    fun start(p: Profile?) = synchronized(lock) {
        profile = p
        rawKm = 0.0
        runningAccumMs = 0
        idleAccumMs = 0
        lastSpeed = null
        lastSampleMs = 0
        segmentStartMs = SystemClock.elapsedRealtime()
        startedAtWallMs = System.currentTimeMillis()
        finishedAtWallMs = 0
        state = TripState.RUNNING
        publish()
    }

    fun pause() = synchronized(lock) {
        if (state != TripState.RUNNING) return@synchronized
        val now = SystemClock.elapsedRealtime()
        runningAccumMs += now - segmentStartMs
        segmentStartMs = now
        lastSpeed = null              // разрыв интегрирования
        state = TripState.PAUSED
        publish()
    }

    fun resume() = synchronized(lock) {
        if (state != TripState.PAUSED) return@synchronized
        val now = SystemClock.elapsedRealtime()
        idleAccumMs += now - segmentStartMs
        segmentStartMs = now
        lastSpeed = null
        state = TripState.RUNNING
        publish()
    }

    /** Завершити поїздку и зафиксировать итог. */
    fun stop() = synchronized(lock) {
        if (state != TripState.RUNNING && state != TripState.PAUSED) return@synchronized
        val now = SystemClock.elapsedRealtime()
        if (state == TripState.RUNNING) runningAccumMs += now - segmentStartMs
        else idleAccumMs += now - segmentStartMs
        segmentStartMs = now
        lastSpeed = null
        finishedAtWallMs = System.currentTimeMillis()
        state = TripState.FINISHED
        publish()
    }

    /** Сбросить итог и вернуться в исходное состояние. */
    fun reset() = synchronized(lock) {
        state = TripState.IDLE
        rawKm = 0.0
        runningAccumMs = 0
        idleAccumMs = 0
        lastSpeed = null
        lastSampleMs = 0
        startedAtWallMs = 0
        finishedAtWallMs = 0
        services = emptySet()
        publish()
    }

    /**
     * Новая выборка скорости от адаптера.
     *
     * @param speedKmh скорость авто, км/год
     * @param atMs метка времени, SystemClock.elapsedRealtime()
     */
    fun onSpeedSample(speedKmh: Int, atMs: Long) = synchronized(lock) {
        if (state != TripState.RUNNING) {
            lastSpeed = null
            lastSampleMs = atMs
            return@synchronized
        }
        val prev = lastSpeed
        val dt = atMs - lastSampleMs
        lastSpeed = speedKmh
        lastSampleMs = atMs
        if (prev == null) return@synchronized
        // Пропуски дольше MAX_GAP_MS (реконнект, зависший адаптер) не считаем:
        // иначе одна дырка в связи дала бы километры из воздуха.
        if (dt <= 0 || dt > MAX_GAP_MS) return@synchronized
        rawKm += (prev + speedKmh) / 2.0 * (dt / 3_600_000.0)
        publish()
    }

    /** Пересчитать живые таймеры (вызывается тикером UI/сервиса). */
    fun tick() = synchronized(lock) { publish() }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val segment = if (state == TripState.RUNNING || state == TripState.PAUSED) {
            (now - segmentStartMs).coerceAtLeast(0)
        } else 0L

        val running = runningAccumMs + if (state == TripState.RUNNING) segment else 0L
        val idle = idleAccumMs + if (state == TripState.PAUSED) segment else 0L
        val km = rawKm * calibration
        val p = profile

        _snapshot.value = TripSnapshot(
            state = state,
            distanceKm = km,
            runningMs = running,
            idleMs = idle,
            startedAtWallMs = startedAtWallMs,
            finishedAtWallMs = finishedAtWallMs,
            profile = p,
            services = services,
            servicePrices = servicePrices,
            fare = p?.calculateFare(km, idle / 1000, services, servicePrices),
        )
    }

    private companion object {
        const val MAX_GAP_MS = 3000L
    }
}
