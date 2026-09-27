package com.taxi.meter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.taxi.meter.data.Storage
import com.taxi.meter.meter.TripEngine
import com.taxi.meter.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class TaxiApp : Application() {

    lateinit var storage: Storage
        private set
    lateinit var obd: ObdManager
        private set
    lateinit var trip: TripEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        trip = TripEngine()
        obd = ObdManager(this, appScope)

        // Единственный источник расстояния: выборки скорости с ЭБУ.
        obd.onSpeedSample = { speed, atMs -> trip.onSpeedSample(speed, atMs) }

        trip.setProfile(storage.activeProfile)
        trip.setCalibration(storage.settings.value.calibration)
        trip.setServicePrices(storage.settings.value.servicePrices)

        createChannel()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "trip"
    }
}
