package com.trustnet.vshield

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.ui.parenting.ParentGateHost
import com.trustnet.vshield.ui.screen.HomeScreen
import com.trustnet.vshield.ui.screen.SettingsScreen
import com.trustnet.vshield.ui.screen.SplashScreen
import com.trustnet.vshield.ui.screen.SplashViewModel
import com.trustnet.vshield.ui.theme.VshieldTheme

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Cần cấp quyền để chạy Vshield", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        prepareAndStartVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
        DomainBlacklist.blockAdult = prefs.getBoolean("BLOCK_ADULT", true)
        DomainBlacklist.blockGambling = prefs.getBoolean("BLOCK_GAMBLING", true)

        setContent {
            VshieldTheme {
                val splashVm = viewModel<SplashViewModel>()

                val isReady by splashVm.isReady.collectAsState()
                val progress by splashVm.progress.collectAsState()
                val statusText by splashVm.statusText.collectAsState()

                if (!isReady) {
                    // ĐÃ XÓA CÁC THAM SỐ HAS ERROR
                    SplashScreen(
                        progress = progress,
                        statusText = statusText
                    )
                }
                else {
                    ParentGateHost {
                        val context = LocalContext.current
                        val isRunning by VpnStats.isRunning.observeAsState(initial = false)
                        val blockedCount by VpnStats.blockedCount.observeAsState(initial = 0L)

                        var showSettings by rememberSaveable { mutableStateOf(false) }

                        if (showSettings) {
                            SettingsScreen(
                                onBackClick = { showSettings = false },
                                isProtectionEnabled = isRunning,
                                onForceProtectionOn = {
                                    if (!isRunning) checkPermissionsAndStart()
                                }
                            )
                        } else {
                            HomeScreen(
                                isConnected = isRunning,
                                blockedCount = blockedCount.toString(),
                                onToggleClick = {
                                    if (isRunning) stopVpnService() else checkPermissionsAndStart()
                                },
                                onSettingsClick = { showSettings = true }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        prepareAndStartVpn()
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, VShieldVpnService::class.java).apply {
            action = VShieldVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, VShieldVpnService::class.java).apply {
            action = VShieldVpnService.ACTION_STOP
        }
        startService(intent)
    }
}