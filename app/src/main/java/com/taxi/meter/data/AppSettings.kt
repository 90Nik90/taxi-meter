package com.taxi.meter.data

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** Bluetooth-адрес выбранного OBD-адаптера */
    val deviceAddress: String? = null,
    /** Имя выбранного адаптера (для отображения) */
    val deviceName: String? = null,
    /** id активного профиля */
    val activeProfileId: String? = null,
    /**
     * Коэффициент калибровки пробега. Скорость с ЭБУ обычно слегка
     * завышена/занижена относительно одометра; 1.0 = без коррекции.
     */
    val calibration: Double = 1.0,
    /** Демо-режим: скорость имитируется, Bluetooth не используется */
    val demoMode: Boolean = false,
    /** Доплаты за услуги — одни и те же для всех тарифов */
    val servicePrices: ServicePrices = ServicePrices(),
)
