package com.taxi.meter.data

import kotlinx.serialization.Serializable

/** Способ оплаты поездки. Выбирается перед сохранением расчёта. */
enum class PaymentMethod(val title: String) {
    CASH("Готівка"),
    CARD("Картка"),
}

/**
 * Посчитанная поездка. Записывается по кнопке «Зберегти поїздку»
 * и дальше живёт только в статистике.
 */
@Serializable
data class TripRecord(
    /** Момент записи; у старых записей — момент нажатия «Старт» */
    val startedAtWallMs: Long,
    /** Момент записи, он же ключ записи в истории */
    val finishedAtWallMs: Long,
    val distanceKm: Double,
    /**
     * Время в движении. У расчётов калькулятора всегда 0: поездку
     * не замеряли по часам. Поле оставлено, чтобы читались старые
     * записи, сделанные версией с таксометром.
     */
    val runningMs: Long,
    /** Ожидание: минуты, переведённые в миллисекунды */
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
    /** Сколько километров посчитано грубо, без спутников */
    val coarseKm: Double = 0.0,
) {
    /** Подпись способа оплаты для показа; пусто, если способ не записан. */
    val paymentTitle: String
        get() = PaymentMethod.entries.firstOrNull { it.name == payment }?.title ?: ""

    /** Ожидание в целых минутах */
    val idleMinutes: Int get() = (idleMs / 60_000L).toInt()
}
