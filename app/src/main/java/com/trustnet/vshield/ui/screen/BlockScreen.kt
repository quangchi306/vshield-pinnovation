package com.trustnet.vshield.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trustnet.vshield.core.BlockedDomainLog
import kotlinx.coroutines.delay

/**
 * Màn hình tab "Đã chặn" — hiển thị toàn bộ danh sách tên miền vừa bị chặn.
 * Logic bypass được xử lý tại MainActivity để tách biệt với UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(
    entries: List<BlockedDomainLog.BlockedEntry>,
    parentingEnabled: Boolean,
    onBypass: (String) -> Unit
) {
    // Tick mỗi giây để xóa entry hết TTL
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            BlockedDomainLog.tick()
        }
    }

    // Dialog khi Parenting Mode đang bật
    var parentingDialogMsg by rememberSaveable { mutableStateOf<String?>(null) }
    parentingDialogMsg?.let { msg ->
        ParentingBlockedDialog(
            message   = msg,
            onDismiss = { parentingDialogMsg = null }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = "Đã chặn",
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text  = "Tự xóa sau 5 phút · ${entries.size} mục",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->

        // ── Trạng thái rỗng ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = entries.isEmpty(),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            enter = fadeIn(tween(300)),
            exit  = fadeOut(tween(150))
        ) {
            Box(
                modifier         = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier           = Modifier.size(56.dp),
                        tint               = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Text(
                        text  = "Không có gì bị chặn",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text  = "Các tên miền bị chặn sẽ xuất hiện ở đây",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        }

        // Danh sách
        AnimatedVisibility(
            visible = entries.isNotEmpty(),
            modifier = Modifier.padding(paddingValues),
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(150))
        ) {
            LazyColumn(
                modifier        = Modifier.fillMaxSize(),
                contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = entries,
                    key   = { it.domain }
                ) { entry ->
                    BlockedEntryCard(
                        entry    = entry,
                        onBypass = {
                            if (parentingEnabled) {
                                parentingDialogMsg =
                                    "Không thể bỏ qua chặn khi Parenting Mode đang bật.\n\n" +
                                            "Hãy vào Settings và tắt Parenting Mode trước."
                            } else {
                                onBypass(entry.domain)
                            }
                        }
                    )
                }

                // Chú thích cuối danh sách
                item {
                    Text(
                        text     = "Các mục tự động xóa khi hết 5 phút",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}


// Card từng entryD

@Composable
private fun BlockedEntryCard(
    entry: BlockedDomainLog.BlockedEntry,
    onBypass: () -> Unit
) {
    // Đếm ngược giây để progress bar mượt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry.domain) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val timeLeftMs  = (entry.expireAt - now).coerceAtLeast(0L)
    val secondsLeft = (timeLeftMs / 1000).toInt()
    val progress    = timeLeftMs.toFloat() / BlockedDomainLog.TTL_MS.toFloat()

    val isAi        = entry.source == BlockedDomainLog.Source.AI
    val sourceColor = if (isAi) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.error
    val sourceLabel = if (isAi) "AI" else "Blacklist"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Hàng 1: icon + domain + badge + nút bypass
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // Shield icon nhỏ bên trái
                Icon(
                    imageVector        = Icons.Filled.Shield,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                    tint               = sourceColor.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Domain + reason
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = entry.domain,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text     = entry.reason,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Badge nguồn + nút bypass
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Badge AI / Blacklist
                    Surface(
                        color = sourceColor.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text     = sourceLabel,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color    = sourceColor
                        )
                    }

                    // Nút Bỏ qua
                    if (entry.canBypass) {
                        TextButton(
                            onClick        = { onBypass() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier       = Modifier.height(30.dp)
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

            Spacer(modifier = Modifier.height(10.dp))

            // Hàng 2: progress bar TTL + thời gian còn lại
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LinearProgressIndicator(
                    progress   = { progress },
                    modifier   = Modifier
                        .weight(1f)
                        .height(3.dp),
                    color      = sourceColor.copy(alpha = 0.5f),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                )
                Text(
                    text  = formatCountdown(secondsLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

private fun formatCountdown(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "$m:${s.toString().padStart(2, '0')}"
    else "${s}s"
}