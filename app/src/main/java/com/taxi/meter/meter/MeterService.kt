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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Держит счётчик живым при погасшем экране.
 *
 * Без переднего плана система засыпает вместе с приложением, обновления
 * координат прекращаются, и километры молча перестают набегать.
 */
class MeterService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TaxiApp
        startForeground(NOTIFICATION_ID, buildNotification(app.meter.snapshot.value))

        updates?.cancel()
        updates = scope.launch {
            while (true) {
                app.meter.tick()
                val snapshot = app.meter.snapshot.value
                if (!snapshot.isActive) {
                    stopSelf()
                    break
                }
                notify(buildNotification(snapshot))
                delay(1000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        updates?.cancel()
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(snapshot: MeterSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            snapshot.state == MeterState.PAUSED -> "Очікування"
            !snapshot.hasFix -> "Немає сигналу GPS"
            else -> "Лічильник працює"
        }

        val text = buildString {
            append(String.format(Locale.US, "%.2f км", snapshot.distanceKm))
            if (snapshot.idleMinutes > 0) append(" · очікування ${snapshot.idleMinutes} хв")
        }

        return NotificationCompat.Builder(this, TaxiApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, MeterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeterService::class.java))
        }
    }
}
