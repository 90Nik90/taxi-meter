package com.taxi.meter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.taxi.meter.data.Storage
import com.taxi.meter.gps.GpsSource
import com.taxi.meter.meter.DistanceMeter

class TaxiApp : Application() {

    lateinit var storage: Storage
        private set
    lateinit var meter: DistanceMeter
        private set
    lateinit var gps: GpsSource
        private set

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        meter = DistanceMeter()
        gps = GpsSource(this)

        // Единственный источник расстояния: выборки скорости с GPS
        gps.onSpeedSample = { kmh, atMs -> meter.onSpeedSample(kmh, atMs) }
        gps.onCoarseDistance = { km -> meter.onCoarseDistance(km) }
        gps.onFixLost = { meter.onFixLost() }

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
        const val CHANNEL_ID = "meter"
    }
}
