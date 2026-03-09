package com.trustnet.vshield.ui.screen

import android.app.Application
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
    private val repo = BlocklistRepository(application)
    private val syncPrefs = SyncPreferences(application)

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("Khởi động hệ thống...")
    val statusText = _statusText.asStateFlow()

    init {
        startInitialization()
    }

    private fun startInitialization() {
        viewModelScope.launch {
            _progress.value = 0.1f

            // 1. ĐỒNG BỘ DỮ LIỆU TỪ SERVER VỚI CƠ CHẾ AUTO-RETRY
            var syncSuccess = false
            while (!syncSuccess) {
                val result = repo.syncWithProgress { p, text ->
                    _progress.value = p / 100f
                    _statusText.value = text
                }

                if (result is SyncResult.Error) {
                    if (syncPrefs.needsFullSync) {
                        // Rớt mạng ở lần tải đầu -> Tự động đợi 2s rồi lặp lại quy trình
                        _statusText.value = "Mạng yếu. Đang thử kết nối lại..."
                        delay(2000)
                    } else {
                        // Cập nhật Delta thất bại -> Không sao, dùng data cũ
                        _statusText.value = "Đang dùng dữ liệu ngoại tuyến."
                        delay(1000)
                        syncSuccess = true
                    }
                } else {
                    // Đồng bộ thành công
                    syncSuccess = true
                }
            }

            // 2. KHỞI TẠO AI SAU KHI ĐÃ CÓ DATABASE
            _progress.value = 0.8f
            _statusText.value = "Đang khởi động Trí tuệ nhân tạo (AI)..."

            withContext(Dispatchers.IO) {
                OnDeviceAi.init(getApplication())
            }

            _progress.value = 1.0f
            _statusText.value = "Hoàn tất! Mở khóa hệ thống..."
            delay(800)

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

        // ĐÃ SỬA THÀNH Vshield
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

        // CHỈ HIỆN THANH TIẾN TRÌNH VÀ TEXT (KHÔNG CÒN NÚT THỬ LẠI)
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