package com.trustnet.vshield.ui.screen

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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    isProtectionEnabled: Boolean,
    onForceProtectionOn: () -> Unit
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
                            enforceParentingFilters(context)

                            // Lưu vào SharedPreferences cho Service đọc
                            context.getSharedPreferences("VShieldPrefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("PARENTING_MODE", true).apply()

                            if (!isProtectionEnabled) {
                                onForceProtectionOn()
                            }

                            enableParentingAfterPassword = false

                            Toast.makeText(
                                context,
                                "Parenting Mode đã bật. VShield Protection, Adult và Gambling được khóa ở trạng thái ON.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        if (ParentingViewModel.ENABLE_UNLOCK_SESSION) {
                            parentingVm.unlockForWindow()
                        }

                        Toast.makeText(
                            context,
                            "Đã lưu mật khẩu phụ huynh.",
                            Toast.LENGTH_SHORT
                        ).show()
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
            item { SectionHeader("NETWORK CONFIGURATION") }

            item {
                SettingItem(
                    icon = Icons.Default.Dns,
                    title = "DNS Server",
                    subtitle = "Current: Cloudflare (1.1.1.1)",
                    onClick = {
                        parentGate.protect(ParentAction.ChangeDns) {
                            Toast.makeText(
                                context,
                                "TODO: Mở dialog chọn DNS",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            item { SectionHeader("PARENTING CONTROL") }

            item {
                SettingSwitchItem(
                    icon = if (parentingState.parentingEnabled) {
                        Icons.Default.Lock
                    } else {
                        Icons.Default.LockOpen
                    },
                    title = "Parenting Mode",
                    subtitle = if (parentingState.parentingEnabled)
                        "ON - VShield Protection, Adult và Gambling sẽ bị ép bật"
                    else
                        "OFF",
                    checked = parentingState.parentingEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (!parentingState.hasPassword) {
                                enableParentingAfterPassword = true
                                showSetPasswordDialog = true
                            } else {
                                parentGate.protect(ParentAction.ToggleParentingMode) {
                                    parentingVm.setParentingEnabled(true)
                                    enforceParentingFilters(context)

                                    // Lưu vào SharedPreferences cho Service
                                    context.getSharedPreferences("VShieldPrefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("PARENTING_MODE", true).apply()

                                    if (!isProtectionEnabled) {
                                        onForceProtectionOn()
                                    }

                                    Toast.makeText(
                                        context,
                                        "Parenting Mode đã bật. VShield Protection, Adult và Gambling được ép ON.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            if (!parentingState.hasPassword) {
                                parentingVm.setParentingEnabled(false)
                                context.getSharedPreferences("VShieldPrefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("PARENTING_MODE", false).apply()
                            } else {
                                parentGate.protect(ParentAction.ToggleParentingMode) {
                                    parentingVm.setParentingEnabled(false)
                                    context.getSharedPreferences("VShieldPrefs", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("PARENTING_MODE", false).apply()
                                }
                            }
                        }
                    }
                )
            }

            item {
                SettingItem(
                    icon = Icons.Default.Key,
                    title = if (parentingState.hasPassword) {
                        "Change Parent Password"
                    } else {
                        "Set Parent Password"
                    },
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

            item { SectionHeader("ABOUT") }

            item {
                SettingItem(
                    icon = Icons.Default.Info,
                    title = "VShield Version",
                    subtitle = "1.0.0 Stable",
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}