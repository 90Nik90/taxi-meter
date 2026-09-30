package com.taxi.meter.data

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** Считать расстояние по GPS: на калькуляторе появляются Старт и Стоп */
    val gpsEnabled: Boolean = false,
    /** Считать грубо по вышкам и Wi-Fi, когда спутники заглушены */
    val coarseEnabled: Boolean = false,
    /** Слушать только вышки и Wi-Fi: спутники не трогаем вовсе */
    val networkOnly: Boolean = false,
    /** id активного профиля */
    val activeProfileId: String? = null,
    /** Доплаты за услуги — одни и те же для всех тарифов */
    val servicePrices: ServicePrices = ServicePrices(),
)
