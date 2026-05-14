package com.bluebridge.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluebridge.android.data.api.Region
import com.bluebridge.android.data.repository.PreferencesManager
import com.bluebridge.android.data.repository.SecureCredentialsManager
import com.bluebridge.android.data.repository.Result
import com.bluebridge.android.data.repository.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: VehicleRepository,
    private val preferencesManager: PreferencesManager,
    private val secureCredentialsManager: SecureCredentialsManager
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean?> = preferencesManager.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val region: StateFlow<String> = preferencesManager.region
        .stateIn(viewModelScope, SharingStarted.Eagerly, Region.US_HYUNDAI.name)

    private val _loginUiState = MutableStateFlow(LoginUiState())
    val loginUiState: StateFlow<LoginUiState> = _loginUiState.asStateFlow()

    private val savedCredentialsAvailable = MutableStateFlow(secureCredentialsManager.hasSavedCredentials())

    val passwordRequired: StateFlow<Boolean> = preferencesManager.passwordRequired
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val biometricLoginAvailable: StateFlow<Boolean> = combine(
        preferencesManager.biometricEnabled,
        savedCredentialsAvailable,
        passwordRequired
    ) { biometricEnabled, hasSavedCredentials, requiresPassword ->
        biometricEnabled && hasSavedCredentials && !requiresPassword
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val biometricSessionRecoveryAvailable: StateFlow<Boolean> = combine(
        biometricLoginAvailable,
        preferencesManager.hasRecoverableSession
    ) { biometricAvailable, hasRecoverableSession ->
        biometricAvailable && hasRecoverableSession
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRegion(region: Region) {
        viewModelScope.launch {
            preferencesManager.setRegion(region.name)
            _loginUiState.value = _loginUiState.value.copy(error = null)
        }
    }

    fun login(username: String, password: String, servicePin: String = "", saveForBiometrics: Boolean = true) {
        if (username.isBlank() || password.isBlank()) {
            _loginUiState.value = LoginUiState(error = "Please enter your email and password")
            return
        }
        viewModelScope.launch {
            performLogin(
                username = username.trim(),
                password = password,
                servicePin = servicePin.trim(),
                saveForBiometrics = saveForBiometrics
            )
        }
    }

    fun loginWithSavedCredentials() {
        val savedCredentials = secureCredentialsManager.getSavedCredentials()
        if (savedCredentials == null) {
            _loginUiState.value = LoginUiState(error = "No saved biometric login is available. Sign in with your password once first.")
            savedCredentialsAvailable.value = false
            return
        }

        viewModelScope.launch {
            performLogin(
                username = savedCredentials.username,
                password = savedCredentials.password,
                servicePin = savedCredentials.servicePin,
                saveForBiometrics = true
            )
        }
    }

    private suspend fun performLogin(
        username: String,
        password: String,
        servicePin: String,
        saveForBiometrics: Boolean
    ) {
        _loginUiState.value = LoginUiState(isLoading = true)
        when (val result = repository.login(username, password, servicePin)) {
            is Result.Success -> {
                if (saveForBiometrics) {
                    secureCredentialsManager.saveCredentials(username, password, servicePin)
                    savedCredentialsAvailable.value = true
                }
                android.util.Log.d("BlueBridge", "Login success")
                _loginUiState.value = LoginUiState(success = true)
            }
            is Result.Error -> {
                android.util.Log.d("BlueBridge", "Login error: ${result.message}")
                _loginUiState.value = LoginUiState(error = result.message)
            }
            else -> {}
        }
    }

    fun requirePasswordLogin() {
        viewModelScope.launch {
            repository.logout(requirePassword = true)
            _loginUiState.value = LoginUiState()
        }
    }

    fun logout(clearSavedCredentials: Boolean = false) {
        viewModelScope.launch {
            repository.logout(requirePassword = true)
            if (clearSavedCredentials) {
                secureCredentialsManager.clearSavedCredentials()
                savedCredentialsAvailable.value = false
            }
            _loginUiState.value = LoginUiState()
        }
    }

    fun clearError() {
        _loginUiState.value = _loginUiState.value.copy(error = null)
    }
}
