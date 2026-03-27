package com.trustnet.vshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import com.trustnet.vshield.core.BlockedDomainLog
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.parenting.ParentingViewModel
import com.trustnet.vshield.ui.parenting.ParentGateHost
import com.trustnet.vshield.ui.screen.BlockedScreen
import com.trustnet.vshield.ui.screen.HomeScreen
import com.trustnet.vshield.ui.screen.SettingsScreen
import com.trustnet.vshield.ui.screen.SplashScreen
import com.trustnet.vshield.ui.screen.SplashViewModel
import com.trustnet.vshield.ui.theme.VshieldTheme

// Định nghĩa các tab trong bottom nav
private enum class AppTab(
    val label: String,
    val icon: ImageVector
) {
    HOME(label = "Home",      icon = Icons.Filled.Home),
    BLOCKED(label = "Đã chặn", icon = Icons.Filled.Shield)
}

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
    ) { _ ->
        prepareAndStartVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
        DomainBlacklist.blockAdult    = prefs.getBoolean("BLOCK_ADULT",    true)
        DomainBlacklist.blockGambling = prefs.getBoolean("BLOCK_GAMBLING", true)

        // Chặn nút Back khi VPN đang bật — hiện dialog xác nhận
        onBackPressedDispatcher.addCallback(this) {
            if (VpnStats.isRunning.value == true) {
                showExitBlockedDialog()
            } else {
                // VPN tắt → cho phép thoát bình thường
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        setContent {
            VshieldTheme {
                val splashVm   = viewModel<SplashViewModel>()
                val isReady    by splashVm.isReady.collectAsState()
                val progress   by splashVm.progress.collectAsState()
                val statusText by splashVm.statusText.collectAsState()

                if (!isReady) {
                    SplashScreen(progress = progress, statusText = statusText)
                } else {
                    ParentGateHost {
                        val isRunning      by VpnStats.isRunning.observeAsState(initial = false)
                        val blockedCount   by VpnStats.blockedCount.observeAsState(initial = 0L)
                        val blockedEntries by BlockedDomainLog.entries.collectAsState()

                        val parentingVm    = viewModel<ParentingViewModel>()
                        val parentingState by parentingVm.uiState.collectAsState()

                        // Tab hiện tại và trạng thái Settings
                        var selectedTab  by rememberSaveable { mutableIntStateOf(AppTab.HOME.ordinal) }
                        var showSettings by rememberSaveable { mutableStateOf(false) }

                        // Màn hình Settings phủ toàn bộ (không có bottom nav)
                        if (showSettings) {
                            SettingsScreen(
                                onBackClick         = { showSettings = false },
                                isProtectionEnabled = isRunning,
                                onForceProtectionOn = {
                                    if (!isRunning) checkPermissionsAndStart()
                                }
                            )
                        } else {
                            Scaffold(
                                bottomBar = {
                                    AppBottomNavBar(
                                        selectedIndex = selectedTab,
                                        blockedCount  = blockedEntries.size,
                                        onTabSelected = { selectedTab = it }
                                    )
                                }
                            ) { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                ) {
                                    when (AppTab.entries[selectedTab]) {

                                        AppTab.HOME -> HomeScreen(
                                            isConnected     = isRunning,
                                            blockedCount    = blockedCount.toString(),
                                            onToggleClick   = {
                                                if (isRunning) stopVpnService()
                                                else checkPermissionsAndStart()
                                            },
                                            onSettingsClick = { showSettings = true }
                                        )

                                        AppTab.BLOCKED -> BlockedScreen(
                                            entries          = blockedEntries,
                                            parentingEnabled = parentingState.parentingEnabled,
                                            onBypass         = { domain ->
                                                DomainBlacklist.addTemporaryAllow(domain)
                                                BlockedDomainLog.bypass(domain)
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Đã cho phép: $domain. Vui lòng tải lại trang!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Khi nhấn Home trong khi VPN đang bật → nhắc nhở user
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (VpnStats.isRunning.value == true) {
            Toast.makeText(
                this,
                "VShield vẫn đang bảo vệ. Tắt bảo vệ trong app trước khi đóng.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Khi user swipe app khỏi recents → onDestroy() được gọi với isFinishing = true
    // Dừng VPN service sạch sẽ để tránh filter bị lỗi khi mở lại app
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && VpnStats.isRunning.value == true) {
            stopVpnService()
        }
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    private fun showExitBlockedDialog() {
        AlertDialog.Builder(this)
            .setTitle("VShield đang hoạt động")
            .setMessage(
                "Chế độ bảo vệ đang bật.\n\n" +
                        "Vui lòng tắt bảo vệ trong app trước khi thoát " +
                        "để tránh lỗi bộ lọc khi mở lại."
            )
            .setPositiveButton("Tắt bảo vệ & Thoát") { _, _ ->
                stopVpnService()
                // Delay nhỏ để service kịp dừng trước khi finish
                window.decorView.postDelayed({ finish() }, 300)
            }
            .setNegativeButton("Ở lại", null)
            .show()
    }

    // ── VPN helpers ───────────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        prepareAndStartVpn()
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpnService()
    }

    private fun startVpnService() {
        startService(
            Intent(this, VShieldVpnService::class.java).apply {
                action = VShieldVpnService.ACTION_START
            }
        )
    }

    private fun stopVpnService() {
        startService(
            Intent(this, VShieldVpnService::class.java).apply {
                action = VShieldVpnService.ACTION_STOP
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Bottom Navigation Bar
// ─────────────────────────────────────────────────────────────────────────────

@androidx.compose.runtime.Composable
private fun AppBottomNavBar(
    selectedIndex: Int,
    blockedCount: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        AppTab.entries.forEachIndexed { index, tab ->
            val selected = index == selectedIndex

            val iconTint by animateColorAsState(
                targetValue   = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                animationSpec = tween(200),
                label         = "tabTint_$index"
            )

            NavigationBarItem(
                selected = selected,
                onClick  = { onTabSelected(index) },
                icon = {
                    if (tab == AppTab.BLOCKED && blockedCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(
                                        text  = if (blockedCount > 99) "99+" else blockedCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector        = tab.icon,
                                contentDescription = tab.label,
                                tint               = iconTint
                            )
                        }
                    } else {
                        Icon(
                            imageVector        = tab.icon,
                            contentDescription = tab.label,
                            tint               = iconTint
                        )
                    }
                },
                label = {
                    Text(
                        text       = tab.label,
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color      = iconTint
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}