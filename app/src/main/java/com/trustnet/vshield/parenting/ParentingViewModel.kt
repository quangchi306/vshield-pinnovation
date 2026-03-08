package com.trustnet.vshield.parenting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ParentingUiState(
    val parentingEnabled: Boolean = false,
    val hasPassword: Boolean = false,
    val isUnlocked: Boolean = false,
    val unlockedUntilEpochMs: Long? = null
)

enum class ParentAction {
    ToggleProtectionOff,
    ToggleProtectionOn,
    ChangeDns,
    ChangeFilterConfig,
    UpdateBlocklist,
    ToggleParentingMode,
    ChangeParentPassword,
    UnlockSession,
    Generic
}

class ParentingViewModel(application: Application) : AndroidViewModel(application) {

    companion object Config {
        const val MIN_PASSWORD_LENGTH = 6
        const val UNLOCK_WINDOW_MS: Long = 15_000L


        const val LOCK_BLOCKLIST_UPDATE: Boolean = false


        const val LOCK_ENABLE_PROTECTION: Boolean = false


        const val ENABLE_UNLOCK_SESSION: Boolean = true
    }

    private val prefs = ParentingPrefs(application.applicationContext)

    private val _uiState = MutableStateFlow(ParentingUiState())
    val uiState: StateFlow<ParentingUiState> = _uiState.asStateFlow()

    private var storedHashB64: String? = null
    private var storedSaltB64: String? = null

    private var unlockJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.data.collect { data ->
                storedHashB64 = data.passwordHashBase64
                storedSaltB64 = data.passwordSaltBase64


                if (data.parentingEnabled && !data.hasPassword) {
                    viewModelScope.launch { prefs.setParentingEnabled(false) }
                }

                val enabledSafe = data.parentingEnabled && data.hasPassword

                // Nếu password bị xóa/clear -> khóa session
                if (!data.hasPassword && _uiState.value.isUnlocked) {
                    lockNow()
                }

                _uiState.update {
                    it.copy(
                        parentingEnabled = enabledSafe,
                        hasPassword = data.hasPassword
                    )
                }
            }
        }
    }

    fun shouldRequireAuthFor(action: ParentAction): Boolean {
        val s = _uiState.value


        if (s.isUnlocked) return false


        val requireWhenPasswordExists = action == ParentAction.ToggleParentingMode ||
                action == ParentAction.ChangeParentPassword ||
                action == ParentAction.UnlockSession

        if (requireWhenPasswordExists) {
            return s.hasPassword
        }


        if (!s.parentingEnabled) return false

        return when (action) {
            ParentAction.ToggleProtectionOn -> LOCK_ENABLE_PROTECTION
            ParentAction.UpdateBlocklist -> LOCK_BLOCKLIST_UPDATE
            else -> true
        }
    }

    fun setParentingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setParentingEnabled(enabled)
            if (!enabled) lockNow()
        }
    }

    fun lockNow() {
        unlockJob?.cancel()
        unlockJob = null
        _uiState.update { it.copy(isUnlocked = false, unlockedUntilEpochMs = null) }
    }

    fun unlockForWindow(windowMs: Long = UNLOCK_WINDOW_MS) {
        val until = System.currentTimeMillis() + windowMs
        unlockJob?.cancel()

        _uiState.update { it.copy(isUnlocked = true, unlockedUntilEpochMs = until) }

        unlockJob = viewModelScope.launch {
            val delayMs = until - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            lockNow()
        }
    }

    suspend fun verifyPassword(password: String): Boolean {
        val hashB64 = storedHashB64
        val saltB64 = storedSaltB64
        if (hashB64.isNullOrBlank() || saltB64.isNullOrBlank()) return false

        return withContext(Dispatchers.Default) {
            val expectedHash = PasswordHasher.decodeBase64(hashB64)
            val salt = PasswordHasher.decodeBase64(saltB64)
            PasswordHasher.verify(password.toCharArray(), expectedHash, salt)
        }
    }


    suspend fun setParentPassword(newPassword: String): String? {
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return "Mật khẩu cần tối thiểu $MIN_PASSWORD_LENGTH ký tự."
        }

        val (hashB64, saltB64) = withContext(Dispatchers.Default) {
            val salt = PasswordHasher.generateSalt()
            val hash = PasswordHasher.hash(newPassword.toCharArray(), salt)
            PasswordHasher.encodeBase64(hash) to PasswordHasher.encodeBase64(salt)
        }

        return try {
            prefs.setPassword(hashB64, saltB64)
            null
        } catch (_: Throwable) {
            "Không thể lưu mật khẩu. Vui lòng thử lại."
        }
    }
}