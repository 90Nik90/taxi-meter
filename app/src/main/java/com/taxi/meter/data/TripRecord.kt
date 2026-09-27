package com.taxi.meter.data

import kotlinx.serialization.Serializable

/** Способ оплаты поездки. Выбирается в итоговом попапе. */
enum class PaymentMethod(val title: String) {
    CASH("Готівка"),
    CARD("Картка"),
}

/**
 * Завершённая поездка. Записывается по кнопке «Завершити поїздку»
 * и дальше живёт только в статистике.
 */
@Serializable
data class TripRecord(
    val startedAtWallMs: Long,
    val finishedAtWallMs: Long,
    val distanceKm: Double,
    val runningMs: Long,
    val idleMs: Long,
    val total: Double,
    val profileName: String,
    /** Названия включённых услуг */
    val services: List<String> = emptyList(),
    /**
     * Способ оплаты: имя константы [PaymentMethod] — CASH или CARD.
     * Храним ключ, а не подпись: подпись зависит от языка интерфейса.
     */
    val payment: String = "",
    /** Сколько из суммы пришлось на доплаты за услуги, грн */
    val servicesTotal: Double = 0.0,
) {
    val totalMs: Long get() = runningMs + idleMs

    /** Подпись способа оплаты для показа; пусто, если способ не записан. */
    val paymentTitle: String
        get() = PaymentMethod.entries.firstOrNull { it.name == payment }?.title ?: ""
}
