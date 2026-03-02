package com.trustnet.vshield.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trustnet.vshield.repository.BlocklistRepository
import com.trustnet.vshield.repository.SyncResult
import com.trustnet.vshield.sync.BlocklistSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncUiState(
    val isSyncing:       Boolean = false,
    val totalBlocked:    Int     = 0,
    val lastSyncDisplay: String  = "Chưa cập nhật",
    val currentVersion:  Int     = 0,
    val errorMessage:    String? = null,
)

class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BlocklistRepository(app)

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    init { loadStats() }

    fun loadStats() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                totalBlocked    = repo.getTotalCount(),
                lastSyncDisplay = formatTime(repo.getLastSyncTime()),
                currentVersion  = repo.getCurrentVersion(),
            )
        }
    }

    fun syncNow() {
        _state.value = _state.value.copy(isSyncing = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repo.sync()) {
                is SyncResult.Success -> _state.value = _state.value.copy(
                    isSyncing       = false,
                    totalBlocked    = repo.getTotalCount(),
                    lastSyncDisplay = formatTime(System.currentTimeMillis()),
                    currentVersion  = result.version,
                )
                is SyncResult.AlreadyUpToDate -> _state.value = _state.value.copy(
                    isSyncing       = false,
                    lastSyncDisplay = formatTime(System.currentTimeMillis()),
                )
                is SyncResult.Error -> _state.value = _state.value.copy(
                    isSyncing    = false,
                    errorMessage = "Lỗi: ${result.message}",
                )
            }
        }
    }

    fun reportDomain(domain: String, category: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            repo.reportDomain(domain, category)
                .onSuccess  { onResult("✅ $it") }
                .onFailure  { onResult("❌ ${it.message}") }
        }
    }

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return "Chưa cập nhật"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000     -> "Vừa xong"
            diff < 3_600_000  -> "${diff / 60_000} phút trước"
            diff < 86_400_000 -> "${diff / 3_600_000} giờ trước"
            else -> SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }
}
