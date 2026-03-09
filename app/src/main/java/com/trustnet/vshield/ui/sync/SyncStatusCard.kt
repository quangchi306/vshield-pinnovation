package com.trustnet.vshield.ui.sync

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SyncStatusCard(
    state:     SyncUiState,
    onSyncNow: () -> Unit,
    modifier:  Modifier = Modifier,
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        // Sử dụng tông màu tối dựa trên xanh dương thay vì màu tím cũ
        colors    = CardDefaults.cardColors(containerColor = Color(0xFF112229)),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Security, null,
                    tint     = MaterialTheme.colorScheme.primary, // Thay tím bằng xanh dương #3cabd1
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Blocklist Server", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.weight(1f))
                SyncButton(isSyncing = state.isSyncing, onClick = onSyncNow)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Domain chặn", "%,d".format(state.totalBlocked), MaterialTheme.colorScheme.secondary) // Dùng xanh lá #7dc27f
                StatItem("Version",     "#${state.currentVersion}",        MaterialTheme.colorScheme.primary)   // Dùng xanh dương
                StatItem("Cập nhật",    state.lastSyncDisplay,             Color.White.copy(alpha = 0.7f))
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3B1F1F), RoundedCornerShape(8.dp))
                        .padding(12.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.errorMessage, color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SyncButton(isSyncing: Boolean, onClick: () -> Unit) {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label         = "rot",
    )
    IconButton(onClick = onClick, enabled = !isSyncing) {
        Icon(
            Icons.Default.Sync, "Cập nhật",
            tint     = if (isSyncing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
            modifier = if (isSyncing) Modifier.rotate(rotation) else Modifier,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
    }
}