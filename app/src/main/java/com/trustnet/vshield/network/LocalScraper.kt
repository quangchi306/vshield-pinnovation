package com.trustnet.vshield.network

import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

object LocalScraper {
    private const val TAG = "LocalScraper"
    private const val MAX_HTML_SIZE = 50_000

    private val passwordRegex = Regex("""type=["']password["']""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe""", RegexOption.IGNORE_CASE)
    private val externalScriptRegex = Regex("""<script[^>]+src=["'](http|//)[^"']*["']""", RegexOption.IGNORE_CASE)
    private val hiddenElementRegex = Regex("""type=["']hidden["']|display:\s*none|visibility:\s*hidden""", RegexOption.IGNORE_CASE)
    private val linkRegex = Regex("""<a[^>]+href=["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val faviconRegex = Regex("""rel=["'](shortcut )?icon["']""", RegexOption.IGNORE_CASE)
    private val titleRegex = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
    private val metaDescRegex = Regex("""<meta[^>]+name=["']description["'][^>]+content=["'](.*?)["']""", RegexOption.IGNORE_CASE)

    private val gamblingRegex = Regex("""nổ hũ|tài xỉu|bet|casino|đánh bài|rút tiền|game bài|slot""", RegexOption.IGNORE_CASE)
    private val adultRegex = Regex("""18\+|khiêu dâm|người lớn|sex|porn|jav""", RegexOption.IGNORE_CASE)
    private val phishingRegex = Regex("""đăng nhập|login|mật khẩu|password|xác minh|verify|tài khoản|account|bảo mật|cập nhật|update""", RegexOption.IGNORE_CASE)

    private val brands = listOf("facebook", "google", "apple", "paypal", "microsoft", "amazon", "netflix", "shopee", "tiktok", "zalo", "bank")

    fun scrapeAndAnalyze(domain: String, service: VShieldVpnService): AiResult {
        if (domain.endsWith(".arpa")) return AiResult(false) // Sửa return
        val startTime = System.currentTimeMillis()

        // 1. TÍNH TOÁN URL SIGNALS
        val isIpBased = domain.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val parts = domain.split(".")
        val tld = if (parts.size > 1 && !isIpBased) parts.last() else ""
        val subdomainCount = max(0, parts.size - 2)
        val specialCharsRatio = domain.count { !it.isLetterOrDigit() && it != '.' }.toDouble() / domain.length
        val containsBrand = brands.any { domain.contains(it) } && !brands.contains(parts.getOrNull(parts.size - 2))
        val entropy = calculateEntropy(domain)

        val client = VpnHttpClient.getClient(service)
        val request = Request.Builder()
            .url("https://$domain")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime
                val statusCode = response.code
                val isRedirect = response.isRedirect
                val serverHeader = response.header("Server") ?: ""
                val xFramePresent = response.header("X-Frame-Options") != null

                // BÓC TÁCH BẢO MẬT SSL
                val handshake = response.handshake
                val x509 = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val issuer = x509?.issuerX500Principal?.name ?: ""
                val validFrom = x509?.notBefore?.time ?: 0L
                val validTo = x509?.notAfter?.time ?: 0L
                val now = System.currentTimeMillis()

                val hasSsl = x509 != null
                val isValidSsl = hasSsl && (now in validFrom..validTo)
                val createdDaysAgo = if (hasSsl) max(0, (now - validFrom) / (1000 * 60 * 60 * 24)).toInt() else 0
                val expiresInDays = if (hasSsl) max(0, (validTo - now) / (1000 * 60 * 60 * 24)).toInt() else 0
                val isFreeSsl = issuer.contains("Let's Encrypt", true) || issuer.contains("ZeroSSL", true) || issuer.contains("Cloudflare", true)

                // ĐÃ SỬA: LỌC BỎ CÁC LUỒNG DỮ LIỆU KHÔNG PHẢI WEB (API, JSON, ẢNH)
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.isNotEmpty() && !contentType.contains("text/html", ignoreCase = true)) {
                    Log.d(TAG, "Bỏ qua AI: Tên miền $domain trả về Resource/API ($contentType)")
                    response.close()
                    return AiResult(false) // Sửa return
                }

                // STREAM HTML
                val bodyStream = response.body?.byteStream() ?: return AiResult(false)
                val buffer = ByteArray(4096)
                var bytesRead = 0
                val bodyBuilder = java.lang.StringBuilder()

                while (bytesRead < MAX_HTML_SIZE) {
                    val read = bodyStream.read(buffer)
                    if (read == -1) break
                    bodyBuilder.append(String(buffer, 0, read, Charsets.UTF_8))
                    bytesRead += read
                }
                response.close()
                val bodySnippet = bodyBuilder.toString()

                // FEATURE COUNT TRÊN ĐOẠN HTML
                val title = titleRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""
                val description = metaDescRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""
                val hasFavicon = faviconRegex.containsMatchIn(bodySnippet)

                val passwordCount = passwordRegex.findAll(bodySnippet).count()
                val iframeCount = iframeRegex.findAll(bodySnippet).count()
                val externalScriptCount = externalScriptRegex.findAll(bodySnippet).count()
                val hiddenElementsCount = hiddenElementRegex.findAll(bodySnippet).count()

                val links = linkRegex.findAll(bodySnippet).map { it.groupValues[1] }.toList()
                val totalLinks = links.size
                val emptyHrefCount = links.count { it == "#" || it.isBlank() || it.startsWith("javascript:") }
                val externalLinks = links.count { it.startsWith("http") && !it.contains(domain) }
                val externalLinksRatio = if (totalLinks > 0) externalLinks.toDouble() / totalLinks else 0.0

                val gamblingScore = gamblingRegex.findAll(bodySnippet).count()
                val adultScore = adultRegex.findAll(bodySnippet).count()
                val phishingScore = phishingRegex.findAll(bodySnippet).count()

                val cleanBody = bodySnippet.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

                // --- GÓP NHẶT 3029 FEATURES ĐỂ ĐƯA VÀO ONNX ---
                val combinedText = "${title.take(100)} ${description.take(200)} ${cleanBody.take(500)}".trim()

                // 1. Chạy TF-IDF (3000 con số)
                val tfidfFeatures = Preprocessor.transformText(combinedText)

                // 2. Chạy Categorical (3 con số)
                val catFeatures = Preprocessor.transformCategory(tld, serverHeader, issuer)

                // 3. Khởi tạo mảng tổng (3029 phần tử)
                val features = FloatArray(3029)

                // Đổ TF-IDF vào
                System.arraycopy(tfidfFeatures, 0, features, 0, tfidfFeatures.size)

                // Đổ Categorical vào tiếp theo
                System.arraycopy(catFeatures, 0, features, tfidfFeatures.size, catFeatures.size)

                // 4. Điền 26 Numeric Features còn lại (Khớp TUYỆT ĐỐI với thứ tự Python)
                var offset = tfidfFeatures.size + catFeatures.size

                features[offset++] = domain.length.toFloat()
                features[offset++] = subdomainCount.toFloat()
                features[offset++] = specialCharsRatio.toFloat()
                features[offset++] = entropy.toFloat()
                features[offset++] = statusCode.toFloat()
                features[offset++] = if (isRedirect) 1f else 0f
                features[offset++] = responseTime.toFloat()
                features[offset++] = passwordCount.toFloat()
                features[offset++] = iframeCount.toFloat()
                features[offset++] = externalScriptCount.toFloat()
                features[offset++] = hiddenElementsCount.toFloat()
                features[offset++] = totalLinks.toFloat()
                features[offset++] = externalLinksRatio.toFloat()
                features[offset++] = emptyHrefCount.toFloat()
                features[offset++] = gamblingScore.toFloat()
                features[offset++] = adultScore.toFloat()
                features[offset++] = phishingScore.toFloat()
                features[offset++] = createdDaysAgo.toFloat()
                features[offset++] = expiresInDays.toFloat()
                features[offset++] = if (isIpBased) 1f else 0f
                features[offset++] = if (containsBrand) 1f else 0f
                features[offset++] = if (xFramePresent) 1f else 0f
                features[offset++] = if (hasFavicon) 1f else 0f
                features[offset++] = if (hasSsl) 1f else 0f
                features[offset++] = if (isValidSsl) 1f else 0f
                features[offset++] = if (isFreeSsl) 1f else 0f

                Log.d(TAG, "⚡ Scrape OK: $domain. Kích thước mảng: ${features.size}. Đang ném vào OnDeviceAi...")

                // GỌI MÔ HÌNH CỤC BỘ TRÊN ĐIỆN THOẠI
                return OnDeviceAi.predict(features)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi Scraper cho $domain: ${e.message}")
            return AiResult(false) // Sửa return
        }
    }

    private fun calculateEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = HashMap<Char, Int>()
        for (c in s) freq[c] = freq.getOrDefault(c, 0) + 1
        var entropy = 0.0
        for (count in freq.values) {
            val p = count.toDouble() / s.length
            entropy -= p * (ln(p) / ln(2.0))
        }
        return entropy
    }
}