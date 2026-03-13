package com.trustnet.vshield.ui.screen

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.data.local.SyncPreferences
import com.trustnet.vshield.network.OnDeviceAi
import com.trustnet.vshield.repository.BlocklistRepository
import com.trustnet.vshield.repository.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashViewModel(application: Application) : AndroidViewModel(application) {

    private val repo      = BlocklistRepository(application)
    private val syncPrefs = SyncPreferences(application)

    private val _isReady     = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _progress    = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _statusText  = MutableStateFlow("Khởi động hệ thống...")
    val statusText = _statusText.asStateFlow()

    init {
        startInitialization()
    }

    private fun startInitialization() {
        viewModelScope.launch {

            //Đã có .bin cache → vào app ngay (~50ms)
            if (DomainBlacklist.hasBinCache(getApplication())) {

                _progress.value  = 0.1f
                _statusText.value = "Đang nạp bộ lọc từ cache..."

                val loadOk = withContext(Dispatchers.IO) {
                    DomainBlacklist.loadFromBinCache(getApplication())
                }

                // Nếu .bin bị lỗi → fallback load từ DB
                if (!loadOk) {
                    _statusText.value = "Cache lỗi, đang nạp từ dữ liệu cũ..."
                    withContext(Dispatchers.IO) {
                        DomainBlacklist.init(getApplication())
                    }
                }

                _progress.value  = 0.7f
                _statusText.value = "Đang khởi động AI..."

                withContext(Dispatchers.IO) {
                    OnDeviceAi.init(getApplication())
                }

                _progress.value  = 1.0f
                _statusText.value = "Hoàn tất!"
                delay(400)

                // Vào app ngay — không chờ sync
                _isReady.value = true

                // Sync delta ngầm — KHÔNG block UI
                launch(Dispatchers.IO) {
                    Log.i("SplashVM", "Bắt đầu sync ngầm sau khi vào app...")
                    val result = repo.sync()
                    Log.i("SplashVM", "Sync ngầm hoàn tất: $result")
                }

                return@launch
            }

            // NHÁNH CHẬM: Lần đầu cài — bắt buộc sync trước
            _progress.value  = 0.05f
            _statusText.value = "Lần đầu khởi động, đang tải dữ liệu..."

            val maxRetry = 5
            var retryCount = 0
            var syncSuccess = false

            while (!syncSuccess) {
                val result = repo.syncWithProgress { p, text ->
                    _progress.value  = p / 100f
                    _statusText.value = text
                }

                when {
                    // Sync thành công
                    result is SyncResult.Success ||
                            result is SyncResult.AlreadyUpToDate -> {
                        syncSuccess = true
                    }

                    // Lỗi mạng — còn lần retry
                    result is SyncResult.Error && retryCount < maxRetry -> {
                        retryCount++
                        _statusText.value =
                            "Mạng yếu. Thử lại ($retryCount/$maxRetry)..."
                        delay(2000)
                    }

                    // Hết retry — dùng assets bundled sẵn, vẫn cho vào app
                    else -> {
                        _statusText.value = "Không có mạng. Dùng dữ liệu mặc định..."
                        withContext(Dispatchers.IO) {
                            DomainBlacklist.init(getApplication())
                        }
                        syncSuccess = true
                    }
                }
            }

            _progress.value  = 0.85f
            _statusText.value = "Đang khởi động AI..."

            withContext(Dispatchers.IO) {
                OnDeviceAi.init(getApplication())
            }

            _progress.value  = 1.0f
            _statusText.value = "Hoàn tất! Mở khóa hệ thống..."
            delay(600)

            _isReady.value = true
        }
    }
}

@Composable
fun SplashScreen(
    progress: Float,
    statusText: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = "Logo",
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vshield",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Lá chắn bảo vệ thiết bị",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(64.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}