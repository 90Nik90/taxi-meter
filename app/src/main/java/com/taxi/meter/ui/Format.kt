package com.taxi.meter.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
