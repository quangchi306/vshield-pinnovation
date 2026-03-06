package com.trustnet.vshield.ui.screen

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trustnet.vshield.parenting.ParentAction
import com.trustnet.vshield.parenting.ParentingViewModel
import com.trustnet.vshield.ui.parenting.LocalParentGate
import com.trustnet.vshield.ui.parenting.ParentSetPasswordDialog
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onUpdateBlocklist: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val parentGate = LocalParentGate.current

    val parentingVm: ParentingViewModel = viewModel()
    val parentingState by parentingVm.uiState.collectAsState()

    val scope = rememberCoroutineScope()

    var showSetPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var setPasswordError by remember { mutableStateOf<String?>(null) }
    var enableParentingAfterPassword by rememberSaveable { mutableStateOf(false) }

    if (showSetPasswordDialog) {
        ParentSetPasswordDialog(
            isChange = parentingState.hasPassword,
            minLength = ParentingViewModel.MIN_PASSWORD_LENGTH,
            errorMessage = setPasswordError,
            onDismiss = {
                showSetPasswordDialog = false
                setPasswordError = null
                enableParentingAfterPassword = false
            },
            onSubmit = { newPassword ->
                scope.launch {
                    val err = parentingVm.setParentPassword(newPassword)
                    if (err == null) {
                        showSetPasswordDialog = false
                        setPasswordError = null

                        if (enableParentingAfterPassword) {
                            parentingVm.setParentingEnabled(true)
                            enableParentingAfterPassword = false
                        }

                        if (ParentingViewModel.ENABLE_UNLOCK_SESSION) {
                            parentingVm.unlockForWindow()
                        }

                        Toast.makeText(context, "Đã lưu mật khẩu phụ huynh.", Toast.LENGTH_SHORT).show()
                    } else {
                        setPasswordError = err
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Section 1: Network
            item { SectionHeader("NETWORK CONFIGURATION") }

            item {
                SettingItem(
                    icon = Icons.Default.Dns,
                    title = "DNS Server",
                    subtitle = "Current: Cloudflare (1.1.1.1)",
                    onClick = {
                        parentGate.protect(ParentAction.ChangeDns) {
                            // TODO: mở dialog chọn DNS thật sự
                            Toast.makeText(context, "TODO: Mở dialog chọn DNS", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Section 2: Security Filter
            item { SectionHeader("SECURITY FILTER") }

            item {
                SettingItem(
                    icon = Icons.Default.Refresh,
                    title = "Update Blocklist",
                    subtitle = "Tap to fetch latest threats database",
                    onClick = {
                        // Gate theo cấu hình ParentingViewModel.LOCK_BLOCKLIST_UPDATE
                        parentGate.protect(ParentAction.UpdateBlocklist) {
                            onUpdateBlocklist()
                        }
                    }
                )
            }

            item {
                SettingItem(
                    icon = Icons.Default.Shield,
                    title = "Blocklist Version",
                    subtitle = "v2026.01.26 (154,000 domains)",
                    onClick = {}
                )
            }

            // Section 3: Parenting Control
            item { SectionHeader("PARENTING CONTROL") }

            item {
                SettingSwitchItem(
                    icon = if (parentingState.parentingEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                    title = "Parenting Mode",
                    subtitle = if (parentingState.parentingEnabled)
                        "ON - Thao tác nhạy cảm sẽ cần xác minh"
                    else
                        "OFF",
                    checked = parentingState.parentingEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // Bật Parenting: bắt buộc phải có password
                            if (!parentingState.hasPassword) {
                                enableParentingAfterPassword = true
                                showSetPasswordDialog = true
                            } else {
                                parentGate.protect(ParentAction.ToggleParentingMode) {
                                    parentingVm.setParentingEnabled(true)
                                }
                            }
                        } else {
                            // Tắt Parenting: luôn require auth nếu đã có password
                            if (!parentingState.hasPassword) {
                                parentingVm.setParentingEnabled(false)
                            } else {
                                parentGate.protect(ParentAction.ToggleParentingMode) {
                                    parentingVm.setParentingEnabled(false)
                                }
                            }
                        }
                    }
                )
            }

            item {
                SettingItem(
                    icon = Icons.Default.Key,
                    title = if (parentingState.hasPassword) "Change Parent Password" else "Set Parent Password",
                    subtitle = if (parentingState.hasPassword)
                        "Update your parent password"
                    else
                        "Create a password to protect critical actions",
                    onClick = {
                        enableParentingAfterPassword = false
                        if (parentingState.hasPassword) {
                            parentGate.protect(ParentAction.ChangeParentPassword) {
                                showSetPasswordDialog = true
                            }
                        } else {
                            showSetPasswordDialog = true
                        }
                    }
                )
            }

            item {
                val subtitle = when {
                    !parentingState.hasPassword ->
                        "Set a parent password to use unlocked session"
                    parentingState.isUnlocked -> {
                        val until = parentingState.unlockedUntilEpochMs ?: 0L
                        val time = DateFormat.getTimeFormat(context).format(Date(until))
                        "Unlocked until $time"
                    }
                    else ->
                        "Locked. Tap to unlock for 5 minutes"
                }

                SettingItem(
                    icon = if (parentingState.isUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    title = "Parent Unlocked Session",
                    subtitle = subtitle,
                    onClick = {
                        when {
                            !parentingState.hasPassword -> {
                                Toast.makeText(context, "Hãy đặt mật khẩu phụ huynh trước.", Toast.LENGTH_SHORT).show()
                            }
                            parentingState.isUnlocked -> {
                                parentingVm.lockNow()
                                Toast.makeText(context, "Đã khóa lại.", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                parentGate.protect(ParentAction.UnlockSession) {
                                    parentingVm.unlockForWindow()
                                }
                            }
                        }
                    }
                )
            }

            // Section 4: About
            item { SectionHeader("ABOUT") }

            item {
                SettingItem(
                    icon = Icons.Default.Info,
                    title = "V-Shield Version",
                    subtitle = "1.0.0 Stable (Build 2026)",
                    onClick = {}
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}