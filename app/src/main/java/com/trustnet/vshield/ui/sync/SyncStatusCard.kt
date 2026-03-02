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

/**
 * Thêm vào HomeScreen:
 *
 *   val syncViewModel: SyncViewModel = viewModel()
 *   val syncState by syncViewModel.state.collectAsState()
 *
 *   SyncStatusCard(
 *       state     = syncState,
 *       onSyncNow = { syncViewModel.syncNow() },
 *   )
 */
@Composable
fun SyncStatusCard(
    state:     SyncUiState,
    onSyncNow: () -> Unit,
    modifier:  Modifier = Modifier,
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Security, null,
                    tint     = Color(0xFF7C3AED),
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
                StatItem("Domain chặn", "%,d".format(state.totalBlocked), Color(0xFF10B981))
                StatItem("Version",     "#${state.currentVersion}",        Color(0xFF60A5FA))
                StatItem("Cập nhật",    state.lastSyncDisplay,             Color(0xFFA78BFA))
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
            tint     = if (isSyncing) Color(0xFF7C3AED) else Color.White.copy(alpha = 0.7f),
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

@Composable
fun ReportDomainDialog(domain: String, onDismiss: () -> Unit, onReport: (String, String) -> Unit) {
    var selectedCategory by remember { mutableStateOf("phishing") }
    val categories = listOf(
        "phishing" to "Phishing / Lừa đảo",
        "malware"  to "Malware / Virus",
        "gambling" to "Cờ bạc",
        "adult"    to "Nội dung người lớn",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Báo cáo domain", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(domain, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp))
                Text("Loại vi phạm:", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                categories.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        RadioButton(selected = selectedCategory == value,
                            onClick = { selectedCategory = value })
                        Text(label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onReport(domain, selectedCategory); onDismiss() }) {
                Text("Gửi báo cáo")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}
