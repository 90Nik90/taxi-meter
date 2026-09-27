package com.taxi.meter.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Состояние соединения с адаптером. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR, DEMO }

data class ObdState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val protocol: String? = null,
    val message: String? = null,
    /** Текущая скорость, км/год */
    val speedKmh: Int = 0,
    val rpm: Int? = null,
    val voltage: Double? = null,
    /** Пробег по PID 0131 с момента сброса ошибок, км (грубая сверка) */
    val distanceSinceClearKm: Int? = null,
    /** Фактическая частота опроса скорости, Гц */
    val pollHz: Double = 0.0,
) {
    val isLive: Boolean
        get() = connection == ConnectionState.CONNECTED || connection == ConnectionState.DEMO
}

data class BtDeviceInfo(val name: String, val address: String)

/**
 * Владеет соединением с адаптером и циклом опроса скорости.
 * Живёт на уровне Application, поэтому переживает повороты экрана
 * и работу из сервиса.
 */
class ObdManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state.asStateFlow()

    /** Слушатель выборок скорости: (км/год, момент времени в мс elapsedRealtime). */
    var onSpeedSample: ((Int, Long) -> Unit)? = null

    private var session: ElmSession? = null
    private var pollJob: Job? = null

    /** Ожидание следующей попытки переподключения */
    private var retryJob: Job? = null

    private var lastAddress: String? = null
    private var lastName: String? = null

    /** Повторять ли подключение после обрыва: выключается кнопкой «Відключити» */
    private var autoReconnect = false
    private var attempt = 0

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isBluetoothOn: Boolean
        get() = adapter?.isEnabled == true

    fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Список спаренных устройств. Адаптер OBD спаривается в настройках системы. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDeviceInfo> {
        if (!hasBluetoothPermission()) return emptyList()
        return try {
            adapter?.bondedDevices
                ?.map { BtDeviceInfo(it.name ?: it.address, it.address) }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    // --- Підключення...---------------------------------------------------

    /** Підключення...о действию пользователя: включает автоповтор. */
    fun connect(address: String, displayName: String?) {
        if (_state.value.connection == ConnectionState.CONNECTING) return
        lastAddress = address
        lastName = displayName
        autoReconnect = true
        attempt = 0
        openConnection()
    }

    @SuppressLint("MissingPermission")
    private fun openConnection() {
        val address = lastAddress ?: return
        retryJob?.cancel()
        retryJob = null
        pollJob?.cancel()
        closeSession()

        _state.value = ObdState(
            connection = ConnectionState.CONNECTING,
            deviceName = lastName,
            message = if (attempt > 0) "Перепідключення..." else "Підключення...",
        )
        pollJob = scope.launch(Dispatchers.IO) {
            val device: BluetoothDevice? = try {
                adapter?.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (device == null) {
                fail("Неправильна адреса адаптера")
                return@launch
            }
            val opened = try {
                ElmSession.connect(device).also { session = it }
            } catch (e: Exception) {
                fail(e.message ?: "Не вдалося підключитися")
                return@launch
            }
            val protocol = try {
                opened.initialize()
            } catch (e: Exception) {
                runCatching { opened.close() }
                session = null
                fail(e.message ?: "Адаптер не відповідає")
                return@launch
            }
            attempt = 0
            _state.value = _state.value.copy(
                connection = ConnectionState.CONNECTED,
                protocol = protocol,
                message = null,
            )
            pollLoop(opened)
        }
    }

    /** Демо-режим: скорость имитируется, Bluetooth не нужен. */
    fun startDemo() {
        disconnect()
        _state.value = ObdState(
            connection = ConnectionState.DEMO,
            deviceName = "Демо-режим",
            protocol = "Імітація",
        )
        pollJob = scope.launch(Dispatchers.Default) { demoLoop() }
    }

    /** Отключение по кнопке: автоповтор выключается. */
    fun disconnect() {
        autoReconnect = false
        retryJob?.cancel()
        retryJob = null
        pollJob?.cancel()
        pollJob = null
        closeSession()
        _state.value = ObdState(connection = ConnectionState.DISCONNECTED)
    }

    private fun closeSession() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    private fun fail(message: String) {
        closeSession()
        _state.value = _state.value.copy(
            connection = ConnectionState.ERROR,
            message = message,
            speedKmh = 0,
        )
        scheduleRetry()
    }

    /**
     * Повтор с нарастающей паузой: адаптер мог отвалиться на кочке, а мог
     * и остаться без питания при выключенном зажигании — во втором случае
     * долбиться каждую секунду смысла нет.
     */
    private fun scheduleRetry() {
        if (!autoReconnect || lastAddress == null) return
        val wait = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        attempt++
        retryJob = scope.launch {
            delay(wait)
            // Обнуляем до вызова: иначе openConnection отменит сам себя
            retryJob = null
            openConnection()
        }
    }

    // --- Циклы опроса ----------------------------------------------------

    private suspend fun pollLoop(elm: ElmSession) = withContext(Dispatchers.IO) {
        var errors = 0
        var tick = 0L
        var windowStart = SystemClock.elapsedRealtime()
        var windowCount = 0

        while (isActive) {
            val speed = try {
                elm.readSpeed()
            } catch (e: Exception) {
                null
            }
            val now = SystemClock.elapsedRealtime()

            if (speed == null) {
                errors++
                if (errors >= MAX_CONSECUTIVE_ERRORS) {
                    fail("Зв'язок з адаптером втрачено")
                    return@withContext
                }
            } else {
                errors = 0
                windowCount++
                onSpeedSample?.invoke(speed, now)
                _state.value = _state.value.copy(speedKmh = speed)
            }

            // Раз в секунду считаем фактическую частоту опроса.
            if (now - windowStart >= 1000) {
                val hz = windowCount * 1000.0 / (now - windowStart)
                _state.value = _state.value.copy(pollHz = (hz * 10).roundToInt() / 10.0)
                windowStart = now
                windowCount = 0
            }

            // Медленные параметры опрашиваем редко, чтобы не тормозить скорость.
            tick++
            if (tick % SLOW_EVERY == 0L) {
                val rpm = runCatching { elm.readRpm() }.getOrNull()
                val dist = runCatching { elm.readDistanceSinceClear() }.getOrNull()
                val volt = runCatching { elm.readVoltage() }.getOrNull()
                _state.value = _state.value.copy(
                    rpm = rpm ?: _state.value.rpm,
                    distanceSinceClearKm = dist ?: _state.value.distanceSinceClearKm,
                    voltage = volt ?: _state.value.voltage,
                )
            }

            delay(POLL_DELAY_MS)
        }
    }

    /**
     * Імітація равномерного движения: постоянные DEMO_SPEED_KMH без
     * остановок. Нужна для проверки интерфейса и расчётов без машины —
     * за минуту набегает ровно километр, и по счётчику сразу видно,
     * верно ли работает тарификация.
     */
    private suspend fun demoLoop() {
        var lastHzReport = SystemClock.elapsedRealtime()
        var count = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val speed = DEMO_SPEED_KMH
            onSpeedSample?.invoke(speed, now)
            count++
            var next = _state.value.copy(
                speedKmh = speed,
                rpm = if (speed == 0) 750 else 900 + speed * 28,
                voltage = 14.1,
            )
            if (now - lastHzReport >= 1000) {
                next = next.copy(pollHz = count * 1000.0 / (now - lastHzReport))
                lastHzReport = now
                count = 0
            }
            _state.value = next
            delay(POLL_DELAY_MS)
        }
    }

    private companion object {
        /**
         * ELM327 по Bluetooth отдаёт один PID примерно за 40-90 мс, поэтому
         * доп. пауза минимальна: реальная частота получается 10-20 Гц,
         * чего достаточно для интегрирования скорости.
         */
        const val POLL_DELAY_MS = 20L
        const val SLOW_EVERY = 40L
        const val MAX_CONSECUTIVE_ERRORS = 15

        /** Скорость, которую отдаёт демо-режим, км/ч */
        const val DEMO_SPEED_KMH = 60

        /** Паузы перед повторами подключения, мс; дальше повторяем с последней */
        val RETRY_DELAYS_MS = longArrayOf(3_000, 5_000, 10_000, 20_000, 30_000)
    }
}
