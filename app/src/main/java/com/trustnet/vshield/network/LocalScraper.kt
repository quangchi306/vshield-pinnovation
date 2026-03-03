package com.trustnet.vshield.network

import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.DomainBlacklist
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.max

object LocalScraper {
    private const val TAG = "LocalScraper"

    fun scrapeAndAnalyze(domain: String, service: VShieldVpnService): Boolean {
        // Chốt chặn phụ: Tránh quét lại hệ thống
        if (domain.endsWith(".arpa")) return false

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        try {
            // 1. Phân giải IP bằng DoH (Dùng IP cứng 1.1.1.1 để bypass VPN loop)
            val realIp = resolveIpViaDoH(domain)
            if (realIp == null) {
                Log.e(TAG, "Không thể phân giải IP qua DoH cho $domain")
                return false
            }

            // 2. Tạo Socket thô và BẢO VỆ NÓ (Đi xuyên qua VPN)
            rawSocket = Socket()
            rawSocket.bind(null) // Lệnh Bắt buộc: Ép Android cấp phát File Descriptor trước khi protect

            if (!service.protect(rawSocket)) {
                Log.e(TAG, "Không thể protect socket cho $domain")
                return false
            }

            // Kết nối trực tiếp tới IP thật của domain
            rawSocket.connect(InetSocketAddress(realIp, 443), 1500)

            // 3. Nâng cấp lên SSL Socket (Bắt tay TLS hỗ trợ SNI)
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, domain, 443, true) as SSLSocket
            sslSocket.soTimeout = 1500
            sslSocket.startHandshake()

            // 4. Lấy thông tin chứng chỉ SSL (Giúp Server bắt Phishing siêu nhanh)
            val certs = sslSocket.session.peerCertificates
            val x509 = certs.firstOrNull() as? X509Certificate

            val issuer = x509?.issuerX500Principal?.name ?: "Unknown"
            val validFrom = x509?.notBefore?.time ?: 0L
            val validTo = x509?.notAfter?.time ?: 0L
            val now = System.currentTimeMillis()

            val createdDaysAgo = max(0, (now - validFrom) / (1000 * 60 * 60 * 24)).toInt()
            val expiresInDays = max(0, (validTo - now) / (1000 * 60 * 60 * 24)).toInt()

            // 5. Gửi HTTP GET để tải HTML (Giả dạng Google Chrome)
            val writer = OutputStreamWriter(sslSocket.outputStream)
            writer.write("GET / HTTP/1.1\r\n")
            writer.write("Host: $domain\r\n")
            writer.write("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()

            // 6. Đọc phản hồi và KIỂM TRA CONTENT-TYPE (Tránh cào file APK, Ảnh, Video)
            val reader = BufferedReader(InputStreamReader(sslSocket.inputStream))
            val htmlBuilder = StringBuilder()
            var line: String?
            var bytesRead = 0

            var isHtml = false
            var contentType = "unknown"

            // Đọc dòng Status HTTP
            val statusLine = reader.readLine()
            if (statusLine != null) htmlBuilder.append(statusLine).append("\n")

            // Đọc Header để tìm xem nó có phải trang Web (text/html) không
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break // Hết Header

                val lowerLine = line!!.lowercase()
                if (lowerLine.startsWith("content-type:")) {
                    contentType = lowerLine.substringAfter("content-type:").trim()
                    if (contentType.contains("text/html")) {
                        isHtml = true
                    }
                }
            }

            // Nếu là trang web thực sự -> Tiến hành cào nội dung
            if (isHtml) {
                while (reader.readLine().also { line = it } != null) {
                    htmlBuilder.append(line).append("\n")
                    bytesRead += line?.length ?: 0
                    // Cắt ngang nếu lấy đủ thẻ Meta để tăng tốc độ phân tích
                    if (bytesRead > 15000 || htmlBuilder.contains("</head>")) break
                }
            } else {
                // Đóng mộc báo hiệu cho Server biết đây là file/API tải ngầm
                Log.d(TAG, "Lọc nhanh: $domain trả về [$contentType]. Gửi siêu dữ liệu lên Server.")
                htmlBuilder.append("[NON-HTML: $contentType]")
            }

            // 7. Dùng Jsoup bóc tách thẻ HTML
            val document = Jsoup.parse(htmlBuilder.toString())
            val title = if (isHtml) document.title() else ""
            val description = if (isHtml) document.select("meta[name=description]").attr("content") else ""
            val keywords = if (isHtml) document.select("meta[name=keywords]").attr("content") else ""
            val bodySnippet = if (isHtml) document.body()?.text()?.take(500) ?: "" else htmlBuilder.toString()

            // 8. ĐÓNG GÓI JSON GỬI LÊN SERVER
            val payload = JSONObject().apply {
                put("request_id", "req-${UUID.randomUUID().toString().substring(0, 8)}")
                put("domain", domain)

                // Trích xuất tùy chọn chặn của người dùng gửi cho Server AI
                put("user_preferences", JSONObject().apply {
                    put("block_phishing", true) // Phishing mặc định luôn bị khóa
                    put("block_adult", DomainBlacklist.blockAdult)
                    put("block_gambling", DomainBlacklist.blockGambling)
                })

                put("client_metadata", JSONObject().apply {
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("user_agent", "V-Shield Android Local Scraper")
                })
                put("content", JSONObject().apply {
                    put("title", title)
                    put("description", description)
                    put("meta_keywords", keywords)
                    put("body_snippet", bodySnippet)
                })
                put("security_signals", JSONObject().apply {
                    put("has_ssl_from_client", true)
                    put("server_verified_ssl", JSONObject().apply {
                        put("is_valid", true)
                        put("issuer", issuer)
                        put("created_days_ago", createdDaysAgo)
                        put("expires_in_days", expiresInDays)
                    })
                })
            }

            Log.d(TAG, "Đã có dữ liệu cho $domain, gửi API lên Server...")

            // Gọi API
            return ServerApiClient.sendPayloadToAi(payload)

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi LocalScraper cho $domain: ${e.message}")
            return false // Fail-open (Lỗi mạng thì cho qua)
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun resolveIpViaDoH(domain: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://1.1.1.1/dns-query?name=$domain&type=A")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            connection.setRequestProperty("accept", "application/dns-json")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)

                if (json.optInt("Status") == 0 && json.has("Answer")) {
                    val answers = json.getJSONArray("Answer")
                    for (i in 0 until answers.length()) {
                        val record = answers.getJSONObject(i)
                        // Chỉ lấy IPv4 (type = 1)
                        if (record.optInt("type") == 1) {
                            return record.optString("data")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân giải DoH cho $domain: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }
}