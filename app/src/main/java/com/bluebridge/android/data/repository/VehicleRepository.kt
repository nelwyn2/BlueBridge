package com.bluebridge.android.data.repository

import com.bluebridge.android.data.api.ApiClient
import com.bluebridge.android.data.api.AuApiClient
import com.bluebridge.android.data.api.CanadaApiClient
import com.bluebridge.android.data.api.EuApiClient
import com.bluebridge.android.data.api.EuIdentityApiClient
import com.bluebridge.android.data.api.Region
import com.bluebridge.android.data.models.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.ResponseBody
import java.security.SecureRandom
import kotlin.math.roundToInt

import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val code: Int? = null) : Result<Nothing>()
}

@Singleton
class VehicleRepository @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val secureCredentialsManager: SecureCredentialsManager
) {
    private var apiClient: ApiClient? = null
    private var apiClientRegion: Region? = null
    private var canadaApiClient: CanadaApiClient? = null
    private var canadaApiClientRegion: Region? = null
    private var euApiClient: EuApiClient? = null
    private var euApiClientRegion: Region? = null
    private var euApiClientDeviceId: String? = null
    private var euIdentityApiClient: EuIdentityApiClient? = null
    private var euIdentityApiClientRegion: Region? = null
    private var auApiClient: AuApiClient? = null
    private var auApiClientRegion: Region? = null
    private var auApiClientDeviceId: String? = null
    private val gson = Gson()

    private suspend fun currentRegion(): Region {
        val regionStr = preferencesManager.region.first()
        return runCatching { Region.valueOf(regionStr) }.getOrDefault(Region.US_HYUNDAI)
    }

    private suspend fun getApiService() = run {
        val region = currentRegion()
        if (region.isCanada) {
            throw IllegalStateException("Canada uses the TODS web API path. Use the Canada API service instead.")
        }
        if (apiClient == null || apiClientRegion != region) {
            apiClient = ApiClient(region.baseUrl)
            apiClientRegion = region
        }
        apiClient!!.apiService
    }

    private suspend fun getCanadaApiService() = run {
        val region = currentRegion()
        if (!region.isCanada) {
            throw IllegalStateException("Current region is not Canada")
        }
        if (canadaApiClient == null || canadaApiClientRegion != region) {
            canadaApiClient = CanadaApiClient(region.baseUrl, region.canadaHost)
            canadaApiClientRegion = region
        }
        canadaApiClient!!.apiService
    }

    private suspend fun getEuApiService() = run {
        val region = currentRegion()
        if (!region.isEurope) {
            throw IllegalStateException("Current region is not Europe")
        }
        val deviceId = ensureEuropeDeviceRegistered(region)
        if (euApiClient == null || euApiClientRegion != region || euApiClientDeviceId != deviceId) {
            euApiClient = EuApiClient(region, deviceId)
            euApiClientRegion = region
            euApiClientDeviceId = deviceId
        }
        euApiClient!!.apiService
    }

    private suspend fun getEuIdentityApiService() = run {
        val region = currentRegion()
        if (!region.isEurope) {
            throw IllegalStateException("Current region is not Europe")
        }
        if (euIdentityApiClient == null || euIdentityApiClientRegion != region) {
            euIdentityApiClient = EuIdentityApiClient(region)
            euIdentityApiClientRegion = region
        }
        euIdentityApiClient!!.apiService
    }

    private suspend fun getAuApiService() = run {
        val region = currentRegion()
        if (!region.isAustralia) {
            throw IllegalStateException("Current region is not Australia/New Zealand")
        }
        val deviceId = preferencesManager.getOrCreateAuDeviceId()
        if (auApiClient == null || auApiClientRegion != region || auApiClientDeviceId != deviceId) {
            auApiClient = AuApiClient(region, deviceId)
            auApiClientRegion = region
            auApiClientDeviceId = deviceId
        }
        auApiClient!!.apiService
    }

    private suspend fun isCanadaRegion(): Boolean = currentRegion().isCanada
    private suspend fun isEuropeRegion(): Boolean = currentRegion().isEurope
    private suspend fun isAustraliaRegion(): Boolean = currentRegion().isAustralia

    private suspend fun getToken(): String = ensureValidAccessToken()

    private suspend fun getUsername() = preferencesManager.username.first()
        ?: throw IllegalStateException("Not authenticated")

    private suspend fun getServicePin(required: Boolean = true): String {
        val pin = preferencesManager.servicePin.first().orEmpty().trim()
        if (required && pin.isBlank()) {
            throw IllegalStateException("Bluelink PIN is required. Add your 4-digit PIN in Settings > Account.")
        }
        return pin
    }

    private suspend fun bearerToken(): String = "Bearer ${getToken()}"

    private suspend fun ensureValidAccessToken(): String {
        val accessToken = preferencesManager.accessToken.first().orEmpty()
        if (accessToken.isBlank()) throw IllegalStateException("Not authenticated")

        val expiresAt = preferencesManager.tokenExpiresAt.first()
        if (expiresAt > System.currentTimeMillis() + 60_000L) return accessToken

        val refreshToken = preferencesManager.refreshToken.first().orEmpty()
        if (refreshToken.isBlank()) {
            preferencesManager.clearSession(requirePassword = true)
            throw IllegalStateException("Session expired. Please sign in again.")
        }

        return try {
            when {
                isEuropeRegion() -> refreshEuropeAccessToken(refreshToken)
                isAustraliaRegion() -> refreshAustraliaAccessToken(refreshToken)
                else -> refreshPasswordBasedSession()
            }
        } catch (e: Exception) {
            // Do not continue with a known-expired token. That leaves the app half-authenticated:
            // navigation still thinks the user is logged in, but vehicle loading fails and the
            // dashboard shows no selected vehicle. Force the auth state to reset instead.
            preferencesManager.clearSession(requirePassword = true)
            throw IllegalStateException(e.message ?: "Session refresh failed. Please sign in again.")
        }
    }

    private suspend fun refreshPasswordBasedSession(): String {
        val savedCredentials = secureCredentialsManager.getSavedCredentials()
        if (savedCredentials == null) {
            preferencesManager.clearSession(requirePassword = true)
            throw IllegalStateException("Session expired. Please sign in again.")
        }

        return when (val result = login(
            username = savedCredentials.username,
            password = savedCredentials.password,
            servicePin = savedCredentials.servicePin
        )) {
            is Result.Success -> preferencesManager.accessToken.first()
                ?: throw IllegalStateException("Sign-in succeeded but no access token was saved")
            is Result.Error -> {
                preferencesManager.clearSession(requirePassword = true)
                throw IllegalStateException(result.message)
            }
        }
    }

    private suspend fun refreshEuropeAccessToken(refreshToken: String): String {
        val region = currentRegion()
        val response = if (region == Region.EU_GENESIS || region.euIdentityBaseUrl.isBlank()) {
            getEuApiService().refreshAccessToken(
                authorization = region.euBasicAuthorization,
                refreshToken = refreshToken
            )
        } else {
            getEuIdentityApiService().refreshAccessToken(
                refreshToken = refreshToken,
                clientId = region.euServiceId,
                clientSecret = region.euClientSecret
            )
        }
        val json = response.body()
        if (!response.isSuccessful || euResponseFailed(json)) {
            throw IllegalStateException(euErrorMessage(json, "Europe token refresh failed (${response.code()})"))
        }
        val accessToken = json?.stringOrNull("access_token")
            ?: json?.objectOrNull("retValue")?.stringOrNull("access_token")
            ?: throw IllegalStateException("Europe token refresh did not return an access token")
        val nextRefreshToken = json?.stringOrNull("refresh_token")
            ?: json?.objectOrNull("retValue")?.stringOrNull("refresh_token")
            ?: refreshToken
        val expiresIn = json?.intOrNull("expires_in")
            ?: json?.objectOrNull("retValue")?.intOrNull("expires_in")
            ?: 1800
        val normalizedAccessToken = normalizeBearerlessToken(accessToken)
        preferencesManager.saveSession(
            accessToken = normalizedAccessToken,
            refreshToken = normalizeBearerlessToken(nextRefreshToken),
            username = getUsername(),
            expiresIn = expiresIn.coerceAtLeast(60) - 60,
            servicePin = getServicePin(required = false)
        )
        return normalizedAccessToken
    }

    private suspend fun refreshAustraliaAccessToken(refreshToken: String): String {
        val region = currentRegion()
        val stamp = AuApiClient(region, preferencesManager.getOrCreateAuDeviceId()).stamp()
        val response = getAuApiService().refreshAccessToken(
            authorization = region.auBasicAuthorization,
            stamp = stamp,
            refreshToken = refreshToken
        )
        val json = response.body()
        if (!response.isSuccessful || auResponseFailed(json)) {
            throw IllegalStateException(auErrorMessage(json, "Australia token refresh failed (${response.code()})"))
        }
        val accessToken = json?.stringOrNull("access_token")
            ?: json?.objectOrNull("retValue")?.stringOrNull("access_token")
            ?: throw IllegalStateException("Australia token refresh did not return an access token")
        val nextRefreshToken = json?.stringOrNull("refresh_token")
            ?: json?.objectOrNull("retValue")?.stringOrNull("refresh_token")
            ?: refreshToken
        val expiresIn = json?.intOrNull("expires_in")
            ?: json?.objectOrNull("retValue")?.intOrNull("expires_in")
            ?: 82800
        val normalizedAccessToken = normalizeBearerlessToken(accessToken)
        preferencesManager.saveSession(
            accessToken = normalizedAccessToken,
            refreshToken = normalizeBearerlessToken(nextRefreshToken),
            username = getUsername(),
            expiresIn = expiresIn.coerceAtLeast(60) - 60,
            servicePin = getServicePin(required = false)
        )
        return normalizedAccessToken
    }

    private fun validateCommandResponse(response: retrofit2.Response<CommandResponse>, actionName: String): Result<Unit> {
        val body = response.body()
        if (!response.isSuccessful) {
            return Result.Error("$actionName failed (${response.code()})", response.code())
        }
        if (body?.isInvalidPin == true) {
            val attempts = body.remainingAttemptCount?.takeIf { it.isNotBlank() }
            val suffix = attempts?.let { " $it attempt(s) remaining." }.orEmpty()
            return Result.Error("Incorrect Bluelink PIN.$suffix Update it in Settings > Account before trying again.")
        }
        body?.userMessage?.let { message ->
            if (message.contains("invalid", ignoreCase = true) ||
                message.contains("failed", ignoreCase = true) ||
                message.contains("error", ignoreCase = true)
            ) {
                return Result.Error(message)
            }
        }
        return Result.Success(Unit)
    }

    private fun validateEmptyCommandResponse(response: retrofit2.Response<ResponseBody>, actionName: String): Result<Unit> {
        if (!response.isSuccessful) {
            return Result.Error("$actionName failed (${response.code()})", response.code())
        }
        return Result.Success(Unit)
    }

    private fun validateRawCommandResponse(response: retrofit2.Response<ResponseBody>, actionName: String): Result<Unit> {
        val rawBody = try {
            response.body()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }

        val rawErrorBody = try {
            response.errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }

        if (!response.isSuccessful) {
            val serverMessage = extractCommandErrorMessage(rawErrorBody)
            return Result.Error(serverMessage ?: "$actionName failed (${response.code()})", response.code())
        }

        if (rawBody.isBlank()) {
            return Result.Success(Unit)
        }

        extractCommandErrorMessage(rawBody)?.let { message ->
            return Result.Error(message)
        }

        return Result.Success(Unit)
    }

    private fun extractCommandErrorMessage(rawJson: String): String? {
        if (rawJson.isBlank()) return null

        return try {
            val element = JsonParser.parseString(rawJson)
            if (!element.isJsonObject) return null
            val json = element.asJsonObject

            val pinValid = json.stringOrNull("isBlueLinkServicePinValid")
            if (pinValid?.equals("invalid", ignoreCase = true) == true) {
                val attempts = json.stringOrNull("remainingAttemptCount")?.takeIf { it.isNotBlank() }
                val suffix = attempts?.let { " $it attempt(s) remaining." }.orEmpty()
                return "Incorrect Bluelink PIN.$suffix Update it in Settings > Account before trying again."
            }

            val invalidAttempt = json.stringOrNull("invalidAttemptMessage")
            if (!invalidAttempt.isNullOrBlank()) {
                return invalidAttempt
            }

            val errorMessage = json.stringOrNull("errorMessage")
                ?: json.stringOrNull("message")
                ?: json.stringOrNull("error")

            errorMessage?.takeIf { message ->
                message.contains("invalid", ignoreCase = true) ||
                    message.contains("failed", ignoreCase = true) ||
                    message.contains("error", ignoreCase = true) ||
                    message.contains("incorrect", ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }


    // ─── Canada TODS helpers ────────────────────────────────────────────────────

    private fun JsonObject.objectOrNull(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (!value.isJsonNull && value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val value = get(name) ?: return null
        return if (!value.isJsonNull && value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asInt }.getOrNull()
            ?: runCatching { value.asString.toDouble().roundToInt() }.getOrNull()
    }

    private fun normalizeEuropeDistanceUnits(status: JsonObject): JsonObject {
        // EU/Canadian-style CCSP range payloads report drvDistance/dte unit=1 values in kilometers.
        // The app's shared model keeps unit=1 as miles for North American US payloads, so normalize
        // EU ranges to unit=2 before deserialization to avoid displaying ~1.609x inflated km values.
        fun normalizeRangeValue(range: JsonObject?) {
            if (range?.intOrNull("unit") == 1) {
                range.addProperty("unit", 2)
            }
        }

        fun JsonElement?.numericDoubleOrNull(): Double? {
            val element = this ?: return null
            if (element.isJsonNull) return null
            if (element.isJsonPrimitive) {
                return runCatching { element.asDouble }.getOrNull()
                    ?: runCatching { element.asString.filter { it.isDigit() || it == '.' || it == '-' }.toDouble() }.getOrNull()
            }
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                listOf("value", "odometer", "totalMileage", "mileage", "distance", "distanceToEmpty").forEach { key ->
                    obj.get(key).numericDoubleOrNull()?.let { return it }
                }
            }
            return null
        }

        fun normalizeOdometer() {
            val odometer = status.get("odometer") ?: return
            odometer.numericDoubleOrNull()?.let { status.addProperty("odometer", it.roundToInt()) }
        }

        fun normalizeAirTemperature() {
            val airTemp = status.objectOrNull("airTemp") ?: return
            val valueElement = airTemp.get("value") ?: return
            val numeric = valueElement.numericDoubleOrNull()
            val raw = runCatching { valueElement.asString }.getOrNull().orEmpty().trim()
            if (numeric != null) {
                airTemp.addProperty("value", numeric.roundToInt().toString())
            } else if (raw.isBlank() || raw.equals("00H", ignoreCase = true) || raw.equals("OFF", ignoreCase = true) || raw == "0") {
                status.remove("airTemp")
            }
        }

        fun numberFromNamedPath(obj: JsonObject?, names: Set<String>): Int? {
            obj ?: return null
            obj.entrySet().forEach { (key, value) ->
                val lower = key.lowercase()
                if (names.any { lower == it || lower.contains(it) }) {
                    value.numericDoubleOrNull()?.roundToInt()?.takeIf { it > 0 }?.let { return it }
                }
                if (value.isJsonObject) {
                    numberFromNamedPath(value.asJsonObject, names)?.let { return it }
                }
            }
            return null
        }

        fun normalizeTirePressure() {
            val tire = status.objectOrNull("tirePressure") ?: return
            val existingHasPressure = listOf(
                "tirePressureFrontLeft",
                "tirePressureFrontRight",
                "tirePressureRearLeft",
                "tirePressureRearRight"
            ).any { (tire.intOrNull(it) ?: 0) > 0 }
            if (existingHasPressure) return

            numberFromNamedPath(tire, setOf("tirepressurefrontleft", "frontleftpressure", "frontleftpsi", "frontleft", "flpressure", "flpsi", "fl"))
                ?.let { tire.addProperty("tirePressureFrontLeft", it) }
            numberFromNamedPath(tire, setOf("tirepressurefrontright", "frontrightpressure", "frontrightpsi", "frontright", "frpressure", "frpsi", "fr"))
                ?.let { tire.addProperty("tirePressureFrontRight", it) }
            numberFromNamedPath(tire, setOf("tirepressurerearleft", "rearleftpressure", "rearleftpsi", "rearleft", "rlpressure", "rlpsi", "rl"))
                ?.let { tire.addProperty("tirePressureRearLeft", it) }
            numberFromNamedPath(tire, setOf("tirepressurerearright", "rearrightpressure", "rearrightpsi", "rearright", "rrpressure", "rrpsi", "rr"))
                ?.let { tire.addProperty("tirePressureRearRight", it) }
        }

        normalizeRangeValue(status.objectOrNull("dte"))
        val distances = status.objectOrNull("evStatus")?.arrayOrNull("drvDistance")
        distances?.forEach { entry ->
            val rangeByFuel = entry.takeIfJsonObject()?.objectOrNull("rangeByFuel")
            normalizeRangeValue(rangeByFuel?.objectOrNull("totalAvailableRange"))
            normalizeRangeValue(rangeByFuel?.objectOrNull("evModeRange"))
            normalizeRangeValue(rangeByFuel?.objectOrNull("gasModeRange"))
        }
        normalizeOdometer()
        normalizeAirTemperature()
        normalizeTirePressure()
        return status
    }

    private fun canadaErrorMessage(json: JsonObject?, fallback: String): String {
        val error = json?.objectOrNull("error")
        val errorDesc = error?.stringOrNull("errorDesc")
            ?: error?.stringOrNull("errorMessage")
            ?: error?.stringOrNull("message")
        val errorCode = error?.stringOrNull("errorCode")
        return when {
            !errorDesc.isNullOrBlank() && !errorCode.isNullOrBlank() -> "$fallback ($errorCode): $errorDesc"
            !errorDesc.isNullOrBlank() -> errorDesc
            !errorCode.isNullOrBlank() -> "$fallback ($errorCode)"
            else -> fallback
        }
    }

    private fun canadaResponseFailed(json: JsonObject?): Boolean {
        val responseCode = json?.objectOrNull("responseHeader")?.intOrNull("responseCode")
        return responseCode != null && responseCode != 0
    }

    private fun canadaOtpRequired(json: JsonObject?): Boolean =
        json?.objectOrNull("error")?.stringOrNull("errorCode") == "7110"

    private suspend fun loginCanada(username: String, password: String, servicePin: String): Result<Unit> {
        return try {
            val deviceId = preferencesManager.getOrCreateCanadaDeviceId()
            val response = getCanadaApiService().login(
                deviceId = deviceId,
                body = mapOf("loginId" to username, "password" to password)
            )
            val json = response.body()
            if (!response.isSuccessful) {
                return Result.Error(canadaErrorMessage(json, "Canada login failed (${response.code()})"), response.code())
            }
            if (canadaOtpRequired(json)) {
                return Result.Error(
                    "Canada login requires OTP/MFA for this device. Open the official Hyundai/Kia Canada app or web portal once, then try BlueBridge again. OTP entry is scaffolded in the API layer but not exposed in the login screen yet."
                )
            }
            if (canadaResponseFailed(json)) {
                return Result.Error(canadaErrorMessage(json, "Canada login failed"))
            }
            val token = json?.objectOrNull("result")?.objectOrNull("token")
                ?: return Result.Error("Canada login did not return a token")
            preferencesManager.saveSession(
                accessToken = token.stringOrNull("accessToken").orEmpty(),
                refreshToken = token.stringOrNull("refreshToken").orEmpty(),
                username = username,
                expiresIn = (token.intOrNull("expireIn") ?: 1800).coerceAtLeast(60) - 60,
                servicePin = servicePin
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Canada login network error")
        }
    }

    private suspend fun getCanadaVehicles(): Result<List<Vehicle>> {
        return try {
            val token = getToken()
            val deviceId = preferencesManager.getOrCreateCanadaDeviceId()
            val response = getCanadaApiService().getVehicles(token, deviceId)
            val json = response.body()
            if (!response.isSuccessful || canadaResponseFailed(json)) {
                return Result.Error(canadaErrorMessage(json, "Failed to fetch Canadian vehicles (${response.code()})"), response.code())
            }
            val region = currentRegion()
            val brand = when (region) {
                Region.CA_KIA -> "K"
                Region.CA_GENESIS -> "G"
                else -> "H"
            }
            val vehicles = json?.objectOrNull("result")?.arrayOrNull("vehicles")
                ?.mapNotNull { it.takeIfJsonObject() }
                ?.map { entry ->
                    val vehicleId = entry.stringOrNull("vehicleId").orEmpty()
                    val fuelCode = entry.stringOrNull("fuelKindCode").orEmpty()
                    val modelName = entry.stringOrNull("modelName").orEmpty()
                    Vehicle(
                        vin = entry.stringOrNull("vin").orEmpty(),
                        vehicleIdentifier = vehicleId,
                        enrollmentId = vehicleId,
                        regId = vehicleId,
                        generation = "3",
                        nickname = entry.stringOrNull("nickName").orEmpty(),
                        modelCode = if (fuelCode == "E") "$modelName EV" else modelName,
                        modelName = modelName,
                        modelYear = entry.stringOrNull("modelYear").orEmpty(),
                        brandIndicator = brand,
                        odometer = entry.intOrNull("odometer") ?: 0
                    )
                }.orEmpty()
            Result.Success(vehicles)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Canada vehicle list network error")
        }
    }

    private fun JsonElement.takeIfJsonObject(): JsonObject? =
        if (!isJsonNull && isJsonObject) asJsonObject else null

    private suspend fun getCanadaVehicleStatus(vin: String, forceRefresh: Boolean, registrationId: String): Result<VehicleStatusData> {
        return try {
            val token = getToken()
            val deviceId = preferencesManager.getOrCreateCanadaDeviceId()
            val vehicleId = registrationId.ifBlank { vin }
            val api = getCanadaApiService()
            val response = if (forceRefresh) {
                api.getLiveVehicleStatus(token, vehicleId, deviceId)
            } else {
                api.getCachedVehicleStatus(token, vehicleId, deviceId)
            }
            val json = response.body()
            if (!response.isSuccessful || canadaResponseFailed(json)) {
                return Result.Error(canadaErrorMessage(json, "Canadian status fetch failed (${response.code()})"), response.code())
            }
            val status = json?.objectOrNull("result")?.objectOrNull("status")
                ?: return Result.Error("Could not parse Canadian vehicle status")
            val data = gson.fromJson(normalizeEuropeDistanceUnits(status), VehicleStatusData::class.java)
            preferencesManager.setLastStatusRefresh(System.currentTimeMillis())
            Result.Success(data)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Canada status network error")
        }
    }

    private suspend fun getCanadaPinAuth(accessToken: String, vehicleId: String, pin: String, deviceId: String): String {
        val response = getCanadaApiService().verifyPin(accessToken, vehicleId, deviceId, mapOf("pin" to pin))
        val json = response.body()
        if (!response.isSuccessful || canadaResponseFailed(json)) {
            throw IllegalStateException(canadaErrorMessage(json, "Canadian PIN verification failed (${response.code()})"))
        }
        return json?.objectOrNull("result")?.stringOrNull("pAuth")
            ?: throw IllegalStateException("Canadian PIN verification did not return pAuth")
    }

    private suspend fun runCanadaPinCommand(
        vin: String,
        registrationId: String,
        actionName: String,
        call: suspend (accessToken: String, vehicleId: String, pAuth: String, deviceId: String, pin: String) -> retrofit2.Response<ResponseBody>
    ): Result<Unit> {
        return try {
            val accessToken = getToken()
            val pin = getServicePin()
            val deviceId = preferencesManager.getOrCreateCanadaDeviceId()
            val vehicleId = registrationId.ifBlank { vin }
            val pAuth = getCanadaPinAuth(accessToken, vehicleId, pin, deviceId)
            validateRawCommandResponse(call(accessToken, vehicleId, pAuth, deviceId, pin), actionName)
        } catch (e: Exception) {
            Result.Error(e.message ?: "$actionName failed")
        }
    }

    private fun canadaClimateTempHexFromF(tempF: String): String {
        val fahrenheit = tempF.toDoubleOrNull() ?: 72.0
        val celsius = ((fahrenheit - 32.0) * 5.0 / 9.0).coerceIn(14.0, 31.5)
        val halfStep = ((celsius - 14.0) / 0.5).roundToInt().coerceIn(0, 35)
        return halfStep.toString(16).padStart(2, '0').uppercase() + "H"
    }

    private fun canadaClimatePayload(pin: String, tempF: String, defrost: Boolean, durationMinutes: Int, isEv: Boolean): JsonObject {
        val airTemp = JsonObject().apply {
            addProperty("value", canadaClimateTempHexFromF(tempF))
            addProperty("unit", 0)
            addProperty("hvacTempType", if (isEv) 1 else 0)
        }
        val seatCommands = JsonObject().apply {
            addProperty("drvSeatOptCmd", 0)
            addProperty("astSeatOptCmd", 0)
            addProperty("rlSeatOptCmd", 0)
            addProperty("rrSeatOptCmd", 0)
        }
        val settings = JsonObject().apply {
            addProperty("airCtrl", 1)
            addProperty("defrost", defrost)
            addProperty("heating1", 0)
            addProperty("igniOnDuration", durationMinutes)
            add("airTemp", airTemp)
            add("seatHeaterVentCMD", seatCommands)
            if (!isEv) addProperty("ims", 0)
        }
        return JsonObject().apply {
            addProperty("pin", pin)
            if (isEv) add("hvacInfo", settings) else add("setting", settings)
        }
    }


    // ─── Europe CCSP helpers ───────────────────────────────────────────────────

    private fun euErrorMessage(json: JsonObject?, fallback: String): String {
        val retMsg = json?.stringOrNull("retMsg")
        val resCode = json?.stringOrNull("resCode")
        return when {
            !retMsg.isNullOrBlank() && !resCode.isNullOrBlank() -> "$fallback ($resCode): $retMsg"
            !retMsg.isNullOrBlank() -> retMsg
            !resCode.isNullOrBlank() -> "$fallback ($resCode)"
            else -> fallback
        }
    }

    private fun euResponseFailed(json: JsonObject?): Boolean {
        val retCode = json?.stringOrNull("retCode")
        val resCode = json?.stringOrNull("resCode")
        return retCode.equals("F", ignoreCase = true) || (!resCode.isNullOrBlank() && resCode != "0000")
    }

    private fun isLikelyAuthFailure(code: Int, json: JsonObject?): Boolean {
        if (code == 401 || code == 403) return true
        val message = listOfNotNull(
            json?.stringOrNull("retMsg"),
            json?.stringOrNull("message"),
            json?.stringOrNull("error"),
            json?.stringOrNull("error_description"),
            json?.objectOrNull("error")?.stringOrNull("message"),
            json?.objectOrNull("error")?.stringOrNull("error_description"),
            json?.objectOrNull("error")?.stringOrNull("errorCode"),
            json?.stringOrNull("resCode")
        ).joinToString(" ").lowercase()
        return message.contains("unauthorized") ||
            message.contains("forbidden") ||
            message.contains("expired") ||
            message.contains("invalid token") ||
            message.contains("invalid_token") ||
            message.contains("invalid access") ||
            message.contains("authentication") ||
            message.contains("auth")
    }

    private fun euRandomHex(length: Int): String {
        val random = SecureRandom()
        val alphabet = "0123456789abcdef"
        return buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }

    private fun looksLikeLegacyLocalEuDeviceId(deviceId: String): Boolean {
        // v1.13 EU test builds generated a local UUID before registering with CCSP.
        // CCSP accepts login tokens independently, but vehicle/status/control calls can fail
        // with a locally invented device id. Force a one-time re-registration for that shape.
        return Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(deviceId.trim())
    }

    private suspend fun ensureEuropeDeviceRegistered(region: Region): String {
        val stored = preferencesManager.getStoredEuDeviceId(region.name).orEmpty().trim()
        if (stored.isNotBlank() && !looksLikeLegacyLocalEuDeviceId(stored)) {
            return stored
        }

        val registrationClient = EuApiClient(region, "").apiService
        val response = registrationClient.registerNotifications(
            JsonObject().apply {
                addProperty("pushRegId", euRandomHex(64))
                addProperty("pushType", region.euPushType.ifBlank { "GCM" })
                addProperty("uuid", java.util.UUID.randomUUID().toString())
            }
        )
        val json = response.body()
        if (!response.isSuccessful || euResponseFailed(json)) {
            throw IllegalStateException(euErrorMessage(json, "European device registration failed (${response.code()})"))
        }

        val deviceId = json?.objectOrNull("resMsg")?.stringOrNull("deviceId")
            ?: json?.objectOrNull("retValue")?.stringOrNull("deviceId")
            ?: json?.stringOrNull("deviceId")
            ?: throw IllegalStateException("European device registration did not return a deviceId")

        preferencesManager.setEuDeviceId(deviceId, region.name)
        euApiClient = null
        euApiClientRegion = null
        euApiClientDeviceId = null
        return deviceId
    }

    private suspend fun registeredEuropeDeviceId(): String = ensureEuropeDeviceRegistered(currentRegion())

    private fun normalizeBearerlessToken(token: String): String = token
        .trim()
        .removePrefix("Bearer ")
        .removePrefix("bearer ")
        .trim()

    private suspend fun loginEurope(username: String, refreshToken: String, servicePin: String): Result<Unit> {
        val token = refreshToken.trim()
        if (!Regex("^[A-Z0-9]{48}$").matches(token)) {
            return Result.Error(
                "Europe login requires a 48-character Hyundai/Kia Connect refresh token, not the normal account password. Generate the EU refresh token with a browser/token helper, paste it into the password field, then sign in again."
            )
        }

        return try {
            val region = currentRegion()
            ensureEuropeDeviceRegistered(region)

            val response = if (region == Region.EU_GENESIS || region.euIdentityBaseUrl.isBlank()) {
                getEuApiService().refreshAccessToken(
                    authorization = region.euBasicAuthorization,
                    refreshToken = token
                )
            } else {
                getEuIdentityApiService().refreshAccessToken(
                    refreshToken = token,
                    clientId = region.euServiceId,
                    clientSecret = region.euClientSecret
                )
            }

            val json = response.body()
            if (!response.isSuccessful || euResponseFailed(json)) {
                return Result.Error(euErrorMessage(json, "Europe refresh-token login failed (${response.code()})"), response.code())
            }

            val accessToken = json?.stringOrNull("access_token")
                ?: json?.objectOrNull("retValue")?.stringOrNull("access_token")
                ?: return Result.Error("Europe login did not return an access token")
            val nextRefreshToken = json?.stringOrNull("refresh_token")
                ?: json?.objectOrNull("retValue")?.stringOrNull("refresh_token")
                ?: token
            val expiresIn = json?.intOrNull("expires_in")
                ?: json?.objectOrNull("retValue")?.intOrNull("expires_in")
                ?: 1800

            preferencesManager.saveSession(
                accessToken = normalizeBearerlessToken(accessToken),
                refreshToken = normalizeBearerlessToken(nextRefreshToken),
                username = username.ifBlank { "EU user" },
                expiresIn = expiresIn.coerceAtLeast(60) - 60,
                servicePin = servicePin
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Europe login network error")
        }
    }

    private suspend fun getEuropeVehicles(): Result<List<Vehicle>> {
        return try {
            var response = getEuApiService().getVehicles(bearerToken())
            var json = response.body()
            if (!response.isSuccessful || euResponseFailed(json)) {
                if (isLikelyAuthFailure(response.code(), json)) {
                    val refreshToken = preferencesManager.refreshToken.first().orEmpty()
                    if (refreshToken.isNotBlank()) {
                        val refreshedToken = runCatching { refreshEuropeAccessToken(refreshToken) }.getOrNull()
                        if (!refreshedToken.isNullOrBlank()) {
                            response = getEuApiService().getVehicles("Bearer $refreshedToken")
                            json = response.body()
                        }
                    }
                }
                if (!response.isSuccessful || euResponseFailed(json)) {
                    if (isLikelyAuthFailure(response.code(), json)) {
                        preferencesManager.clearSession(requirePassword = true)
                        return Result.Error("Session expired. Please sign in again.", response.code())
                    }
                    return Result.Error(euErrorMessage(json, "Failed to fetch European vehicles (${response.code()})"), response.code())
                }
            }
            val region = currentRegion()
            val brand = when (region) {
                Region.EU_KIA -> "K"
                Region.EU_GENESIS -> "G"
                else -> "H"
            }
            val source = json?.get("resMsg") ?: json?.get("retValue") ?: json
            val array = when {
                source == null || source.isJsonNull -> null
                source.isJsonArray -> source.asJsonArray
                source.isJsonObject -> source.asJsonObject.arrayOrNull("vehicles")
                    ?: source.asJsonObject.arrayOrNull("vehicleList")
                    ?: source.asJsonObject.arrayOrNull("enrolledVehicleDetails")
                else -> null
            }
            val vehicles = array?.mapNotNull { it.takeIfJsonObject() }?.map { entry ->
                val vehicleObj = entry.objectOrNull("vehicle") ?: entry.objectOrNull("vehicleDetails") ?: entry
                val vehicleId = vehicleObj.stringOrNull("id")
                    ?: vehicleObj.stringOrNull("vehicleId")
                    ?: vehicleObj.stringOrNull("vehicleIdentifier")
                    ?: vehicleObj.stringOrNull("regid")
                    ?: vehicleObj.stringOrNull("enrollmentId")
                    ?: vehicleObj.stringOrNull("vin")
                    ?: ""
                val vin = vehicleObj.stringOrNull("vin").orEmpty()
                val modelName = vehicleObj.stringOrNull("name")
                    ?: vehicleObj.stringOrNull("modelName")
                    ?: vehicleObj.stringOrNull("series")
                    ?: vehicleObj.stringOrNull("model")
                    ?: "Vehicle"
                Vehicle(
                    vin = vin,
                    vehicleIdentifier = vehicleId,
                    enrollmentId = vehicleId,
                    regId = vehicleId,
                    generation = if ((vehicleObj.intOrNull("ccuCCS2ProtocolSupport") ?: 0) != 0) "4" else "3",
                    nickname = vehicleObj.stringOrNull("nickname")
                        ?: vehicleObj.stringOrNull("nickName")
                        ?: vehicleObj.stringOrNull("vehicleName")
                        ?: "",
                    modelCode = vehicleObj.stringOrNull("modelCode") ?: modelName,
                    modelName = modelName,
                    modelYear = vehicleObj.stringOrNull("modelYear") ?: vehicleObj.stringOrNull("year") ?: "",
                    brandIndicator = brand,
                    odometer = vehicleObj.intOrNull("odometer") ?: 0
                )
            }.orEmpty()
            Result.Success(vehicles)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Europe vehicle list network error")
        }
    }

    private suspend fun resolveEuropeVehicleId(vin: String, registrationId: String): String {
        if (registrationId.isNotBlank()) return registrationId
        return when (val result = getEuropeVehicles()) {
            is Result.Success -> result.data.firstOrNull { it.vin == vin }?.regId ?: vin
            is Result.Error -> vin
        }
    }

    private suspend fun getEuropeVehicleStatus(vin: String, forceRefresh: Boolean, registrationId: String, generation: String): Result<VehicleStatusData> {
        return try {
            val vehicleId = resolveEuropeVehicleId(vin, registrationId)
            val api = getEuApiService()
            val ccs2First = generation == "4"
            val response = if (ccs2First) {
                if (forceRefresh) api.getLiveCcs2VehicleStatus(bearerToken(), vehicleId) else api.getCachedCcs2VehicleStatus(bearerToken(), vehicleId)
            } else {
                if (forceRefresh) api.getLiveVehicleStatus(bearerToken(), vehicleId) else api.getCachedVehicleStatus(bearerToken(), vehicleId)
            }
            val fallbackResponse = if (!response.isSuccessful && ccs2First) {
                if (forceRefresh) api.getLiveVehicleStatus(bearerToken(), vehicleId) else api.getCachedVehicleStatus(bearerToken(), vehicleId)
            } else response
            val json = fallbackResponse.body()
            if (!fallbackResponse.isSuccessful || euResponseFailed(json)) {
                if (isLikelyAuthFailure(fallbackResponse.code(), json)) {
                    preferencesManager.clearSession(requirePassword = true)
                    return Result.Error("Session expired. Please sign in again.", fallbackResponse.code())
                }
                return Result.Error(euErrorMessage(json, "European status fetch failed (${fallbackResponse.code()})"), fallbackResponse.code())
            }
            val resMsg = json?.get("resMsg")
            val status = when {
                resMsg == null || resMsg.isJsonNull -> json
                resMsg.isJsonObject && resMsg.asJsonObject.objectOrNull("vehicleStatusInfo") != null -> resMsg.asJsonObject.objectOrNull("vehicleStatusInfo")
                resMsg.isJsonObject && resMsg.asJsonObject.objectOrNull("state")?.objectOrNull("Vehicle") != null -> resMsg.asJsonObject.objectOrNull("state")?.objectOrNull("Vehicle")
                resMsg.isJsonObject -> resMsg.asJsonObject
                else -> json
            } ?: return Result.Error("Could not parse European vehicle status")
            val data = gson.fromJson(normalizeEuropeDistanceUnits(status), VehicleStatusData::class.java)
            preferencesManager.setLastStatusRefresh(System.currentTimeMillis())
            Result.Success(data)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Europe status network error")
        }
    }

    private suspend fun getEuropeControlAuthorization(vehicleId: String): String {
        val pin = getServicePin(required = false)
        if (pin.isBlank()) return bearerToken()

        val region = currentRegion()
        val deviceId = ensureEuropeDeviceRegistered(region)
        val response = getEuApiService().verifyPin(
            authorization = bearerToken(),
            body = mapOf("deviceId" to deviceId, "pin" to pin, "vehicleId" to vehicleId)
        )
        val json = response.body()
        if (!response.isSuccessful || euResponseFailed(json)) {
            throw IllegalStateException(euErrorMessage(json, "European PIN verification failed (${response.code()})"))
        }
        val controlToken = json?.stringOrNull("controlToken")
            ?: json?.objectOrNull("resMsg")?.stringOrNull("controlToken")
            ?: json?.objectOrNull("retValue")?.stringOrNull("controlToken")
            ?: return bearerToken()
        return "Bearer $controlToken"
    }

    private suspend fun runEuropeCommand(
        vin: String,
        registrationId: String,
        actionName: String,
        call: suspend (authorization: String, vehicleId: String) -> retrofit2.Response<ResponseBody>
    ): Result<Unit> {
        return try {
            val vehicleId = resolveEuropeVehicleId(vin, registrationId)
            val authorization = getEuropeControlAuthorization(vehicleId)
            validateRawCommandResponse(call(authorization, vehicleId), actionName)
        } catch (e: Exception) {
            Result.Error(e.message ?: "$actionName failed")
        }
    }

    private fun europeEnginePayload(action: String, deviceId: String, tempF: String = "72", defrost: Boolean = false, durationMinutes: Int = 10): JsonObject {
        val celsius = (((tempF.toDoubleOrNull() ?: 72.0) - 32.0) * 5.0 / 9.0).coerceIn(14.0, 29.5)
        return JsonObject().apply {
            addProperty("action", action)
            addProperty("deviceId", deviceId)
            addProperty("igniOnDuration", durationMinutes)
            add("airTemp", JsonObject().apply {
                addProperty("value", celsius)
                addProperty("unit", 0)
            })
            addProperty("defrost", defrost)
            addProperty("airCtrl", action == "start")
        }
    }


    // ─── Australia / New Zealand CCSP helpers ─────────────────────────────────

    private fun auErrorMessage(json: JsonObject?, fallback: String): String {
        val retMsg = json?.stringOrNull("retMsg")
            ?: json?.stringOrNull("msg")
            ?: json?.objectOrNull("error")?.stringOrNull("message")
            ?: json?.objectOrNull("error")?.stringOrNull("error_description")
        val resCode = json?.stringOrNull("resCode") ?: json?.stringOrNull("code")
        return when {
            !retMsg.isNullOrBlank() && !resCode.isNullOrBlank() -> "$fallback ($resCode): $retMsg"
            !retMsg.isNullOrBlank() -> retMsg
            !resCode.isNullOrBlank() -> "$fallback ($resCode)"
            else -> fallback
        }
    }

    private fun auResponseFailed(json: JsonObject?): Boolean {
        val retCode = json?.stringOrNull("retCode")
        val resCode = json?.stringOrNull("resCode")
        return retCode.equals("F", ignoreCase = true) || (!resCode.isNullOrBlank() && resCode != "0000")
    }

    private suspend fun auAuthorization(): String = "Bearer ${getToken()}"

    private fun extractQueryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size == 2) pieces[0] to java.net.URLDecoder.decode(pieces[1], "UTF-8") else null
            }
            .firstOrNull { it.first == name }
            ?.second
    }

    private suspend fun registerAuDeviceIfPossible(): String {
        val api = getAuApiService()
        val stamp = AuApiClient(currentRegion(), preferencesManager.getOrCreateAuDeviceId()).stamp()
        val body = JsonObject().apply {
            addProperty("pushRegId", com.bluebridge.android.data.api.newAuPushRegistrationId())
            addProperty("pushType", "GCM")
            addProperty("uuid", com.bluebridge.android.data.api.newAuUuid())
        }
        val response = api.registerNotifications(stamp, body)
        val json = response.body()
        val deviceId = json?.objectOrNull("resMsg")?.stringOrNull("deviceId")
            ?: json?.objectOrNull("retValue")?.stringOrNull("deviceId")
        if (response.isSuccessful && !deviceId.isNullOrBlank()) {
            preferencesManager.setAuDeviceId(deviceId)
            auApiClient = null
            auApiClientDeviceId = null
            return deviceId
        }
        return preferencesManager.getOrCreateAuDeviceId()
    }

    private suspend fun loginAustralia(username: String, password: String, servicePin: String): Result<Unit> {
        return try {
            val region = currentRegion()
            getAuApiService().getAuthorizationCookies()
            registerAuDeviceIfPossible()
            val signInResponse = getAuApiService().signIn(JsonObject().apply {
                addProperty("email", username)
                addProperty("password", password)
            })
            val signInJson = signInResponse.body()
            if (!signInResponse.isSuccessful || auResponseFailed(signInJson)) {
                return Result.Error(auErrorMessage(signInJson, "Australia login failed (${signInResponse.code()})"), signInResponse.code())
            }
            val redirectUrl = signInJson?.stringOrNull("redirectUrl")
                ?: signInJson?.objectOrNull("resMsg")?.stringOrNull("redirectUrl")
                ?: return Result.Error("Australia login did not return an authorization redirect")
            val authorizationCode = extractQueryParam(redirectUrl, "code")
                ?: return Result.Error("Australia login redirect did not contain an authorization code")
            val stamp = AuApiClient(region, preferencesManager.getOrCreateAuDeviceId()).stamp()
            val tokenResponse = getAuApiService().exchangeAuthorizationCode(
                authorization = region.auBasicAuthorization,
                stamp = stamp,
                redirectUri = "https://${region.auHost}/api/v1/user/oauth2/redirect",
                code = authorizationCode
            )
            val tokenJson = tokenResponse.body()
            if (!tokenResponse.isSuccessful || auResponseFailed(tokenJson)) {
                return Result.Error(auErrorMessage(tokenJson, "Australia token exchange failed (${tokenResponse.code()})"), tokenResponse.code())
            }
            val accessToken = tokenJson?.stringOrNull("access_token")
                ?: tokenJson?.objectOrNull("retValue")?.stringOrNull("access_token")
                ?: return Result.Error("Australia login did not return an access token")
            val refreshToken = tokenJson?.stringOrNull("refresh_token")
                ?: tokenJson?.objectOrNull("retValue")?.stringOrNull("refresh_token")
                ?: ""
            val expiresIn = tokenJson?.intOrNull("expires_in")
                ?: tokenJson?.objectOrNull("retValue")?.intOrNull("expires_in")
                ?: 82800
            preferencesManager.saveSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                username = username,
                expiresIn = expiresIn.coerceAtLeast(60) - 60,
                servicePin = servicePin
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Australia login network error")
        }
    }

    private suspend fun getAustraliaVehicles(): Result<List<Vehicle>> {
        return try {
            val response = getAuApiService().getVehicles(auAuthorization())
            val json = response.body()
            if (!response.isSuccessful || auResponseFailed(json)) {
                return Result.Error(auErrorMessage(json, "Failed to fetch Australia/NZ vehicles (${response.code()})"), response.code())
            }
            val region = currentRegion()
            val brand = when (region) {
                Region.AU_KIA, Region.NZ_KIA -> "K"
                else -> "H"
            }
            val source = json?.get("resMsg") ?: json?.get("retValue") ?: json
            val array = when {
                source == null || source.isJsonNull -> null
                source.isJsonArray -> source.asJsonArray
                source.isJsonObject -> source.asJsonObject.arrayOrNull("vehicles")
                    ?: source.asJsonObject.arrayOrNull("vehicleList")
                    ?: source.asJsonObject.arrayOrNull("enrolledVehicleDetails")
                else -> null
            }
            val vehicles = array?.mapNotNull { it.takeIfJsonObject() }?.map { entry ->
                val vehicleObj = entry.objectOrNull("vehicle") ?: entry.objectOrNull("vehicleDetails") ?: entry
                val vehicleId = vehicleObj.stringOrNull("id")
                    ?: vehicleObj.stringOrNull("vehicleId")
                    ?: vehicleObj.stringOrNull("vehicleIdentifier")
                    ?: vehicleObj.stringOrNull("regid")
                    ?: vehicleObj.stringOrNull("enrollmentId")
                    ?: vehicleObj.stringOrNull("vin")
                    ?: ""
                val vin = vehicleObj.stringOrNull("vin").orEmpty()
                val modelName = vehicleObj.stringOrNull("name")
                    ?: vehicleObj.stringOrNull("modelName")
                    ?: vehicleObj.stringOrNull("series")
                    ?: vehicleObj.stringOrNull("model")
                    ?: "Vehicle"
                Vehicle(
                    vin = vin,
                    vehicleIdentifier = vehicleId,
                    enrollmentId = vehicleId,
                    regId = vehicleId,
                    generation = if ((vehicleObj.intOrNull("ccuCCS2ProtocolSupport") ?: 0) != 0) "4" else "3",
                    nickname = vehicleObj.stringOrNull("nickname")
                        ?: vehicleObj.stringOrNull("nickName")
                        ?: vehicleObj.stringOrNull("vehicleName")
                        ?: "",
                    modelCode = vehicleObj.stringOrNull("modelCode") ?: modelName,
                    modelName = modelName,
                    modelYear = vehicleObj.stringOrNull("modelYear") ?: vehicleObj.stringOrNull("year") ?: "",
                    brandIndicator = brand,
                    odometer = vehicleObj.intOrNull("odometer") ?: 0
                )
            }.orEmpty()
            Result.Success(vehicles)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Australia/NZ vehicle list network error")
        }
    }

    private suspend fun resolveAustraliaVehicleId(vin: String, registrationId: String): String {
        if (registrationId.isNotBlank()) return registrationId
        return when (val result = getAustraliaVehicles()) {
            is Result.Success -> result.data.firstOrNull { it.vin == vin }?.regId ?: vin
            is Result.Error -> vin
        }
    }

    private suspend fun getAustraliaVehicleStatus(vin: String, forceRefresh: Boolean, registrationId: String, generation: String): Result<VehicleStatusData> {
        return try {
            val vehicleId = resolveAustraliaVehicleId(vin, registrationId)
            val api = getAuApiService()
            val ccs2First = generation == "4"
            val response = if (ccs2First) {
                if (forceRefresh) api.getLiveCcs2VehicleStatus(auAuthorization(), vehicleId) else api.getCachedCcs2VehicleStatus(auAuthorization(), vehicleId)
            } else {
                if (forceRefresh) api.getLiveVehicleStatus(auAuthorization(), vehicleId) else api.getCachedVehicleStatus(auAuthorization(), vehicleId)
            }
            val fallbackResponse = if (!response.isSuccessful && ccs2First) {
                if (forceRefresh) api.getLiveVehicleStatus(auAuthorization(), vehicleId) else api.getCachedVehicleStatus(auAuthorization(), vehicleId)
            } else response
            val json = fallbackResponse.body()
            if (!fallbackResponse.isSuccessful || auResponseFailed(json)) {
                return Result.Error(auErrorMessage(json, "Australia/NZ status fetch failed (${fallbackResponse.code()})"), fallbackResponse.code())
            }
            val resMsg = json?.get("resMsg")
            val status = when {
                resMsg == null || resMsg.isJsonNull -> json
                resMsg.isJsonObject && resMsg.asJsonObject.objectOrNull("vehicleStatusInfo") != null -> resMsg.asJsonObject.objectOrNull("vehicleStatusInfo")
                resMsg.isJsonObject && resMsg.asJsonObject.objectOrNull("state")?.objectOrNull("Vehicle") != null -> resMsg.asJsonObject.objectOrNull("state")?.objectOrNull("Vehicle")
                resMsg.isJsonObject -> resMsg.asJsonObject
                else -> json
            } ?: return Result.Error("Could not parse Australia/NZ vehicle status")
            val data = gson.fromJson(status, VehicleStatusData::class.java)
            preferencesManager.setLastStatusRefresh(System.currentTimeMillis())
            Result.Success(data)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Australia/NZ status network error")
        }
    }

    private suspend fun runAustraliaCommand(
        vin: String,
        registrationId: String,
        actionName: String,
        call: suspend (authorization: String, vehicleId: String) -> retrofit2.Response<ResponseBody>
    ): Result<Unit> {
        return try {
            val vehicleId = resolveAustraliaVehicleId(vin, registrationId)
            validateRawCommandResponse(call(auAuthorization(), vehicleId), actionName)
        } catch (e: Exception) {
            Result.Error(e.message ?: "$actionName failed")
        }
    }

    private suspend fun australiaDeviceId(): String = preferencesManager.getOrCreateAuDeviceId()

    private fun australiaEnginePayload(action: String, tempF: String = "72", defrost: Boolean = false, durationMinutes: Int = 10): JsonObject {
        val celsius = (((tempF.toDoubleOrNull() ?: 72.0) - 32.0) * 5.0 / 9.0).coerceIn(17.0, 26.5)
        return JsonObject().apply {
            addProperty("action", action)
            addProperty("deviceId", auApiClientDeviceId.orEmpty())
            addProperty("igniOnDuration", durationMinutes)
            add("airTemp", JsonObject().apply {
                addProperty("value", celsius)
                addProperty("unit", 0)
            })
            addProperty("defrost", defrost)
            addProperty("airCtrl", action == "start")
        }
    }

    // ─── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String, servicePin: String = ""): Result<Unit> {
        if (isCanadaRegion()) return loginCanada(username, password, servicePin)
        if (isEuropeRegion()) return loginEurope(username, password, servicePin)
        if (isAustraliaRegion()) return loginAustralia(username, password, servicePin)
        return try {
            val response = getApiService().getToken(
                LoginRequest(username = username, password = password)
            )
            if (response.isSuccessful) {
                val token = response.body() ?: return Result.Error("Empty response")
                preferencesManager.saveSession(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    username = username,
                    expiresIn = token.expiresIn.toIntOrNull() ?: 1799,
                    servicePin = servicePin
                )
                Result.Success(Unit)
            } else {
                Result.Error(
                    when (response.code()) {
                        401 -> "Invalid username or password"
                        403 -> "Account locked. Please use the Bluelink app to unlock."
                        429 -> "Too many attempts. Please wait before trying again."
                        else -> "Login failed (${response.code()})"
                    },
                    response.code()
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error. Check your connection.")
        }
    }

    suspend fun logout(requirePassword: Boolean = true) {
        preferencesManager.clearSession(requirePassword = requirePassword)
        apiClient = null
        apiClientRegion = null
        canadaApiClient = null
        canadaApiClientRegion = null
        euApiClient = null
        euApiClientRegion = null
        euApiClientDeviceId = null
        euIdentityApiClient = null
        euIdentityApiClientRegion = null
        auApiClient = null
        auApiClientRegion = null
        auApiClientDeviceId = null
    }

    // ─── Vehicles ──────────────────────────────────────────────────────────────

    suspend fun getVehicles(): Result<List<Vehicle>> {
        if (isCanadaRegion()) return getCanadaVehicles()
        if (isEuropeRegion()) return getEuropeVehicles()
        if (isAustraliaRegion()) return getAustraliaVehicles()
        return try {
            var token = getToken()
            val username = getUsername()
            var response = getApiService().getVehicles(
                accessToken = token,
                username = username
            )
            if (!response.isSuccessful && (response.code() == 401 || response.code() == 403)) {
                token = runCatching { refreshPasswordBasedSession() }.getOrNull().orEmpty()
                if (token.isNotBlank()) {
                    response = getApiService().getVehicles(
                        accessToken = token,
                        username = getUsername()
                    )
                }
            }
            if (response.isSuccessful) {
                val vehicles = response.body()?.vehicles?.map { detail ->
                    detail.vehicle.copy(packageDetails = detail.packageDetails)
                } ?: emptyList()
                Result.Success(vehicles)
            } else {
                if (response.code() == 401 || response.code() == 403) {
                    preferencesManager.clearSession(requirePassword = true)
                    Result.Error("Session expired. Please sign in again.", response.code())
                } else {
                    Result.Error("Failed to fetch vehicles (${response.code()})")
                }
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────────

    suspend fun getVehicleStatus(
        vin: String,
        forceRefresh: Boolean = false,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<VehicleStatusData> {
        if (isCanadaRegion()) return getCanadaVehicleStatus(vin, forceRefresh, registrationId)
        if (isEuropeRegion()) return getEuropeVehicleStatus(vin, forceRefresh, registrationId, generation)
        if (isAustraliaRegion()) return getAustraliaVehicleStatus(vin, forceRefresh, registrationId, generation)
        return try {
            val token = getToken()
            val username = getUsername()
            val api = getApiService()

            val response = api.getVehicleStatus(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                refresh = forceRefresh.toString(),
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator
            )

            if (response.isSuccessful) {
                val data = response.body()?.vehicleStatus
                    ?: return Result.Error("Could not parse vehicle status")
                preferencesManager.setLastStatusRefresh(System.currentTimeMillis())
                Result.Success(data)
            } else {
                Result.Error("Status fetch failed (${response.code()})")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }


    suspend fun getVehicleLocation(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<VehicleLocation> {
        if (isCanadaRegion()) return Result.Error("Vehicle location for Canada is not mapped yet.")
        if (isEuropeRegion()) return Result.Error("Vehicle location for Europe is not mapped into the BlueBridge location model yet.")
        if (isAustraliaRegion()) return Result.Error("Vehicle location for Australia/NZ is available in the upstream API but is not mapped into the BlueBridge location model yet.")
        return try {
            val token = getToken()
            val username = getUsername()
            val response = getApiService().getVehicleLocation(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator
            )

            if (response.isSuccessful) {
                val location = response.body()
                    ?: return Result.Error("Could not parse vehicle location")
                Result.Success(location)
            } else {
                Result.Error("Location fetch failed (${response.code()})")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    // ─── Lock / Unlock ─────────────────────────────────────────────────────────

    suspend fun lockDoors(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId, "Lock") { accessToken, vehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().lockDoors(accessToken, vehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId, "Lock") { authorization, vehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlDoor(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "close")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId, "Lock") { authorization, vehicleId ->
                getAuApiService().controlDoor(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "close")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin(required = false)
            val response = getApiService().lockDoors(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                formUserName = username,
                formVin = vin
            )
            validateRawCommandResponse(response, "Lock")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun unlockDoors(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId, "Unlock") { accessToken, vehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().unlockDoors(accessToken, vehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId, "Unlock") { authorization, vehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlDoor(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "open")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId, "Unlock") { authorization, vehicleId ->
                getAuApiService().controlDoor(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "open")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().unlockDoors(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                formUserName = username,
                formVin = vin
            )
            validateRawCommandResponse(response, "Unlock")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    // ─── Remote Start / Stop ───────────────────────────────────────────────────

    suspend fun startEngine(
        vin: String,
        tempF: String = "72",
        hvacOn: Boolean = true,
        defrost: Boolean = false,
        heatedSteering: Boolean = false,
        driverSeatHeat: Int = 2,
        passengerSeatHeat: Int = 2,
        rearLeftSeatHeat: Int = 2,
        rearRightSeatHeat: Int = 2,
        durationMinutes: Int = 10,
        isEv: Boolean = false,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId, "Start") { accessToken, vehicleId, pAuth, deviceId, pin ->
                val payload = canadaClimatePayload(pin, tempF, defrost, durationMinutes, isEv)
                if (isEv) {
                    getCanadaApiService().startEvClimate(accessToken, vehicleId, pAuth, deviceId, payload)
                } else {
                    getCanadaApiService().startEngine(accessToken, vehicleId, pAuth, deviceId, payload)
                }
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId, "Start") { authorization, vehicleId ->
                val deviceId = registeredEuropeDeviceId()
                val payload = europeEnginePayload("start", deviceId, tempF, defrost, durationMinutes)
                if (isEv) getEuApiService().controlEvClimate(authorization, vehicleId, payload) else getEuApiService().controlEngine(authorization, vehicleId, payload)
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId, "Start") { authorization, vehicleId ->
                val payload = australiaEnginePayload("start", tempF, defrost, durationMinutes)
                if (isEv) getAuApiService().controlEvClimate(authorization, vehicleId, payload) else getAuApiService().controlEngine(authorization, vehicleId, payload)
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val request = RemoteStartRequest(
                airCtrl = if (hvacOn) 1 else 0,
                airTemp = AirTempRequest(value = tempF, unit = 1),
                defrost = defrost,
                heating1 = if (heatedSteering) 1 else 0,
                igniOnDuration = durationMinutes,
                seatHeaterVentInfo = SeatInfo(
                    driverSeatHeatCool = driverSeatHeat,
                    passengerSeatHeatCool = passengerSeatHeat,
                    rearLeftSeatHeatCool = rearLeftSeatHeat,
                    rearRightSeatHeatCool = rearRightSeatHeat
                ),
                username = username,
                vin = vin
            )
            val response = if (isEv) {
                getApiService().startEvClimate(
                    accessToken = token,
                    username = username,
                    vin = vin,
                    appCloudVin = vin,
                    servicePin = pin,
                    registrationId = registrationId,
                    gen = generation,
                    brandIndicator = brandIndicator,
                    request = request
                )
            } else {
                getApiService().startEngine(
                    accessToken = token,
                    username = username,
                    vin = vin,
                    appCloudVin = vin,
                    servicePin = pin,
                    registrationId = registrationId,
                    gen = generation,
                    brandIndicator = brandIndicator,
                    request = request
                )
            }
            validateRawCommandResponse(response, "Start")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun stopEngine(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId, "Stop") { accessToken, vehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().stopEngine(accessToken, vehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId, "Stop") { authorization, vehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlEngine(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId, "Stop") { authorization, vehicleId ->
                getAuApiService().controlEngine(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().stopEngine(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator
            )
            validateRawCommandResponse(response, "Stop")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }


    private suspend fun stopEvClimate(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId, "Stop Climate") { accessToken, vehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().stopEvClimate(accessToken, vehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId, "Stop Climate") { authorization, vehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlEvClimate(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId, "Stop Climate") { authorization, vehicleId ->
                getAuApiService().controlEvClimate(authorization, vehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().stopEvClimate(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator
            )
            validateRawCommandResponse(response, "Stop Climate")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    // ─── Climate ───────────────────────────────────────────────────────────────

    suspend fun startClimate(
        vin: String,
        tempF: String = "72",
        defrost: Boolean = false,
        driverSeatHeat: Int = 2,
        passengerSeatHeat: Int = 2,
        rearLeftSeatHeat: Int = 2,
        rearRightSeatHeat: Int = 2,
        isEv: Boolean = false,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        // Hyundai USA uses remote-start/preconditioning for cabin climate.
        // EVs go through /ac/v2/evc/fatc/start; gas vehicles through /ac/v2/rcs/rsc/start.
        // Seat heat/vent values use Hyundai's reported level codes:
        // 2/off, 6-8 heat low/medium/high, 3-5 vent low/medium/high.
        return startEngine(
            vin = vin,
            tempF = tempF,
            hvacOn = true,
            defrost = defrost,
            driverSeatHeat = driverSeatHeat,
            passengerSeatHeat = passengerSeatHeat,
            rearLeftSeatHeat = rearLeftSeatHeat,
            rearRightSeatHeat = rearRightSeatHeat,
            isEv = isEv,
            registrationId = registrationId,
            generation = generation,
            brandIndicator = brandIndicator
        )
    }

    suspend fun stopClimate(
        vin: String,
        isEv: Boolean = false,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        // IONIQ EV climate preconditioning should stop through the EV FATC route.
        // The non-EV rsc/stop route returns unsupported for the IONIQ 9.
        return if (isEv) {
            stopEvClimate(
                vin = vin,
                registrationId = registrationId,
                generation = generation,
                brandIndicator = brandIndicator
            )
        } else {
            stopEngine(
                vin = vin,
                registrationId = registrationId,
                generation = generation,
                brandIndicator = brandIndicator
            )
        }
    }

    // ─── EV Charging ───────────────────────────────────────────────────────────

    suspend fun startCharging(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H",
        vehicleId: String = ""
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId.ifBlank { vehicleId }, "Charge start") { accessToken, caVehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().startCharging(accessToken, caVehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId.ifBlank { vehicleId }, "Charge start") { authorization, euVehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlCharge(authorization, euVehicleId, JsonObject().apply {
                    addProperty("action", "start")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId.ifBlank { vehicleId }, "Charge start") { authorization, auVehicleId ->
                getAuApiService().controlCharge(authorization, auVehicleId, JsonObject().apply {
                    addProperty("action", "start")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()

            val response = getApiService().startCharging(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                request = EVChargeRequest(userName = username, vin = vin, action = "start")
            )
            validateEmptyCommandResponse(response, "Charge start")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun stopCharging(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H",
        vehicleId: String = ""
    ): Result<Unit> {
        if (isCanadaRegion()) {
            return runCanadaPinCommand(vin, registrationId.ifBlank { vehicleId }, "Charge stop") { accessToken, caVehicleId, pAuth, deviceId, pin ->
                getCanadaApiService().stopCharging(accessToken, caVehicleId, pAuth, deviceId, mapOf("pin" to pin))
            }
        }
        if (isEuropeRegion()) {
            return runEuropeCommand(vin, registrationId.ifBlank { vehicleId }, "Charge stop") { authorization, euVehicleId ->
                val deviceId = registeredEuropeDeviceId()
                getEuApiService().controlCharge(authorization, euVehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", deviceId)
                })
            }
        }
        if (isAustraliaRegion()) {
            return runAustraliaCommand(vin, registrationId.ifBlank { vehicleId }, "Charge stop") { authorization, auVehicleId ->
                getAuApiService().controlCharge(authorization, auVehicleId, JsonObject().apply {
                    addProperty("action", "stop")
                    addProperty("deviceId", australiaDeviceId())
                })
            }
        }
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()

            val response = getApiService().stopCharging(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                request = EVChargeRequest(userName = username, vin = vin, action = "stop")
            )
            validateEmptyCommandResponse(response, "Charge stop")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }


    private fun normalizeScheduleTime(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val padded = when {
            digits.length >= 4 -> digits.takeLast(4)
            digits.length == 3 -> digits.padStart(4, '0')
            else -> "0000"
        }
        val hour = padded.take(2).toIntOrNull() ?: 0
        val minute = padded.takeLast(2).toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return "0000"
        return "%02d%02d".format(java.util.Locale.US, hour, minute)
    }

    private fun scheduleTimeJson(hhmm: String): JsonObject {
        val normalized = normalizeScheduleTime(hhmm)
        return JsonObject().apply {
            addProperty("time", normalized)
            addProperty("timeSection", if ((normalized.take(2).toIntOrNull() ?: 0) >= 12) 1 else 0)
        }
    }

    private fun scheduleEndpointJson(hhmm: String, day: Int = 0): JsonObject = JsonObject().apply {
        add("time", scheduleTimeJson(hhmm))
        addProperty("day", day)
    }

    private fun offPeakTimeJson(start: String, end: String, lowercaseKeys: Boolean): JsonObject = JsonObject().apply {
        val startKey = if (lowercaseKeys) "starttime" else "startTime"
        val endKey = if (lowercaseKeys) "endtime" else "endTime"
        add(startKey, scheduleTimeJson(start))
        add(endKey, scheduleTimeJson(end))
    }

    private fun numericWeekDaysJson(): JsonArray = JsonArray().apply {
        (0..6).forEach { add(it) }
    }

    private fun reservationDetailJson(chargeTime: String, enabled: Boolean = true): JsonObject = JsonObject().apply {
        add("reservChargeInfoDetail", JsonObject().apply {
            addProperty("reservChargeSet", enabled)
            add("reservFatcSet", JsonObject().apply {
                addProperty("airCtrl", 0)
                add("airTemp", JsonObject().apply {
                    addProperty("value", "OFF")
                    addProperty("unit", 0)
                })
                addProperty("defrost", false)
            })
            add("reservInfo", JsonObject().apply {
                add("day", numericWeekDaysJson())
                add("time", scheduleTimeJson(chargeTime))
            })
        })
    }

    private fun buildChargeSchedulePayload(
        chargeStartTime: String,
        chargeEndTime: String,
        offPeakStartTime: String,
        offPeakEndTime: String,
        offPeakOnly: Boolean,
        includeExtendedWindowFields: Boolean = false,
        europeanPayload: Boolean = false
    ): JsonObject {
        val chargeStart = normalizeScheduleTime(chargeStartTime)
        val chargeEnd = normalizeScheduleTime(chargeEndTime)
        val offPeakStart = normalizeScheduleTime(offPeakStartTime)
        val offPeakEnd = normalizeScheduleTime(offPeakEndTime)
        val days = JsonArray().apply {
            listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY").forEach { add(it) }
        }
        if (!europeanPayload) {
            // Keep North America close to observed MyHyundai traffic. The NA endpoint appears
            // to accept exactly one charge start time plus optional off-peak settings; sending
            // synthetic ect/reservation detail objects can return 200/empty but not change the car.
            return JsonObject().apply {
                addProperty("airCtrl", 0)
                addProperty("chargeSet", true)
                addProperty("defrost", false)
                add("offpeakPowerInfo", JsonObject().apply {
                    if (offPeakOnly) {
                        addProperty("offPeakPowerFlag", 1)
                        add("offPeakPowerTime1", offPeakTimeJson(offPeakStart, offPeakEnd, lowercaseKeys = false))
                    } else {
                        addProperty("offPeakPowerFlag", 0)
                        add("offPeakPowerTime1", JsonObject())
                    }
                    add("offPeakPowerTime2", JsonObject())
                })
                add("day", days)
                addProperty("alarmTime", 0)
                add("airTemp", JsonObject().apply {
                    addProperty("value", "72")
                    addProperty("unit", 1)
                })
                addProperty("reservFlag", 1)
                addProperty("startTime", chargeStart)
            }
        }
        return JsonObject().apply {
            addProperty("airCtrl", 0)
            addProperty("chargeSet", true)
            addProperty("defrost", false)
            add("day", days)
            addProperty("alarmTime", 0)
            add("airTemp", JsonObject().apply {
                addProperty("value", "72")
                addProperty("unit", 1)
            })
            addProperty("reservFlag", 1)
            addProperty("startTime", chargeStart)
            // The status API reports the editable charge window as reservChargeInfos.ect.
            // Send it back even for NA so the UI's charge start/end fields can round-trip.
            add("ect", JsonObject().apply {
                add("start", scheduleEndpointJson(chargeStart, 7))
                add("end", scheduleEndpointJson(chargeEnd, 7))
            })
            if (includeExtendedWindowFields) {
                addProperty("endTime", chargeEnd)
            }
            add("offpeakPowerInfo", JsonObject().apply {
                // Hyundai/Kia uses 1 = off-peak only, 2 = prefer/prioritize off-peak.
                // Sending 0 disables/clears off-peak on some backends, which makes the
                // command look accepted while nothing useful changes.
                addProperty("offPeakPowerFlag", if (offPeakOnly) 1 else 2)
                add("offPeakPowerTime1", offPeakTimeJson(offPeakStart, offPeakEnd, lowercaseKeys = true))
                add("offPeakPowerTime2", JsonObject())
            })
            add("reservChargeInfo", reservationDetailJson(chargeStart, enabled = true))
            add("reserveChargeInfo2", reservationDetailJson(chargeEnd, enabled = false))
        }
    }

    suspend fun setChargingSchedule(
        vin: String,
        chargeStartTime: String,
        chargeEndTime: String,
        offPeakStartTime: String,
        offPeakEndTime: String,
        offPeakOnly: Boolean,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H",
        vehicleId: String = ""
    ): Result<Unit> {
        if (isCanadaRegion()) return Result.Error("Canadian charging schedule setting is not mapped yet.")
        if (isAustraliaRegion()) return Result.Error("Australia/NZ charging schedule setting is not mapped yet.")
        if (isEuropeRegion()) {
            val payload = buildChargeSchedulePayload(
                chargeStartTime = chargeStartTime,
                chargeEndTime = chargeEndTime,
                offPeakStartTime = offPeakStartTime,
                offPeakEndTime = offPeakEndTime,
                offPeakOnly = offPeakOnly,
                includeExtendedWindowFields = true,
                europeanPayload = true
            )
            return runEuropeCommand(vin, registrationId.ifBlank { vehicleId }, "Set charging schedule") { authorization, euVehicleId ->
                val primary = getEuApiService().setChargeSchedule(authorization, euVehicleId, payload)
                if (primary.isSuccessful || primary.code() !in setOf(404, 405)) {
                    primary
                } else {
                    getEuApiService().setChargeScheduleAlt(authorization, euVehicleId, payload)
                }
            }
        }

        val payload = buildChargeSchedulePayload(
            chargeStartTime = chargeStartTime,
            chargeEndTime = chargeEndTime,
            offPeakStartTime = offPeakStartTime,
            offPeakEndTime = offPeakEndTime,
            offPeakOnly = offPeakOnly,
            includeExtendedWindowFields = false,
            europeanPayload = false
        )

        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().setChargeSchedule(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                request = payload
            )
            validateRawCommandResponse(response, "Set charging schedule")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun setChargeTarget(
        vin: String,
        acTarget: Int = 90,
        dcTarget: Int = 80,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) return Result.Error("Canadian charge target setting is not mapped yet; start/stop charging is available for this vehicle.")
        if (isEuropeRegion()) return Result.Error("European charge target setting is not mapped yet; start/stop charging is available for this vehicle.")
        if (isAustraliaRegion()) return Result.Error("Australia/NZ charge target setting is not mapped yet; start/stop charging is available for this vehicle.")
        val allowedTargets = setOf(50, 60, 70, 80, 90, 100)
        if (acTarget !in allowedTargets || dcTarget !in allowedTargets) {
            return Result.Error("Charge targets must be one of 50, 60, 70, 80, 90, or 100%")
        }

        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()

            // Bluelinky maps EV charge target modes as FAST = 0 and SLOW = 1.
            // On the observed US IONIQ 9 status payload, plugType 0 corresponds to DC
            // and plugType 1 corresponds to AC.
            val request = SpaChargeTargetRequest(
                targets = listOf(
                    ChargeTarget(plugType = 0, targetSoc = dcTarget),
                    ChargeTarget(plugType = 1, targetSoc = acTarget)
                )
            )

            val response = getApiService().setSpaChargeTargets(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                request = request
            )

            validateCommandResponse(response, "Set charge targets")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }


    // ─── Horn / Lights ─────────────────────────────────────────────────────────

    suspend fun hornAndLights(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) return Result.Error("Horn/lights for Canada is not mapped yet.")
        if (isEuropeRegion()) return Result.Error("Horn/lights for Europe is not mapped yet.")
        if (isAustraliaRegion()) return Result.Error("Horn/lights for Australia/NZ is not mapped yet.")
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().hornAndLights(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                formUserName = username,
                formVin = vin
            )
            validateCommandResponse(response, "Horn/lights")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    suspend fun flashLights(
        vin: String,
        registrationId: String = "",
        generation: String = "3",
        brandIndicator: String = "H"
    ): Result<Unit> {
        if (isCanadaRegion()) return Result.Error("Flash lights for Canada is not mapped yet.")
        if (isEuropeRegion()) return Result.Error("Flash lights for Europe is not mapped yet.")
        if (isAustraliaRegion()) return Result.Error("Flash lights for Australia/NZ is not mapped yet.")
        return try {
            val token = getToken()
            val username = getUsername()
            val pin = getServicePin()
            val response = getApiService().flashLightsOnly(
                accessToken = token,
                username = username,
                vin = vin,
                appCloudVin = vin,
                servicePin = pin,
                registrationId = registrationId,
                gen = generation,
                brandIndicator = brandIndicator,
                formUserName = username,
                formVin = vin
            )
            validateCommandResponse(response, "Flash lights")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Network error")
        }
    }

    // ─── Official-app-style feature hooks ─────────────────────────────────────

    fun setValetMode(vin: String, enabled: Boolean): Result<Unit> {
        val requestedState = if (enabled) "enable" else "disable"
        val vehicleSuffix = vin.takeLast(6).takeIf { it.isNotBlank() }?.let { " for vehicle ending in $it" }.orEmpty()
        return Result.Error(
            "Valet Mode $requestedState$vehicleSuffix is saved locally, but the Hyundai valet endpoint is not mapped in this project yet."
        )
    }

    fun getSurroundViewSnapshot(vin: String): Result<SurroundViewSnapshot> {
        val vehicleSuffix = vin.takeLast(6).takeIf { it.isNotBlank() }?.let { " for vehicle ending in $it" }.orEmpty()
        return Result.Error(
            "Surround View Monitor$vehicleSuffix requires Hyundai camera snapshot endpoints that are not mapped in this project yet."
        )
    }

}