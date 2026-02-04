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

@Composable
fun HomeScreen(
    isConnected: Boolean,
    blockedCount: String,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current

    // State quản lý nút gạt (Lấy giá trị thực tế từ biến Static)
    var isAdultBlocked by remember { mutableStateOf(DomainBlacklist.blockAdult) }
    var isGamblingBlocked by remember { mutableStateOf(DomainBlacklist.blockGambling) }

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

                    // 1. Chặn Adult
                    FilterSwitchRow(
                        label = "Web người lớn (Adult)",
                        checked = isAdultBlocked,
                        onCheckedChange = { checked ->
                            isAdultBlocked = checked
                            // Gọi hàm xử lý thay đổi
                            handleSettingChange(context, checked, isGamblingBlocked)
                        }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.2f))

                    // 2. Chặn Gambling
                    FilterSwitchRow(
                        label = "Cờ bạc (Gambling)",
                        checked = isGamblingBlocked,
                        onCheckedChange = { checked ->
                            isGamblingBlocked = checked
                            // Gọi hàm xử lý thay đổi
                            handleSettingChange(context, isAdultBlocked, checked)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            StatsDashboard(isConnected, blockedCount)
        }
    }
}

// --- HÀM XỬ LÝ LOGIC MỚI ---
fun handleSettingChange(context: Context, blockAdult: Boolean, blockGambling: Boolean) {
    // 1. Lưu cấu hình vào SharedPreferences
    val prefs = context.getSharedPreferences("VShieldPrefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("BLOCK_ADULT", blockAdult)
        .putBoolean("BLOCK_GAMBLING", blockGambling)
        .apply()

    // 2. Cập nhật biến static để lưu trạng thái
    DomainBlacklist.blockAdult = blockAdult
    DomainBlacklist.blockGambling = blockGambling

    // 3. QUAN TRỌNG: Gửi lệnh STOP service (Tắt VPN)
    // Thay vì gửi lệnh UPDATE, ta gửi lệnh STOP để buộc VPN ngắt kết nối
    val intent = Intent(context, VShieldVpnService::class.java)
    intent.action = VShieldVpnService.ACTION_STOP
    context.startService(intent)

    // 4. Thông báo cho người dùng
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
            // Logic Icon: Nếu đang kết nối -> Khiên. Nếu tắt -> Nút nguồn.
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