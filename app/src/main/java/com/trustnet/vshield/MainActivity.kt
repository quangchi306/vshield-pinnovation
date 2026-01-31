package com.trustnet.vshield

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.core.content.ContextCompat
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.ui.screen.HomeScreen
import com.trustnet.vshield.ui.screen.SettingsScreen
import com.trustnet.vshield.ui.theme.VshieldTheme

enum class AppScreen { HOME, SETTINGS }

class MainActivity : ComponentActivity() {

    // 1. Launcher xử lý kết quả khi xin quyền VPN
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                toast("Bạn cần cấp quyền để V-Shield hoạt động.")
            }
        }

    // 2. Launcher xử lý xin quyền thông báo
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) toast("Thông báo bị tắt. Bạn sẽ không thấy trạng thái VPN trên thanh status.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kiểm tra và xin quyền thông báo nếu chạy trên Android 13 (Tiramisu) trở lên
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            VshieldTheme {
                // --- QUẢN LÝ TRẠNG THÁI GIAO DIỆN ---

                // Biến lưu màn hình hiện tại
                var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

                // Lắng nghe dữ liệu từ Service
                val isRunning by VpnStats.isRunning.observeAsState(initial = false)

                // Lắng nghe số lượng quảng cáo đã chặn
                val blockedCount by VpnStats.blockedCount.observeAsState(initial = 0L)

                // Xử lý nút Back cứng trên điện thoại:
                // Nếu đang ở Settings -> Quay về Home.
                // Nếu đang ở Home -> Thoát app
                BackHandler(enabled = currentScreen == AppScreen.SETTINGS) {
                    currentScreen = AppScreen.HOME
                }

                // --- ĐIỀU HƯỚNG MÀN HÌNH ---
                when (currentScreen) {
                    AppScreen.HOME -> {
                        HomeScreen(
                            isConnected = isRunning,
                            blockedCount = blockedCount.toString(),
                            onToggleClick = {
                                if (isRunning) stopVpn() else requestVpnPermissionAndStart()
                            },
                            onSettingsClick = {
                                currentScreen = AppScreen.SETTINGS
                            }
                        )
                    }
                    AppScreen.SETTINGS -> {
                        SettingsScreen(
                            onBackClick = {
                                currentScreen = AppScreen.HOME
                            },
                            onUpdateBlocklist = {
                                updateBlacklist()
                            }
                        )
                    }
                }
            }
        }
    }

    // --- CÁC HÀM LOGIC XỬ LÝ VPN ---

    private fun requestVpnPermissionAndStart() {
        // Kiểm tra xem hệ thống đã cấp quyền VPN?
        val intent = VpnService.prepare(this)
        if (intent != null) {
            //Hộp thoại hỏi người dùng
            vpnPermissionLauncher.launch(intent)
        } else {
            // Chạy
            startVpn()
        }
    }

    private fun startVpn() {
        val i = Intent(this, VShieldVpnService::class.java).apply {
            action = VShieldVpnService.ACTION_START
        }
        // Dùng startForegroundService vì VPN cần chạy ngầm bền vững
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopVpn() {
        val i = Intent(this, VShieldVpnService::class.java).apply {
            action = VShieldVpnService.ACTION_STOP
        }
        startService(i)
    }

    private fun updateBlacklist() {
        toast("Đang tải dữ liệu chặn mới nhất...")
        DomainBlacklist.updateFromUrl(DomainBlacklist.DEFAULT_REMOTE_URL) { success, msg ->
            runOnUiThread {
                if (success) {
                    toast("Cập nhật thành công! $msg")
                } else {
                    toast("Lỗi cập nhật: $msg")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}