package com.taxi.meter.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Сырая сессия с ELM327-совместимым адаптером через Bluetooth RFCOMM (SPP).
 *
 * Адаптер всегда завершает ответ символом-приглашением, на него и
 * ориентируемся, вместо блокирующего read() без таймаута.
 */
class ElmSession private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    /** Последний сырой ответ адаптера, для экрана диагностики. */
    @Volatile
    var lastRaw: String = ""
        private set

    /**
     * Инициализация адаптера. Возвращает описание протокола,
     * бросает [IOException], если ЭБУ не отвечает.
     */
    fun initialize(): String {
        command("ATZ", timeoutMs = 4000)      // сброс адаптера
        command("ATE0")                        // эхо выключить
        command("ATL0")                        // без переводов строки
        command("ATS0")                        // без пробелов в ответах
        command("ATH0")                        // без CAN-заголовков
        command("ATSP0", timeoutMs = 2000)     // автоопределение протокола

        // Первый реальный запрос поднимает шину. На Sonata 2017 это
        // ISO 15765-4 CAN 11 bit / 500 kbaud, но ATSP0 определит сам.
        var ok = false
        repeat(3) {
            val r = command("0100", timeoutMs = 6000)
            if (r.contains("41") && !r.isError()) ok = true
        }
        if (!ok) throw IOException("ЕБУ не відповідає. Увімкніть запалювання.")

        command("ATAT1")                       // адаптивные тайминги
        return command("ATDP", timeoutMs = 2000).ifBlank { "OBD-II" }
    }

    /** Скорость авто, км/ч (PID 010D), либо null если ответа нет. */
    fun readSpeed(): Int? = parseSpeed(command("010D", timeoutMs = 1200))

    /** Пробег с момента сброса ошибок, км (PID 0131). Шаг 1 км. */
    fun readDistanceSinceClear(): Int? =
        parseDistanceSinceClear(command("0131", timeoutMs = 1500))

    /** Оберти двигателя, об/хв (PID 010C). */
    fun readRpm(): Int? = parseRpm(command("010C", timeoutMs = 1200))

    /** Напруга бортовой сети по данным адаптера, В. */
    fun readVoltage(): Double? {
        val r = command("ATRV", timeoutMs = 1500).replace("V", "")
        val m = Regex("[0-9]+[.]?[0-9]*").find(r) ?: return null
        return m.value.toDoubleOrNull()
    }

    /** Отправить команду и вернуть очищенный ответ. */
    fun command(cmd: String, timeoutMs: Long = 1500): String {
        synchronized(this) {
            drain()
            output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            output.flush()
            val raw = readUntilPrompt(timeoutMs)
            lastRaw = raw
            return clean(raw, cmd)
        }
    }

    private fun drain() {
        try {
            while (input.available() > 0) {
                val chunk = ByteArray(input.available().coerceAtMost(512))
                if (input.read(chunk) <= 0) break
            }
        } catch (_: IOException) {
            // нечего вычитывать
        }
    }

    private fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val n = input.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                if (sb.indexOf(PROMPT) >= 0) break
            } else {
                try {
                    Thread.sleep(3)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return sb.toString()
    }

    private fun clean(raw: String, cmd: String): String = raw
        .replace(PROMPT, " ")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("SEARCHING...", " ")
        .replace(cmd, " ")           // на случай, если ATE0 не применился
        .trim()
        .uppercase()

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val PROMPT = ">"

        /** Стандартный UUID сервиса Serial Port Profile. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Підключення... спаренному адаптеру. Сначала пробуем обычный
         * secure-сокет, затем insecure и скрытый reflection-метод:
         * дешёвые клоны ELM327 часто отвечают только на один из них.
         */
        @SuppressLint("MissingPermission")
        @Throws(IOException::class)
        fun connect(device: BluetoothDevice): ElmSession {
            val socket = openSocket(device)
            return try {
                ElmSession(socket, socket.inputStream, socket.outputStream)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        @SuppressLint("MissingPermission")
        @Throws(IOException::class)
        private fun openSocket(device: BluetoothDevice): BluetoothSocket {
            val attempts: List<() -> BluetoothSocket> = listOf(
                { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                {
                    val m = device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for (attempt in attempts) {
                try {
                    val s = attempt()
                    s.connect()
                    return s
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IOException("Не вдалося відкрити з’єднання з адаптером", last)
        }

        internal fun String.isError(): Boolean {
            val u = uppercase()
            return u.isBlank() || u.contains("NO DATA") || u.contains("UNABLE") ||
                u.contains("ERROR") || u.contains("STOPPED") || u.contains("?") ||
                u.contains("BUFFER FULL")
        }

        /** Выделяет полезные hex-байты ответа, начиная с заданного заголовка. */
        internal fun payload(response: String, header: String): String? {
            if (response.isError()) return null
            val hex = response.replace(Regex("[^0-9A-F]"), "")
            val i = hex.indexOf(header)
            if (i < 0) return null
            return hex.substring(i + header.length)
        }

        internal fun parseSpeed(response: String): Int? {
            val p = payload(response, "410D") ?: return null
            if (p.length < 2) return null
            return p.substring(0, 2).toIntOrNull(16)
        }

        internal fun parseRpm(response: String): Int? {
            val p = payload(response, "410C") ?: return null
            if (p.length < 4) return null
            val a = p.substring(0, 2).toIntOrNull(16) ?: return null
            val b = p.substring(2, 4).toIntOrNull(16) ?: return null
            return (a * 256 + b) / 4
        }

        internal fun parseDistanceSinceClear(response: String): Int? {
            val p = payload(response, "4131") ?: return null
            if (p.length < 4) return null
            val a = p.substring(0, 2).toIntOrNull(16) ?: return null
            val b = p.substring(2, 4).toIntOrNull(16) ?: return null
            return a * 256 + b
        }
    }
}
