package com.trustnet.vshield.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Hàm chính của màn hình, nhận dữ liệu từ MainActivity truyền vào
@Composable
fun HomeScreen(
    isConnected: Boolean,           // Trạng thái VPN (Bật/Tắt)
    blockedCount: String,           // Số lượng đã chặn
    onToggleClick: () -> Unit,      // Hàm xử lý khi bấm nút to
    onSettingsClick: () -> Unit     // Hàm xử lý khi bấm nút cài đặt
) {
    // Animation chuyển màu nền khi Bật/Tắt
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(500), label = "bgColor"
    )

    // Animation chuyển màu nút bấm
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
        topBar = {
            TopAppBar(isConnected, onSettingsClick)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Phần 1: Nút bấm trung tâm (Power Button)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f)
            ) {
                ConnectButton(
                    isConnected = isConnected,
                    buttonColor = buttonColor,
                    iconColor = iconColor,
                    onClick = onToggleClick
                )
            }

            // Phần 2: Dòng chữ trạng thái (Connected/Disconnected)
            StatusText(isConnected)

            Spacer(modifier = Modifier.height(32.dp))

            // Phần 3: Bảng thống kê (Blocked / Status)
            StatsDashboard(isConnected, blockedCount)
        }
    }
}

@Composable
fun TopAppBar(isConnected: Boolean, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "V-Shield",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isConnected) "System Protected" else "Protection Disabled",
                style = MaterialTheme.typography.labelMedium,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    // Hiệu ứng phình to nhẹ khi đang bật
    val scale by animateFloatAsState(
        targetValue = if (isConnected) 1.05f else 1.0f,
        label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(buttonColor.copy(alpha = 0.8f), buttonColor)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null // Tắt hiệu ứng ripple mặc định
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Toggle VPN",
                modifier = Modifier.size(80.dp),
                tint = iconColor
            )
        }
    }
}

@Composable
fun StatusText(isConnected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isConnected) "CONNECTED" else "DISCONNECTED",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (isConnected) "Traffic is being filtered" else "Tap to secure connection",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatsDashboard(isConnected: Boolean, blockedCount: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem(
                icon = Icons.Filled.Shield,
                label = "Blocked",
                value = if (blockedCount == "0" && !isConnected) "--" else blockedCount,
                color = MaterialTheme.colorScheme.primary
            )
            StatItem(
                icon = Icons.Outlined.History,
                label = "Status",
                value = if (isConnected) "Active" else "Idle",
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
fun StatItem(icon: ImageVector, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    HomeScreen(isConnected = true, blockedCount = "1024", {}, {})
}