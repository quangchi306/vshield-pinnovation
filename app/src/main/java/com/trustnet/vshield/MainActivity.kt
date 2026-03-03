package com.trustnet.vshield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.ui.screen.HomeScreen
import com.trustnet.vshield.ui.screen.SettingsScreen
import com.trustnet.vshield.ui.theme.VshieldTheme

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Cần cấp quyền để chạy V-Shield", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load settings (filters)
        val prefs = getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
        DomainBlacklist.blockAdult = prefs.getBoolean("BLOCK_ADULT", true)
        DomainBlacklist.blockGambling = prefs.getBoolean("BLOCK_GAMBLING", true)

        setContent {
            VshieldTheme {
                val context = LocalContext.current
                val isRunning by VpnStats.isRunning.observeAsState(initial = false)
                val blockedCount by VpnStats.blockedCount.observeAsState(initial = 0L)

                var showSettings by rememberSaveable { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        onBackClick = { showSettings = false },
                        onUpdateBlocklist = {
                            // TODO: gắn logic update blocklist thật sự (download/parse/replace)
                            Toast.makeText(context, "Đang cập nhật blocklist...", Toast.LENGTH_SHORT).show()

                            // Nếu bạn muốn “buộc VPN reload” sau khi update:
                            // stopVpnService()
                            // startVpnService()
                        }
                    )
                } else {
                    HomeScreen(
                        isConnected = isRunning,
                        blockedCount = blockedCount.toString(),
                        onToggleClick = {
                            if (isRunning) stopVpnService()
                            else checkPermissionsAndStart()
                        },
                        onSettingsClick = {
                            showSettings = true
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        // Bước 1: Kiểm tra quyền Hiển thị trên ứng dụng khác
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác' để hiện cảnh báo chặn web",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Bước 2: Chuẩn bị VPN
        prepareAndStartVpn()
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpnService()
    }

    private fun startVpnService() {
        val intent = Intent(this, VShieldVpnService::class.java)
        intent.action = VShieldVpnService.ACTION_START
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, VShieldVpnService::class.java)
        intent.action = VShieldVpnService.ACTION_STOP
        startService(intent)
    }
}