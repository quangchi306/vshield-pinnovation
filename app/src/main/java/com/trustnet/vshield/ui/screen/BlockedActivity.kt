package com.trustnet.vshield.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.ui.theme.VshieldTheme

class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lấy domain bị chặn
        val domain = intent.getStringExtra("BLOCKED_DOMAIN") ?: "Unknown Website"

        setContent {
            VshieldTheme {
                BlockedScreen(
                    domain = domain,
                    onGoBack = {
                        // Nút An toàn: Mở Google để thoát khỏi web đen
                        openBrowser("https://www.google.com")
                    },
                    onBypass = {
                        // --- LOGIC MỚI: TIẾP TỤC TRUY CẬP ---

                        // 1. Gửi lệnh Whitelist (Cho phép 5 phút) về Service
                        val intent = Intent(this, VShieldVpnService::class.java)
                        intent.action = VShieldVpnService.ACTION_ALLOW_DOMAIN
                        intent.putExtra(VShieldVpnService.EXTRA_DOMAIN, domain)
                        startService(intent)

                        // 2. Mở trình duyệt và quay lại trang web đó
                        // Lưu ý: DNS chỉ biết domain, không biết path, nên ta về trang chủ https
                        val targetUrl = "https://$domain"
                        openBrowser(targetUrl)
                    }
                )
            }
        }
    }

    // Hàm tiện ích để mở trình duyệt
    private fun openBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(browserIntent)

            // Đóng màn hình cảnh báo ngay lập tức
            finishAndRemoveTask()
        } catch (e: Exception) {
            Toast.makeText(this, "Không tìm thấy trình duyệt web!", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }
}

@Composable
fun BlockedScreen(domain: String, onGoBack: () -> Unit, onBypass: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB00020)) // Nền đỏ cảnh báo
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Warning",
            tint = Color.White,
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Phát hiện mối nguy hiểm!",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "V-Shield đã chặn kết nối tới:",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center
        )

        Text(
            text = domain,
            style = MaterialTheme.typography.headlineSmall, // Font to hơn cho domain
            color = Color.Yellow,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(vertical = 12.dp)
                .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Text(
            text = "Trang web này có thể chứa nội dung lừa đảo, cờ bạc hoặc phần mềm độc hại.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Nút Quay lại (Google)
        Button(
            onClick = onGoBack,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "Quay lại Google (An toàn)",
                color = Color(0xFFB00020),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Nút Tiếp tục (Vào web đen)
        OutlinedButton(
            onClick = onBypass,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Tôi hiểu rủi ro, tiếp tục truy cập",
                color = Color.White.copy(alpha = 0.9f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Link sẽ được mở khóa trong 5 phút",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}