package com.taxi.meter.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Период, за который считается статистика. */
enum class StatsPeriod(val title: String, val hint: String, val days: Int) {
    DAY("День", "з початку доби", 1),
    WEEK("Тиждень", "останні 7 днів", 7),
    MONTH("Місяць", "останні 30 днів", 30),
    ALL("Усе", "усі збережені поїздки", 0),
}

/** Столбик графика выручки. */
data class Bucket(val label: String, val value: Double, val emphasized: Boolean = false)

/** Доля тарифа в периоде. */
data class ProfileSlice(val name: String, val trips: Int, val revenue: Double)

data class PeriodStats(
    val trips: List<TripRecord>,
    val revenue: Double,
    val cash: Double,
    val card: Double,
    val distanceKm: Double,
    val drivingMs: Long,
    val idleMs: Long,
    val servicesTotal: Double,
    val maxTrip: Double,
    val minTrip: Double,
    val daysWithTrips: Int,
    val byProfile: List<ProfileSlice>,
    val buckets: List<Bucket>,
    val bucketTitle: String,
) {
    val tripCount: Int get() = trips.size

    /**
     * Выручка поездок без отметки об оплате. Появляется только у записей,
     * сделанных до того, как способ оплаты стал обязательным.
     */
    val unpaid: Double get() = (revenue - cash - card).coerceAtLeast(0.0)

    /** Время, проведённое в поездках: движение плюс простой */
    val workMs: Long get() = drivingMs + idleMs

    val averageCheck: Double get() = if (trips.isEmpty()) 0.0 else revenue / trips.size
    val averageDistance: Double get() = if (trips.isEmpty()) 0.0 else distanceKm / trips.size
    val averageTripMs: Long get() = if (trips.isEmpty()) 0L else workMs / trips.size

    /** Сколько приносит час, проведённый в поездке */
    val revenuePerHour: Double
        get() = if (workMs <= 0L) 0.0 else revenue / (workMs / 3_600_000.0)

    val revenuePerKm: Double get() = if (distanceKm <= 0.0) 0.0 else revenue / distanceKm

    /** Доля времени в поездке, потраченная на простой, 0..1 */
    val idleShare: Double
        get() = if (workMs <= 0L) 0.0 else (idleMs.toDouble() / workMs).coerceIn(0.0, 1.0)

    val tripsPerDay: Double
        get() = if (daysWithTrips <= 0) 0.0 else tripCount.toDouble() / daysWithTrips

    val revenuePerDay: Double
        get() = if (daysWithTrips <= 0) 0.0 else revenue / daysWithTrips
}

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun dateOf(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

private fun startOfDayMs(date: LocalDate): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

private val weekdays = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

/** Начало периода по стенным часам; для «Всё» — ноль. */
fun periodStartMs(period: StatsPeriod, nowWallMs: Long): Long {
    if (period == StatsPeriod.ALL) return 0L
    val firstDay = dateOf(nowWallMs).minusDays(period.days - 1L)
    return startOfDayMs(firstDay)
}

/** Считает статистику по всем сохранённым поездкам за выбранный период. */
fun buildStats(
    allTrips: List<TripRecord>,
    period: StatsPeriod,
    nowWallMs: Long,
): PeriodStats {
    val today = dateOf(nowWallMs)
    val fromMs = periodStartMs(period, nowWallMs)
    val trips = allTrips.filter { it.finishedAtWallMs >= fromMs }

    val revenue = trips.sumOf { it.total }
    val cash = trips.filter { it.payment == PaymentMethod.CASH.name }.sumOf { it.total }
    val card = trips.filter { it.payment == PaymentMethod.CARD.name }.sumOf { it.total }

    val byProfile = trips
        .groupBy { it.profileName.ifBlank { "Без тарифу" } }
        .map { (name, list) -> ProfileSlice(name, list.size, list.sumOf { it.total }) }
        .sortedByDescending { it.revenue }

    val days = trips.map { dateOf(it.finishedAtWallMs) }.distinct().size

    val buckets: List<Bucket>
    val bucketTitle: String
    when {
        // За день график не строим: внутри суток он мало что объясняет
        period == StatsPeriod.DAY -> {
            buckets = emptyList()
            bucketTitle = ""
        }

        period == StatsPeriod.ALL -> {
            buckets = allTimeBuckets(trips, today)
            bucketTitle = "ВИТОРГ ПО ДНЯХ"
        }

        else -> {
            val firstDay = today.minusDays(period.days - 1L)
            buckets = dailyBuckets(trips, firstDay, period.days, today)
            bucketTitle = "ВИТОРГ ПО ДНЯХ"
        }
    }

    return PeriodStats(
        trips = trips.sortedByDescending { it.finishedAtWallMs },
        revenue = revenue,
        cash = cash,
        card = card,
        distanceKm = trips.sumOf { it.distanceKm },
        drivingMs = trips.sumOf { it.runningMs },
        idleMs = trips.sumOf { it.idleMs },
        servicesTotal = trips.sumOf { it.servicesTotal },
        maxTrip = trips.maxOfOrNull { it.total } ?: 0.0,
        minTrip = trips.minOfOrNull { it.total } ?: 0.0,
        daysWithTrips = days,
        byProfile = byProfile,
        buckets = buckets,
        bucketTitle = bucketTitle,
    )
}

/** Дневные столбики за последние [days] дней. */
private fun dailyBuckets(
    trips: List<TripRecord>,
    firstDay: LocalDate,
    days: Int,
    today: LocalDate,
): List<Bucket> {
    val sums = DoubleArray(days)
    trips.forEach { trip ->
        val index = ChronoUnit.DAYS.between(firstDay, dateOf(trip.finishedAtWallMs)).toInt()
        if (index in 0 until days) sums[index] += trip.total
    }
    return (0 until days).map { index ->
        val date = firstDay.plusDays(index.toLong())
        val label = when {
            days <= 7 -> weekdays[date.dayOfWeek.value - 1]
            index % 5 == 0 || index == days - 1 -> date.dayOfMonth.toString()
            else -> ""
        }
        Bucket(label = label, value = sums[index], emphasized = date == today)
    }
}

/** Для «Всё» — дни от первой поездки до сегодня, но не больше 60 столбиков. */
private fun allTimeBuckets(trips: List<TripRecord>, today: LocalDate): List<Bucket> {
    if (trips.isEmpty()) return emptyList()
    val firstTripDay = trips.minOf { dateOf(it.finishedAtWallMs) }
    val span = ChronoUnit.DAYS.between(firstTripDay, today).toInt() + 1
    val days = span.coerceIn(1, 60)
    val firstDay = today.minusDays(days - 1L)
    return dailyBuckets(trips, firstDay, days, today)
}
