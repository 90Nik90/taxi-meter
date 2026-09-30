package com.taxi.meter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Простое хранилище на SharedPreferences + JSON. Профилей единицы,
 * база данных здесь была бы избыточна.
 */
class Storage(context: Context) {

    private val prefs = context.getSharedPreferences("taxi_meter", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _trips = MutableStateFlow(loadTrips())

    /** Сохранённые поездки, новые первыми. */
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()

    init {
        // Старые записи хранили подпись способа оплаты, а не ключ,
        // и подпись была русской — переводим на текущий формат.
        val migrated = _trips.value.map {
            when (it.payment) {
                "Наличные", "Готівка" -> it.copy(payment = PaymentMethod.CASH.name)
                "Карта", "Картка" -> it.copy(payment = PaymentMethod.CARD.name)
                else -> it
            }
        }
        // Записи без способа оплаты не выбрасываем: счётчик сохраняет
        // поездку сразу по «Стоп», а оплату водитель отмечает после.
        if (migrated != _trips.value) persistTrips(migrated)
    }

    val activeProfile: Profile?
        get() {
            val list = _profiles.value
            val id = _settings.value.activeProfileId
            return list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }

    /**
     * Тарифы из хранилища.
     *
     * Заводские подставляются только при первом запуске. Пустой
     * сохранённый список — это выбор водителя: он удалил всё, и
     * возвращать заводские нельзя, иначе удалить их невозможно.
     */
    private fun loadProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return defaultProfiles()
        return runCatching { json.decodeFromString<List<Profile>>(raw) }
            .getOrElse { defaultProfiles() }
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrElse { AppSettings() }
    }

    private fun persistProfiles(list: List<Profile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list)).apply()
        _profiles.value = list
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
        _settings.value = next
    }

    fun saveProfile(profile: Profile) {
        val list = _profiles.value.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(profile)
        persistProfiles(list)
    }

    fun deleteProfile(id: String) {
        persistProfiles(_profiles.value.filterNot { it.id == id })
        if (_settings.value.activeProfileId == id) {
            updateSettings { it.copy(activeProfileId = _profiles.value.firstOrNull()?.id) }
        }
    }

    fun selectProfile(id: String) = updateSettings { it.copy(activeProfileId = id) }

    // --- Поездки --------------------------------------------------------

    private fun loadTrips(): List<TripRecord> {
        val raw = prefs.getString(KEY_TRIPS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<TripRecord>>(raw) }
            .getOrElse { emptyList() }
    }

    /** Записать посчитанную поездку. Новые лежат первыми. */
    fun addTrip(record: TripRecord) {
        persistTrips((listOf(record) + _trips.value).take(MAX_TRIPS))
    }

    /**
     * Видалити поїздку из истории. Ключ — момент записи: две поездки
     * не могут быть записаны в одну и ту же миллисекунду.
     */
    fun deleteTrip(finishedAtWallMs: Long) {
        persistTrips(_trips.value.filterNot { it.finishedAtWallMs == finishedAtWallMs })
    }

    private fun persistTrips(list: List<TripRecord>) {
        prefs.edit().putString(KEY_TRIPS, json.encodeToString(list)).apply()
        _trips.value = list
    }

    private fun defaultProfiles(): List<Profile> = listOf(
        Profile(
            name = "Місто",
            pricePerKm = 15.0,
            pricePerIdleMinute = 2.0,
            minPrice = 60.0,
            minDistanceKm = 2.0,
        ),
        Profile(
            name = "Нічний",
            pricePerKm = 20.0,
            pricePerIdleMinute = 3.0,
            minPrice = 90.0,
            minDistanceKm = 2.0,
        ),
    )

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_SETTINGS = "settings"

        const val KEY_TRIPS = "trips"

        /** Сколько поездок храним в истории */
        const val MAX_TRIPS = 500
    }
}
