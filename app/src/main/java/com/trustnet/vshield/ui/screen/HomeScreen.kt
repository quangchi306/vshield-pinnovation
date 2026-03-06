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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.DomainBlacklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.trustnet.vshield.vpn.dns.DnsTestClient

@Composable
fun HomeScreen(
    isConnected: Boolean,
    blockedCount: String,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current

    // State quản lý nút gạt
    var isAdultBlocked by remember { mutableStateOf(DomainBlacklist.blockAdult) }
    var isGamblingBlocked by remember { mutableStateOf(DomainBlacklist.blockGambling) }

    // State hiển thị hộp thoại Test DNS
    var showTestDialog by remember { mutableStateOf(false) }

    // Hiệu ứng màu nền
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(500), label = "bgColor"
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = tween(500), label = "btnColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        animationSpec = tween(500), label = "iconColor"
    )

    if (showTestDialog) {
        DnsTestDialog(onDismiss = { showTestDialog = false })
    }

    Scaffold(
        containerColor = backgroundColor,
        topBar = { TopAppBar(isConnected, onSettingsClick) }
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
                    onClick = onToggleClick
                )
            }

            StatusText(isConnected)

            Spacer(modifier = Modifier.height(16.dp))

            // --- BẢNG ĐIỀU KHIỂN BỘ LỌC ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Tùy chọn chặn",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    FilterSwitchRow(
                        label = "Web người lớn (Adult)",
                        checked = isAdultBlocked,
                        onCheckedChange = { checked ->
                            isAdultBlocked = checked
                            handleSettingChange(context, checked, isGamblingBlocked)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.2f))

                    FilterSwitchRow(
                        label = "Cờ bạc (Gambling)",
                        checked = isGamblingBlocked,
                        onCheckedChange = { checked ->
                            isGamblingBlocked = checked
                            handleSettingChange(context, isAdultBlocked, checked)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            StatsDashboard(isConnected, blockedCount)

            Spacer(modifier = Modifier.height(16.dp))

            // --- NÚT GỌI HỘP THOẠI TEST DNS ---
            OutlinedButton(
                onClick = {
                    if (isConnected) {
                        showTestDialog = true
                    } else {
                        Toast.makeText(context, "Vui lòng bật V-Shield trước khi Test", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Dns, contentDescription = "Test DNS")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Công cụ Test DNS")
            }
        }
    }
}

// ==========================================
// COMPOSABLE HỘP THOẠI TEST DNS AN TOÀN TRÊN LUỒNG NỀN
// ==========================================
@Composable
fun DnsTestDialog(onDismiss: () -> Unit) {
    var domain by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test DNS Filter", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Nhập domain để kiểm tra xem hệ thống có đang chặn nó hay không.")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("vd: example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (result.isNotEmpty()) {
                    Text(
                        text = "Kết quả:\n$result",
                        fontWeight = FontWeight.Bold,
                        color = if (result.contains("BỊ CHẶN")) Color.Red else Color(0xFF007700)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (domain.isNotBlank()) {
                        isTesting = true
                        result = ""
                        // ⚡ Kích hoạt Coroutine chạy ngầm để gửi UDP Socket
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val res = DnsTestClient.testA(domain)
                                // Trả kết quả về luồng UI
                                withContext(Dispatchers.Main) {
                                    result = res
                                    isTesting = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    result = "Lỗi kết nối: ${e.message}\n(Đảm bảo VPN đang bật)"
                                    isTesting = false
                                }
                            }
                        }
                    }
                }
            ) {
                Text("Kiểm tra")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}

// --- CÁC HÀM CŨ GIỮ NGUYÊN BÊN DƯỚI ---

fun handleSettingChange(context: Context, blockAdult: Boolean, blockGambling: Boolean) {
    val prefs = context.getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("BLOCK_ADULT", blockAdult)
        .putBoolean("BLOCK_GAMBLING", blockGambling)
        .apply()

    DomainBlacklist.blockAdult = blockAdult
    DomainBlacklist.blockGambling = blockGambling

    val intent = Intent(context, VShieldVpnService::class.java)
    intent.action = VShieldVpnService.ACTION_STOP
    context.startService(intent)

    Toast.makeText(context, "Đã lưu cài đặt. Vui lòng bật lại VPN!", Toast.LENGTH_SHORT).show()
}

@Composable
fun FilterSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TopAppBar(isConnected: Boolean, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("V-Shield Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (isConnected) "Đang bảo vệ" else "Đã tắt",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
fun ConnectButton(isConnected: Boolean, buttonColor: Color, iconColor: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isConnected) 1.05f else 1.0f, label = "scale")
    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(buttonColor.copy(alpha = 0.8f), buttonColor)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Filled.Security else Icons.Filled.PowerSettingsNew,
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
fun StatsDashboard(isConnected: Boolean, blockedCount: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(blockedCount, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Đã chặn", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}