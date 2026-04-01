package com.trustnet.vshield.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustnet.vshield.core.BlockedDomainLog
import kotlinx.coroutines.delay

/**
 * Panel danh sách các tên miền vừa bị chặn.
 * Đặt trong HomeScreen — hiện khi danh sách không rỗng, ẩn khi rỗng.
 *
 * @param entries   Danh sách entry từ BlockedDomainLog.entries (collectAsState)
 * @param onBypass  Callback khi người dùng bấm "Bỏ qua" cho 1 entry
 */
@Composable
fun BlockedDomainsPanel(
    entries: List<BlockedDomainLog.BlockedEntry>,
    onBypass: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Tick mỗi giây để BlockedDomainLog xóa các entry hết TTL → StateFlow emit → recompose
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            BlockedDomainLog.tick()
        }
    }

    AnimatedVisibility(
        visible = entries.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 4 },
        exit  = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 4 }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vừa bị chặn",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${entries.size} mục",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }

            // Danh sách — giới hạn chiều cao tối đa 240dp để không đẩy layout
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = entries,
                    key   = { it.domain }   // key ổn định để animate item đúng
                ) { entry ->
                    BlockedEntryItem(
                        entry    = entry,
                        onBypass = onBypass
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Item card cho từng entry
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BlockedEntryItem(
    entry: BlockedDomainLog.BlockedEntry,
    onBypass: (String) -> Unit
) {
    // Cập nhật lại thời gian còn lại mỗi giây (tick đã gọi BlockedDomainLog.tick(),
    // nhưng chúng ta cần re-read expireAt để progress bar chạy mượt)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry.domain) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val timeLeftMs   = (entry.expireAt - now).coerceAtLeast(0L)
    val secondsLeft  = (timeLeftMs / 1000).toInt()
    val progress     = timeLeftMs.toFloat() / BlockedDomainLog.TTL_MS.toFloat()

    val isAi         = entry.source == BlockedDomainLog.Source.AI
    val sourceColor  = if (isAi) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.error
    val sourceLabel  = if (isAi) "AI" else "Blacklist"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // Hàng trên: domain + badge + nút bypass
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment    = Alignment.Top
            ) {
                // Tên domain + lý do
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = entry.domain,
                        style    = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text  = entry.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge nguồn chặn
                    Surface(
                        color = sourceColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text     = sourceLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = sourceColor
                        )
                    }

                    // Nút bypass
                    if (entry.canBypass) {
                        TextButton(
                            onClick         = { onBypass(entry.domain) },
                            contentPadding  = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier        = Modifier.height(28.dp)
                        ) {
                            Text(
                                text  = "Bỏ qua",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Hàng dưới: progress bar TTL + đếm ngược
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress  = { progress },
                    modifier  = Modifier
                        .weight(1f)
                        .height(3.dp),
                    color      = sourceColor.copy(alpha = 0.55f),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
                Text(
                    text  = formatCountdown(secondsLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** "4:59", "0:30", "5s" */
private fun formatCountdown(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "$m:${s.toString().padStart(2, '0')}"
    else "${s}s"
}