package com.taxi.meter.meter

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.taxi.meter.MainActivity
import com.taxi.meter.R
import com.taxi.meter.TaxiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Держит процесс живым, пока идёт поездка: опрос адаптера не должен
 * прерываться, когда экран погас или приложение свёрнуто.
 * Одновременно тикает таймеры и обновляет уведомление.
 */
class MeterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TaxiApp
        try {
            startForeground(NOTIFICATION_ID, buildNotification(app.trip.snapshot.value))
        } catch (e: Exception) {
            // Например, если разрешение на Bluetooth отозвано: сервис типа
            // connectedDevice запуститься не может. Поездка продолжит
            // считаться, пока приложение на экране.
            stopSelf()
            return START_NOT_STICKY
        }

        if (ticker == null) {
            ticker = scope.launch {
                var lastText = ""
                while (isActive) {
                    app.trip.tick()
                    val snap = app.trip.snapshot.value
                    if (!snap.isActive) {
                        stopSelf()
                        break
                    }
                    val text = notificationText(snap)
                    if (text != lastText) {
                        lastText = text
                        notify(buildNotification(snap))
                    }
                    delay(TICK_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun notificationText(snap: TripSnapshot): String {
        val km = String.format(Locale.US, "%.2f км", snap.distanceKm)
        val money = snap.fare?.let { String.format(Locale.US, "%.2f грн", it.total) } ?: "-"
        val idle = formatDuration(snap.idleMs)
        return "$km · $money · простій $idle"
    }

    private fun buildNotification(snap: TripSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (snap.state) {
            TripState.RUNNING -> "Поїздка триває"
            TripState.PAUSED -> "Пауза — триває простій"
            else -> "Таксометр"
        }
        return NotificationCompat.Builder(this, TaxiApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(title)
            .setContentText(notificationText(snap))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, MeterService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Запуск из фона может быть запрещён системой — поездка
                // всё равно считается, пока приложение на экране.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeterService::class.java))
        }
    }
}

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm")

/** Дата и время по часам телефона, например «14 марта, 08:30». */
fun formatDate(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * Момент по часам телефона в формате ЧЧ:ММ.
 * Ноль означает, что отметка не проставлена.
 */
fun formatClock(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else clockFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** ч:мм:сс или мм:сс */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
