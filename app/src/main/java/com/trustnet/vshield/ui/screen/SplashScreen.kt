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

            // FIX: Nếu lists đã được nạp sẵn vào RAM (service đang chạy, cùng process),
            // KHÔNG khởi tạo lại. Lý do: gọi loadFromBinCache() lần thứ hai trong khi
            // VPN đang xử lý traffic sẽ set lần lượt từng AtomicReference (phishing →
            // adult → gambling → whitelist). Giữa các lần set đó, processPacket() chạy
            // với filter cũ+mới lẫn lộn → cả blacklist lẫn whitelist hoạt động sai.
            //
            // Kịch bản này xảy ra khi: người dùng đóng app từ recents rồi mở lại,
            // trong khi VPN service vẫn đang chạy trong nền.
            if (DomainBlacklist.isListsReady()) {
                Log.i("SplashVM", "Lists đã sẵn sàng (service đang chạy), bỏ qua init.")
                _progress.value   = 1.0f
                _statusText.value = "Hệ thống đang hoạt động"
                _isReady.value    = true

                // Vẫn chạy sync ngầm để cập nhật danh sách mới nhất
                launch(Dispatchers.IO) {
                    Log.i("SplashVM", "Sync ngầm (fast-path)...")
                    val result = repo.sync()
                    Log.i("SplashVM", "Sync ngầm hoàn tất: $result")
                }
                return@launch
            }

            // ── NHÁNH NHANH: Đã có .bin cache → vào app ngay (~50ms) ──────────────
            if (DomainBlacklist.hasBinCache(getApplication())) {

                _progress.value  = 0.1f
                _statusText.value = "Đang nạp bộ lọc từ cache..."

                val loadOk = withContext(Dispatchers.IO) {
                    DomainBlacklist.loadFromBinCache(getApplication())
                }

                // Nếu .bin bị lỗi → fallback load từ DB.
                // FIX LỖI 2: Dùng reloadFromDatabaseSync()
                // init() là fire-and-forget (spawn coroutine rồi return ngay),
                // withContext() không thực sự chờ filter nạp xong.
                if (!loadOk) {
                    _statusText.value = "Cache lỗi, đang nạp từ dữ liệu cũ..."
                    withContext(Dispatchers.IO) {
                        DomainBlacklist.reloadFromDatabaseSync(getApplication()) // ← FIX: thay init()
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
                // FIX LỖI 1: repo.sync() đã được sửa để xử lý AlreadyUpToDate đúng cách
                launch(Dispatchers.IO) {
                    Log.i("SplashVM", "Bắt đầu sync ngầm sau khi vào app...")
                    val result = repo.sync()
                    Log.i("SplashVM", "Sync ngầm hoàn tất: $result")
                }

                return@launch
            }

            //Lần đầu cài - bắt buộc sync trước
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
                    // Sync thành công hoặc đã up-to-date
                    // FIX LỖI 3: Cả hai trường hợp này giờ đã được xử lý đúng bởi
                    // syncWithProgress() đã được sửa ở BlocklistRepository.
                    // Không cần gọi thêm reloadFromDatabaseSync() hay init() ở đây nữa,
                    // vì syncWithProgress() đã tự làm khi cần.
                    result is SyncResult.Success ||
                            result is SyncResult.AlreadyUpToDate -> {
                        // Nếu vì lý do nào đó filter vẫn chưa nạp (fallback an toàn),
                        // nạp trực tiếp từ DB.
                        if (!DomainBlacklist.isListsReady()) {
                            Log.w("SplashVM", "Sau sync, filter vẫn chưa ready — fallback reload DB...")
                            withContext(Dispatchers.IO) {
                                DomainBlacklist.reloadFromDatabaseSync(getApplication())
                            }
                        }
                        syncSuccess = true
                    }

                    // Lỗi mạng — còn lần retry
                    result is SyncResult.Error && retryCount < maxRetry -> {
                        retryCount++
                        _statusText.value =
                            "Mạng yếu. Thử lại ($retryCount/$maxRetry)..."
                        delay(2000)
                    }

                    // Hết retry — dùng assets bundled sẵn, vẫn cho vào app.
                    // FIX LỖI 2: Dùng reloadFromDatabaseSync() thay vì init()
                    // để withContext() thực sự chờ filter nạp xong.
                    else -> {
                        _statusText.value = "Không có mạng. Dùng dữ liệu mặc định..."
                        withContext(Dispatchers.IO) {
                            DomainBlacklist.reloadFromDatabaseSync(getApplication()) // ← FIX: thay init()
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector        = Icons.Filled.Security,
            contentDescription = "VShield",
            modifier           = Modifier.size(80.dp),
            tint               = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text       = "VShield",
            style      = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text  = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        LinearProgressIndicator(
            progress  = { progress },
            modifier  = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp),
            color      = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )
    }
}