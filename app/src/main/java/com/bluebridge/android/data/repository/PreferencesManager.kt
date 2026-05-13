package com.bluebridge.android.data.repository

import com.bluebridge.android.data.models.CommandHistoryEntry
import com.bluebridge.android.data.models.UiColorOverrides
import com.bluebridge.android.data.models.UiColorSlot
import com.bluebridge.android.data.models.WidgetVehicleSnapshot
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.UUID
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bluebridge_prefs")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USERNAME = stringPreferencesKey("username")
        val SERVICE_PIN = stringPreferencesKey("service_pin")
        val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        val PASSWORD_REQUIRED = booleanPreferencesKey("password_required")
        val SELECTED_VIN = stringPreferencesKey("selected_vin")
        val REGION = stringPreferencesKey("region")
        val TEMPERATURE_UNIT = stringPreferencesKey("temp_unit")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val LAST_BIOMETRIC_UNLOCK_AT = longPreferencesKey("last_biometric_unlock_at")
        val LAST_STATUS_REFRESH = longPreferencesKey("last_status_refresh")
        val DEFAULT_CLIMATE_TEMP = stringPreferencesKey("default_climate_temp")
        val VALET_MODE_ENABLED = booleanPreferencesKey("valet_mode_enabled")
        val ACTIVE_DRIVER_PROFILE_ID = stringPreferencesKey("active_driver_profile_id")
        val APP_THEME = stringPreferencesKey("app_theme")
        val UI_COLOR_OVERRIDES_JSON = stringPreferencesKey("ui_color_overrides_json")
        val PROFILE_PRIMARY_PHOTO_URI = stringPreferencesKey("profile_primary_photo_uri")
        val PROFILE_GUEST_PHOTO_URI = stringPreferencesKey("profile_guest_photo_uri")
        val PROFILE_VALET_PHOTO_URI = stringPreferencesKey("profile_valet_photo_uri")
        val COMMAND_HISTORY_JSON = stringPreferencesKey("command_history_json")
        val WIDGET_VEHICLE_NAME = stringPreferencesKey("widget_vehicle_name")
        val WIDGET_DOORS_LOCKED = booleanPreferencesKey("widget_doors_locked")
        val WIDGET_BATTERY_PERCENT = intPreferencesKey("widget_battery_percent")
        val WIDGET_RANGE_MILES = intPreferencesKey("widget_range_miles")
        val WIDGET_CHARGING_LABEL = stringPreferencesKey("widget_charging_label")
        val WIDGET_MESSAGE = stringPreferencesKey("widget_message")
        val WIDGET_DETAIL_ONE = stringPreferencesKey("widget_detail_one")
        val WIDGET_DETAIL_TWO = stringPreferencesKey("widget_detail_two")
        val WIDGET_DETAIL_THREE = stringPreferencesKey("widget_detail_three")
        val WIDGET_UPDATED_AT = longPreferencesKey("widget_updated_at")
        val CANADA_DEVICE_ID = stringPreferencesKey("canada_device_id")
    }

    val accessToken: Flow<String?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[ACCESS_TOKEN] }

    val username: Flow<String?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[USERNAME] }

    val servicePin: Flow<String?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[SERVICE_PIN] }

    val isLoggedIn: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val token = prefs[ACCESS_TOKEN]
            val expiresAt = prefs[TOKEN_EXPIRES_AT] ?: 0L
            !token.isNullOrEmpty() && System.currentTimeMillis() < expiresAt
        }

    val passwordRequired: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PASSWORD_REQUIRED] ?: false }

    val hasRecoverableSession: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val token = prefs[ACCESS_TOKEN]
            val passwordRequired = prefs[PASSWORD_REQUIRED] ?: false
            !token.isNullOrBlank() && !passwordRequired
        }

    val selectedVin: Flow<String?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[SELECTED_VIN] }

    val region: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[REGION] ?: "US_HYUNDAI" }

    val temperatureUnit: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[TEMPERATURE_UNIT] ?: "F" }

    val biometricEnabled: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[BIOMETRIC_ENABLED] ?: false }

    val lastBiometricUnlockAt: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[LAST_BIOMETRIC_UNLOCK_AT] ?: 0L }

    val defaultClimateTemp: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[DEFAULT_CLIMATE_TEMP] ?: "72" }


    val valetModeEnabled: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[VALET_MODE_ENABLED] ?: false }

    val activeDriverProfileId: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[ACTIVE_DRIVER_PROFILE_ID] ?: "primary" }

    val appTheme: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[APP_THEME] ?: "hyundai_night" }

    val uiColorOverrides: Flow<UiColorOverrides> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseUiColorOverrides(prefs[UI_COLOR_OVERRIDES_JSON].orEmpty()) }

    val driverProfilePhotoUris: Flow<Map<String, String?>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            mapOf(
                "primary" to prefs[PROFILE_PRIMARY_PHOTO_URI],
                "guest" to prefs[PROFILE_GUEST_PHOTO_URI],
                "valet" to prefs[PROFILE_VALET_PHOTO_URI]
            )
        }

    val lastStatusRefresh: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[LAST_STATUS_REFRESH] ?: 0L }

    val commandHistory: Flow<List<CommandHistoryEntry>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> parseCommandHistory(prefs[COMMAND_HISTORY_JSON].orEmpty()) }

    val widgetVehicleSnapshot: Flow<WidgetVehicleSnapshot> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            WidgetVehicleSnapshot(
                vehicleName = prefs[WIDGET_VEHICLE_NAME] ?: "BlueBridge",
                doorsLocked = prefs[WIDGET_DOORS_LOCKED],
                batteryPercent = prefs[WIDGET_BATTERY_PERCENT],
                rangeMiles = prefs[WIDGET_RANGE_MILES],
                chargingLabel = prefs[WIDGET_CHARGING_LABEL] ?: "Open app to refresh",
                message = prefs[WIDGET_MESSAGE] ?: "Tap to open app",
                detailOne = prefs[WIDGET_DETAIL_ONE] ?: "Doors —",
                detailTwo = prefs[WIDGET_DETAIL_TWO] ?: "Climate —",
                detailThree = prefs[WIDGET_DETAIL_THREE] ?: "Tires —",
                updatedAtMillis = prefs[WIDGET_UPDATED_AT] ?: 0L
            )
        }


    suspend fun getOrCreateCanadaDeviceId(): String {
        val existing = dataStore.data.first()[CANADA_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val generated = Base64.encodeToString(
            UUID.randomUUID().toString().replace("-", "").toByteArray(),
            Base64.NO_WRAP
        )
        dataStore.edit { prefs -> prefs[CANADA_DEVICE_ID] = generated }
        return generated
    }

    suspend fun saveSession(accessToken: String, refreshToken: String, username: String, expiresIn: Int, servicePin: String? = null) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            prefs[REFRESH_TOKEN] = refreshToken
            prefs[USERNAME] = username
            if (!servicePin.isNullOrBlank()) prefs[SERVICE_PIN] = servicePin
            prefs[TOKEN_EXPIRES_AT] = System.currentTimeMillis() + (expiresIn * 1000L)
            prefs[PASSWORD_REQUIRED] = false
        }
    }

    suspend fun clearSession(requirePassword: Boolean = true) {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(USERNAME)
            prefs.remove(SERVICE_PIN)
            prefs.remove(TOKEN_EXPIRES_AT)
            prefs[PASSWORD_REQUIRED] = requirePassword
        }
    }

    suspend fun setSelectedVin(vin: String) {
        dataStore.edit { it[SELECTED_VIN] = vin }
    }

    suspend fun setServicePin(pin: String) {
        dataStore.edit { prefs ->
            if (pin.isBlank()) prefs.remove(SERVICE_PIN) else prefs[SERVICE_PIN] = pin
        }
    }

    suspend fun setRegion(region: String) {
        dataStore.edit { it[REGION] = region }
    }

    suspend fun setTemperatureUnit(unit: String) {
        dataStore.edit { it[TEMPERATURE_UNIT] = unit }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[BIOMETRIC_ENABLED] = enabled
            if (!enabled) prefs.remove(LAST_BIOMETRIC_UNLOCK_AT)
        }
    }

    suspend fun setLastBiometricUnlockAt(timestamp: Long) {
        dataStore.edit { prefs ->
            if (timestamp > 0L) prefs[LAST_BIOMETRIC_UNLOCK_AT] = timestamp else prefs.remove(LAST_BIOMETRIC_UNLOCK_AT)
        }
    }

    suspend fun setDefaultClimateTemp(temp: String) {
        dataStore.edit { it[DEFAULT_CLIMATE_TEMP] = temp }
    }


    suspend fun setValetModeEnabled(enabled: Boolean) {
        dataStore.edit { it[VALET_MODE_ENABLED] = enabled }
    }

    suspend fun setActiveDriverProfileId(profileId: String) {
        dataStore.edit { it[ACTIVE_DRIVER_PROFILE_ID] = profileId }
    }

    suspend fun setAppTheme(themeId: String) {
        dataStore.edit { it[APP_THEME] = themeId }
    }

    suspend fun setUiColorOverride(slot: UiColorSlot, hex: String?) {
        dataStore.edit { prefs ->
            val current = parseUiColorOverrides(prefs[UI_COLOR_OVERRIDES_JSON].orEmpty())
            val normalized = hex?.trim()?.takeIf { it.isNotBlank() }
            prefs[UI_COLOR_OVERRIDES_JSON] = encodeUiColorOverrides(current.withValue(slot, normalized))
        }
    }

    suspend fun resetUiColorOverride(slot: UiColorSlot) {
        setUiColorOverride(slot, null)
    }

    suspend fun resetUiColorOverrides() {
        dataStore.edit { it.remove(UI_COLOR_OVERRIDES_JSON) }
    }

    suspend fun setDriverProfilePhotoUri(profileId: String, photoUri: String?) {
        val key = when (profileId) {
            "primary" -> PROFILE_PRIMARY_PHOTO_URI
            "guest" -> PROFILE_GUEST_PHOTO_URI
            "valet" -> PROFILE_VALET_PHOTO_URI
            else -> return
        }
        dataStore.edit { prefs ->
            if (photoUri.isNullOrBlank()) prefs.remove(key) else prefs[key] = photoUri
        }
    }

    suspend fun setLastStatusRefresh(timestamp: Long) {
        dataStore.edit { it[LAST_STATUS_REFRESH] = timestamp }
    }

    suspend fun addCommandHistoryEntry(entry: CommandHistoryEntry) {
        dataStore.edit { prefs ->
            val current = parseCommandHistory(prefs[COMMAND_HISTORY_JSON].orEmpty())
            val updated = (listOf(entry) + current).take(30)
            prefs[COMMAND_HISTORY_JSON] = encodeCommandHistory(updated)
        }
    }

    suspend fun clearCommandHistory() {
        dataStore.edit { it.remove(COMMAND_HISTORY_JSON) }
    }

    suspend fun cacheWidgetSnapshot(snapshot: WidgetVehicleSnapshot) {
        dataStore.edit { prefs ->
            prefs[WIDGET_VEHICLE_NAME] = snapshot.vehicleName.ifBlank { "BlueBridge" }
            snapshot.doorsLocked?.let { prefs[WIDGET_DOORS_LOCKED] = it } ?: prefs.remove(WIDGET_DOORS_LOCKED)
            snapshot.batteryPercent?.let { prefs[WIDGET_BATTERY_PERCENT] = it.coerceIn(0, 100) } ?: prefs.remove(WIDGET_BATTERY_PERCENT)
            snapshot.rangeMiles?.let { prefs[WIDGET_RANGE_MILES] = it.coerceAtLeast(0) } ?: prefs.remove(WIDGET_RANGE_MILES)
            prefs[WIDGET_CHARGING_LABEL] = snapshot.chargingLabel.ifBlank { "Status unavailable" }
            prefs[WIDGET_MESSAGE] = snapshot.message.ifBlank { "Tap to open app" }
            prefs[WIDGET_DETAIL_ONE] = snapshot.detailOne.ifBlank { "Doors —" }
            prefs[WIDGET_DETAIL_TWO] = snapshot.detailTwo.ifBlank { "Climate —" }
            prefs[WIDGET_DETAIL_THREE] = snapshot.detailThree.ifBlank { "Tires —" }
            prefs[WIDGET_UPDATED_AT] = snapshot.updatedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
    }

    suspend fun setWidgetMessage(message: String) {
        dataStore.edit { prefs ->
            prefs[WIDGET_MESSAGE] = message
            prefs[WIDGET_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    private fun parseUiColorOverrides(raw: String): UiColorOverrides {
        if (raw.isBlank()) return UiColorOverrides.EMPTY
        return runCatching {
            val json = JSONObject(raw)
            UiColorSlot.entries.fold(UiColorOverrides.EMPTY) { overrides, slot ->
                val value = json.optString(slot.key).takeIf { it.isNotBlank() }
                overrides.withValue(slot, value)
            }
        }.getOrDefault(UiColorOverrides.EMPTY)
    }

    private fun encodeUiColorOverrides(overrides: UiColorOverrides): String {
        val json = JSONObject()
        UiColorSlot.entries.forEach { slot ->
            overrides.valueFor(slot)?.takeIf { it.isNotBlank() }?.let { json.put(slot.key, it) }
        }
        return json.toString()
    }

    private fun parseCommandHistory(raw: String): List<CommandHistoryEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CommandHistoryEntry(
                    timestampMillis = item.optLong("timestampMillis"),
                    title = item.optString("title"),
                    detail = item.optString("detail"),
                    successful = item.optBoolean("successful", true),
                    vehicleName = item.optString("vehicleName")
                )
            }.filter { it.title.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun encodeCommandHistory(entries: List<CommandHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("timestampMillis", entry.timestampMillis)
                    .put("title", entry.title)
                    .put("detail", entry.detail)
                    .put("successful", entry.successful)
                    .put("vehicleName", entry.vehicleName)
            )
        }
        return array.toString()
    }
}
