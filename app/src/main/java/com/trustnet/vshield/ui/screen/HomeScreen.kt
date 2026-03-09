package com.trustnet.vshield.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.parenting.ParentAction
import com.trustnet.vshield.parenting.ParentingViewModel
import com.trustnet.vshield.ui.parenting.LocalParentGate

@Composable
fun HomeScreen(
    isConnected: Boolean,
    blockedCount: String,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val gate = LocalParentGate.current

    val parentingVm: ParentingViewModel = viewModel()
    val parentingState by parentingVm.uiState.collectAsState()

    var isAdultBlocked by remember { mutableStateOf(DomainBlacklist.blockAdult) }
    var isGamblingBlocked by remember { mutableStateOf(DomainBlacklist.blockGambling) }
    var blockedDialogMessage by rememberSaveable { mutableStateOf<String?>(null) }

    blockedDialogMessage?.let { message ->
        ParentingBlockedDialog(
            message = message,
            onDismiss = { blockedDialogMessage = null }
        )
    }

    LaunchedEffect(parentingState.parentingEnabled, isConnected) {
        if (parentingState.parentingEnabled) {
            // Ép 2 filter luôn bật khi Parenting Mode bật
            if (!isAdultBlocked || !isGamblingBlocked) {
                isAdultBlocked = true
                isGamblingBlocked = true
                enforceParentingFilters(context)
            }

            // Ép Protection luôn bật khi Parenting Mode bật
            if (!isConnected) {
                onToggleClick()
                Toast.makeText(
                    context,
                    "Parenting Mode đang bật nên VShield Protection được tự động bật.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(500),
        label = "bgColor"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isConnected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = tween(500),
        label = "btnColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isConnected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = tween(500),
        label = "iconColor"
    )

    Scaffold(
        containerColor = backgroundColor,
        topBar = { HomeTopBar(isConnected, onSettingsClick) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(0.5f)
            ) {
                ConnectButton(
                    isConnected = isConnected,
                    buttonColor = buttonColor,
                    iconColor = iconColor,
                    onClick = {
                        when {
                            parentingState.parentingEnabled && isConnected -> {
                                blockedDialogMessage =
                                    "Không thể tắt VShield Protection ở màn hình Home khi Parenting Mode đang bật.\n\n" +
                                            "Nếu muốn thay đổi, hãy vào Settings > Parenting Control."
                            }

                            parentingState.parentingEnabled && !isConnected -> {
                                blockedDialogMessage =
                                    "Parenting Mode yêu cầu VShield Protection luôn ở trạng thái bật."
                                onToggleClick()
                            }

                            else -> {
                                val action = if (isConnected) {
                                    ParentAction.ToggleProtectionOff
                                } else {
                                    ParentAction.ToggleProtectionOn
                                }

                                gate.protect(action) {
                                    onToggleClick()
                                }
                            }
                        }
                    }
                )
            }

            StatusText(isConnected)

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Tùy chọn chặn",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilterSwitchRow(
                        label = "Web người lớn (Adult)",
                        checked = isAdultBlocked,
                        onCheckedChange = { checked ->
                            if (parentingState.parentingEnabled) {
                                blockedDialogMessage =
                                    "Không thể thay đổi bộ lọc Adult khi Parenting Mode đang bật.\n\n" +
                                            "Hãy vào Settings và tắt Parenting Mode trước."
                            } else {
                                gate.protect(ParentAction.ChangeFilterConfig) {
                                    isAdultBlocked = checked
                                    handleSettingChange(context, checked, isGamblingBlocked)
                                }
                            }
                        }
                    )

                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = Color.Gray.copy(alpha = 0.2f)
                    )

                    FilterSwitchRow(
                        label = "Cờ bạc (Gambling)",
                        checked = isGamblingBlocked,
                        onCheckedChange = { checked ->
                            if (parentingState.parentingEnabled) {
                                blockedDialogMessage =
                                    "Không thể thay đổi bộ lọc Gambling khi Parenting Mode đang bật.\n\n" +
                                            "Hãy vào Settings và tắt Parenting Mode trước."
                            } else {
                                gate.protect(ParentAction.ChangeFilterConfig) {
                                    isGamblingBlocked = checked
                                    handleSettingChange(context, isAdultBlocked, checked)
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            StatsDashboard(
                isConnected = isConnected,
                blockedCount = blockedCount
            )
        }
    }
}

fun handleSettingChange(
    context: Context,
    blockAdult: Boolean,
    blockGambling: Boolean
) {
    val prefs = context.getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("BLOCK_ADULT", blockAdult)
        .putBoolean("BLOCK_GAMBLING", blockGambling)
        .apply()

    DomainBlacklist.blockAdult = blockAdult
    DomainBlacklist.blockGambling = blockGambling

    val intent = Intent(context, VShieldVpnService::class.java).apply {
        action = VShieldVpnService.ACTION_STOP
    }
    context.startService(intent)

    Toast.makeText(
        context,
        "Đã lưu cài đặt. Vui lòng bật lại VPN!",
        Toast.LENGTH_SHORT
    ).show()
}

fun enforceParentingFilters(context: Context) {
    val prefs = context.getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)

    val currentAdult = prefs.getBoolean("BLOCK_ADULT", DomainBlacklist.blockAdult)
    val currentGambling = prefs.getBoolean("BLOCK_GAMBLING", DomainBlacklist.blockGambling)

    if (currentAdult && currentGambling) return

    handleSettingChange(
        context = context,
        blockAdult = true,
        blockGambling = true
    )
}

@Composable
fun FilterSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun HomeTopBar(
    isConnected: Boolean,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "VShield Home",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isConnected) "Đang bảo vệ" else "Đã tắt",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings"
            )
        }
    }
}

@Composable
fun ConnectButton(
    isConnected: Boolean,
    buttonColor: Color,
    iconColor: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isConnected) 1.05f else 1.0f,
        animationSpec = tween(300),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        buttonColor.copy(alpha = 0.8f),
                        buttonColor
                    )
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isConnected) {
                Icons.Filled.Security
            } else {
                Icons.Filled.PowerSettingsNew
            },
            contentDescription = "Connect",
            tint = iconColor,
            modifier = Modifier.size(64.dp)
        )
    }
}

@Composable
fun StatusText(isConnected: Boolean) {
    Text(
        text = if (isConnected) "Hệ thống đang chạy ngầm" else "Nhấn nút để bắt đầu",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

@Composable
fun StatsDashboard(
    isConnected: Boolean,
    blockedCount: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = blockedCount,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Đã chặn",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ParentingBlockedDialog(
    title: String = "Bị khóa bởi Parenting Mode",
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Đã hiểu")
            }
        }
    )
}