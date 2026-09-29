package com.taxi.meter.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Период, за который считается статистика. */
enum class StatsPeriod(val title: String, val hint: String) {
    DAY("День", "з початку доби"),
    WEEK("Тиждень", "поточний тиждень, Пн — Нд"),
    MONTH("Місяць", "поточний місяць"),
    ALL("Усе", "усі збережені поїздки"),
}

/** Столбик графика выручки. */
data class Bucket(
    val label: String,
    val value: Double,
    /** Начало дня — нужно для подписи над выбранным столбиком */
    val dayMs: Long,
    val emphasized: Boolean = false,
)

/** Доля тарифа в периоде. */
data class ProfileSlice(val name: String, val trips: Int, val revenue: Double)

data class PeriodStats(
    val trips: List<TripRecord>,
    val revenue: Double,
    val cash: Double,
    val card: Double,
    val distanceKm: Double,
    /** Суммарное ожидание, мс (введённые водителем минуты) */
    val idleMs: Long,
    val servicesTotal: Double,
    val maxTrip: Double,
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
/**
 * Начало периода по стенным часам; для «Всё» — ноль.
 *
 * Неделя и месяц календарные: с понедельника и с первого числа.
 * Водитель считает их именно так, да и список дней иначе разошёлся
 * бы с итогами.
 */
fun periodStartMs(period: StatsPeriod, nowWallMs: Long): Long {
    val today = dateOf(nowWallMs)
    return when (period) {
        StatsPeriod.DAY -> startOfDayMs(today)
        StatsPeriod.WEEK -> startOfDayMs(today.with(DayOfWeek.MONDAY))
        StatsPeriod.MONTH -> startOfDayMs(today.withDayOfMonth(1))
        StatsPeriod.ALL -> 0L
    }
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

        period == StatsPeriod.WEEK -> {
            buckets = dailyBuckets(trips, today.with(DayOfWeek.MONDAY), 7, today)
            bucketTitle = "ВИТОРГ ПО ДНЯХ"
        }

        else -> {
            // Месяц рисуем с первого числа по сегодня: пустые будущие
            // дни только сплющили бы столбики
            buckets = dailyBuckets(trips, today.withDayOfMonth(1), today.dayOfMonth, today)
            bucketTitle = "ВИТОРГ ПО ДНЯХ"
        }
    }

    return PeriodStats(
        trips = trips.sortedByDescending { it.finishedAtWallMs },
        revenue = revenue,
        cash = cash,
        card = card,
        distanceKm = trips.sumOf { it.distanceKm },
        idleMs = trips.sumOf { it.idleMs },
        servicesTotal = trips.sumOf { it.servicesTotal },
        maxTrip = trips.maxOfOrNull { it.total } ?: 0.0,
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
        Bucket(
            label = label,
            value = sums[index],
            dayMs = startOfDayMs(date),
            emphasized = date == today,
        )
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

/** Один день в списке поездок за неделю или месяц. */
data class DaySlice(
    val dayMs: Long,
    val title: String,
    val trips: Int,
    val revenue: Double,
    val isToday: Boolean,
)

private val weekdaysFull = arrayOf(
    "Понеділок", "Вівторок", "Середа", "Четвер", "П’ятниця", "Субота", "Неділя",
)

private val monthsGenitive = arrayOf(
    "січня", "лютого", "березня", "квітня", "травня", "червня",
    "липня", "серпня", "вересня", "жовтня", "листопада", "грудня",
)

/**
 * Дни периода для списка поездок.
 *
 * За неделю — семь дней от понедельника, за месяц — числа от первого
 * до конца, за «Усе» — только те дни, в которые что-то было.
 * Пустые дни остаются в списке: по ним видно, что день был выходной,
 * а не потерялся.
 */
fun buildDays(
    allTrips: List<TripRecord>,
    period: StatsPeriod,
    nowWallMs: Long,
): List<DaySlice> {
    val today = dateOf(nowWallMs)
    val byDay = allTrips.groupBy { dateOf(it.finishedAtWallMs) }

    val dates: List<LocalDate> = when (period) {
        StatsPeriod.WEEK -> {
            val monday = today.with(DayOfWeek.MONDAY)
            (0 until 7).map { monday.plusDays(it.toLong()) }
        }

        StatsPeriod.MONTH -> {
            val first = today.withDayOfMonth(1)
            (0 until today.lengthOfMonth()).map { first.plusDays(it.toLong()) }
        }

        else -> byDay.keys.sortedDescending()
    }

    return dates.map { date ->
        val trips = byDay[date].orEmpty()
        val title = if (period == StatsPeriod.MONTH) {
            "${date.dayOfMonth} (${weekdays[date.dayOfWeek.value - 1]})"
        } else {
            "${weekdaysFull[date.dayOfWeek.value - 1]}, " +
                "${date.dayOfMonth} ${monthsGenitive[date.monthValue - 1]}"
        }
        DaySlice(
            dayMs = startOfDayMs(date),
            title = title,
            trips = trips.size,
            revenue = trips.sumOf { it.total },
            isToday = date == today,
        )
    }
}

/** Поездки одного дня, новые первыми. */
fun tripsOfDay(allTrips: List<TripRecord>, dayMs: Long): List<TripRecord> {
    val next = dayMs + 24L * 60 * 60 * 1000
    return allTrips
        .filter { it.finishedAtWallMs in dayMs until next }
        .sortedByDescending { it.finishedAtWallMs }
}

/** Заголовок экрана одного дня: «28 вересня». */
fun dayTitle(dayMs: Long): String {
    val date = dateOf(dayMs)
    return "${date.dayOfMonth} ${monthsGenitive[date.monthValue - 1]}"
}
