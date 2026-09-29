package com.taxi.meter.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm")

/** Дата и время по часам телефона, например «14 березня, 08:30». */
fun formatDate(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * Момент по часам телефона в формате ЧЧ:ММ.
 * Ноль означает, что отметка не проставлена.
 */
fun formatClock(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else clockFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Ожидание в минутах словами: «5 хв». */
fun formatMinutes(minutes: Int): String = "$minutes хв"

/** Длительность поездки: ч:мм:сс или мм:сс. */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
