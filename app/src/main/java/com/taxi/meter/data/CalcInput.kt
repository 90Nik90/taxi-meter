package com.taxi.meter.data

/**
 * Что водитель ввёл в калькуляторе. Расстояние он узнаёт сам (карты,
 * навигатор), приложение только считает по тарифу.
 *
 * Тариф и цены услуг здесь не хранятся: они живут в [Storage] и берутся
 * при расчёте, иначе правка тарифа не подхватилась бы в открытом
 * калькуляторе.
 */
data class CalcInput(
    /** Введённое расстояние, как набрано: «12.4» */
    val distanceText: String = "",
    /** Введённое время ожидания в целых минутах: «5» */
    val idleText: String = "",
    val services: Set<ExtraService> = emptySet(),
    val payment: PaymentMethod? = null,
) {
    /** Расстояние; запятая с клавиатуры считается точкой. */
    val distanceKm: Double
        get() = distanceText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0

    /** Ожидание вводится целыми минутами, поэтому округлять нечего. */
    val idleMinutes: Int
        get() = idleText.toIntOrNull()?.coerceAtLeast(0) ?: 0

    val idleSeconds: Long get() = idleMinutes * 60L

    /** Есть ли что сбрасывать. */
    val isEmpty: Boolean
        get() = distanceKm <= 0.0 && idleMinutes == 0 && services.isEmpty() && payment == null

    fun fare(profile: Profile?, prices: ServicePrices): Fare? =
        profile?.calculateFare(distanceKm, idleSeconds, services, prices)
}
