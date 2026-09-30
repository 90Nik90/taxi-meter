package com.taxi.meter.data

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * Тарифный профиль. Все цены — в гривнах.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    /** Назва профілю */
    val name: String = "Новий профіль",
    /** Вартість 1 км, грн */
    val pricePerKm: Double = 15.0,
    /** Вартість простою, грн/хвуту */
    val pricePerIdleMinute: Double = 2.0,
    /** Мінімальна ціна за поїздку, грн */
    val minPrice: Double = 60.0,
    /** Мінімальна відстань, до которого действует минимальная цена, км */
    val minDistanceKm: Double = 2.0,
)

/**
 * Доплаты за дополнительные услуги, грн. Общие для всех тарифов,
 * поэтому живут в настройках приложения, а не в профиле.
 */
@Serializable
data class ServicePrices(
    val children: Double = 20.0,
    val pets: Double = 30.0,
    val luggage: Double = 20.0,
)

/**
 * Дополнительные услуги. Включаются на главном экране перед поездкой
 * или во время неё; цены задаются один раз для всех тарифов.
 */
enum class ExtraService(val title: String) {
    CHILDREN("Діти"),
    PETS("Тварини"),
    LUGGAGE("Багаж у салоні");

    fun priceIn(prices: ServicePrices): Double = when (this) {
        CHILDREN -> prices.children
        PETS -> prices.pets
        LUGGAGE -> prices.luggage
    }
}

/** Разбивка итоговой стоимости поездки. */
data class Fare(
    /** Фактически пройденное расстояние, км */
    val distanceKm: Double,
    /** Оплачиваемое расстояние сверх минимального, км (по факту, дробное) */
    val billedKm: Double,
    /** Оплачиваемые целые минуты простоя */
    val billedIdleMinutes: Int,
    /** Часть стоимости за расстояние (включая минимальную цену и доплаты за услуги) */
    val distancePart: Double,
    /** Сумма доплат за включённые услуги */
    val servicesPart: Double,
    /** Часть стоимости за простой */
    val idlePart: Double,
    val total: Double,
    /** true, если сработала минимальная цена за поездку */
    val minPriceApplied: Boolean,
)

/**
 * Расчёт стоимости.
 *
 * Расстояние оплачивается по факту, до метра: на счётчике сумма растёт
 * вместе с машиной, а не прыгает раз в километр. Простой остаётся
 * поминутным — как пошла очередная минута, она оплачивается полностью.
 *
 * Доплаты за включённые услуги прибавляются к минимальной цене поездки.
 *
 * Если задано минимальное расстояние (> 0):
 *   - в его пределах берётся только минимальная цена;
 *   - сверх него — минимальная цена + пройденные километры * цена за км.
 * Если минимальное расстояние равно 0:
 *   - берётся пройденные километры * цена за км, но не меньше минимальной цены.
 * Простой всегда добавляется сверху.
 */
fun Profile.calculateFare(
    distanceKm: Double,
    idleSeconds: Long,
    services: Set<ExtraService> = emptySet(),
    servicePrices: ServicePrices = ServicePrices(),
): Fare {
    val km = max(0.0, distanceKm)

    val billedMinutes = ceilUnits(max(0L, idleSeconds) / 60.0)
    val idlePart = billedMinutes * pricePerIdleMinute

    // Расстояние — по факту, до метра; округляем только итоговую сумму
    val billedKm = max(0.0, km - minDistanceKm)

    val servicesPart = services.sumOf { it.priceIn(servicePrices) }
    val effectiveMinPrice = minPrice + servicesPart

    val distancePart: Double
    val minApplied: Boolean
    if (minDistanceKm > 0.0) {
        distancePart = effectiveMinPrice + billedKm * pricePerKm
        minApplied = billedKm <= 0.0
    } else {
        val raw = billedKm * pricePerKm
        if (raw < effectiveMinPrice) {
            distancePart = effectiveMinPrice
            minApplied = true
        } else {
            distancePart = raw
            minApplied = false
        }
    }

    return Fare(
        distanceKm = km,
        billedKm = billedKm,
        billedIdleMinutes = billedMinutes,
        distancePart = round2(distancePart),
        servicesPart = round2(servicesPart),
        idlePart = round2(idlePart),
        total = round2(distancePart + idlePart),
        minPriceApplied = minApplied,
    )
}

/**
 * Округление вверх с запасом на погрешность double: ровно 120 секунд
 * простоя не должны открывать третью минуту.
 */
private fun ceilUnits(value: Double): Int =
    ceil(value - 1e-9).toInt().coerceAtLeast(0)

private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
