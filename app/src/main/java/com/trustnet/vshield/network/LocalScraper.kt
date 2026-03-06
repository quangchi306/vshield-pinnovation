package com.trustnet.vshield.network

import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import okhttp3.Request
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

object LocalScraper {
    private const val TAG = "LocalScraper"
    private const val MAX_HTML_SIZE = 50_000 // Tối đa 50KB để đảm bảo tốc độ

    // BIÊN DỊCH SẴN REGEX VÀO RAM
    private val passwordRegex = Regex("""type=["']password["']""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe""", RegexOption.IGNORE_CASE)
    private val externalScriptRegex = Regex("""<script[^>]+src=["'](http|//)[^"']*["']""", RegexOption.IGNORE_CASE)
    private val hiddenElementRegex = Regex("""type=["']hidden["']|display:\s*none|visibility:\s*hidden""", RegexOption.IGNORE_CASE)
    private val linkRegex = Regex("""<a[^>]+href=["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val faviconRegex = Regex("""rel=["'](shortcut )?icon["']""", RegexOption.IGNORE_CASE)
    private val titleRegex = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
    private val metaDescRegex = Regex("""<meta[^>]+name=["']description["'][^>]+content=["'](.*?)["']""", RegexOption.IGNORE_CASE)

    // Heuristic Word Count Regexes
    private val gamblingRegex = Regex("""nổ hũ|tài xỉu|bet|casino|đánh bài|rút tiền|game bài|slot""", RegexOption.IGNORE_CASE)
    private val adultRegex = Regex("""18\+|khiêu dâm|người lớn|sex|porn|jav""", RegexOption.IGNORE_CASE)
    private val phishingRegex = Regex("""đăng nhập|login|mật khẩu|password|xác minh|verify|tài khoản|account|bảo mật|cập nhật|update""", RegexOption.IGNORE_CASE)
    private val cryptoRegex = Regex("""bitcoin|crypto|wallet|token|usdt|binance|airdrop""", RegexOption.IGNORE_CASE)

    private val brands = listOf("facebook", "google", "apple", "paypal", "microsoft", "amazon", "netflix", "shopee", "tiktok", "zalo", "bank")

    fun scrapeAndAnalyze(domain: String, service: VShieldVpnService): Boolean {
        if (domain.endsWith(".arpa")) return false
        val startTime = System.currentTimeMillis()

        // 1. TÍNH TOÁN URL SIGNALS
        val isIpBased = domain.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val parts = domain.split(".")
        val tld = if (parts.size > 1 && !isIpBased) parts.last() else ""
        val subdomainCount = max(0, parts.size - 2)
        val specialCharsRatio = domain.count { !it.isLetterOrDigit() && it != '.' }.toDouble() / domain.length
        val containsBrand = brands.any { domain.contains(it) } && !brands.contains(parts.getOrNull(parts.size - 2))
        val entropy = calculateEntropy(domain)

        // 2. GỬI REQUEST BẰNG OKHTTP
        val client = VpnHttpClient.getClient(service)
        val request = Request.Builder()
            .url("https://$domain")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime
                val realIp = "" // Bỏ qua việc lấy IP thủ công vì OkHttp quản lý trong Pool
                val statusCode = response.code
                val isRedirect = response.isRedirect
                val serverHeader = response.header("Server") ?: ""
                val xFramePresent = response.header("X-Frame-Options") != null

                // 3. BÓC TÁCH BẢO MẬT SSL
                val handshake = response.handshake
                val x509 = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val issuer = x509?.issuerX500Principal?.name ?: "Unknown"
                val validFrom = x509?.notBefore?.time ?: 0L
                val validTo = x509?.notAfter?.time ?: 0L
                val now = System.currentTimeMillis()

                val createdDaysAgo = max(0, (now - validFrom) / (1000 * 60 * 60 * 24)).toInt()
                val expiresInDays = max(0, (validTo - now) / (1000 * 60 * 60 * 24)).toInt()
                val isFreeSsl = issuer.contains("Let's Encrypt", true) ||
                        issuer.contains("ZeroSSL", true) ||
                        issuer.contains("Cloudflare", true)

                // 4. STREAM TỐI ĐA 50KB HTML
                val bodyStream = response.body?.byteStream() ?: return false
                val buffer = ByteArray(4096)
                var bytesRead = 0
                val bodyBuilder = java.lang.StringBuilder()

                while (bytesRead < MAX_HTML_SIZE) {
                    val read = bodyStream.read(buffer)
                    if (read == -1) break
                    bodyBuilder.append(String(buffer, 0, read, Charsets.UTF_8))
                    bytesRead += read
                }

                // Đóng mạng lập tức để giải phóng Connection Pool
                response.close()

                val bodySnippet = bodyBuilder.toString()

                // 5. CHẠY REGEX TRÊN ĐOẠN 50KB
                val title = titleRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""
                val description = metaDescRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""
                val hasFavicon = faviconRegex.containsMatchIn(bodySnippet)

                val links = linkRegex.findAll(bodySnippet).map { it.groupValues[1] }.toList()
                val totalLinks = links.size
                val emptyHrefCount = links.count { it == "#" || it.isBlank() || it.startsWith("javascript:") }
                val externalLinks = links.count { it.startsWith("http") && !it.contains(domain) }
                val externalLinksRatio = if (totalLinks > 0) externalLinks.toDouble() / totalLinks else 0.0

                // 6. GÓI DỮ LIỆU JSON
                val payload = JSONObject().apply {
                    put("target_info", JSONObject().apply {
                        put("requested_url", "https://$domain")
                        put("resolved_ip", realIp)
                    })

                    put("url_signals", JSONObject().apply {
                        put("tld", tld)
                        put("domain_length", domain.length)
                        put("subdomain_count", subdomainCount)
                        put("is_ip_based_url", isIpBased)
                        put("special_chars_ratio", String.format(Locale.US, "%.2f", specialCharsRatio).toDouble())
                        put("contains_brand_names", containsBrand)
                        put("entropy_score", String.format(Locale.US, "%.2f", entropy).toDouble())
                    })

                    put("network_signals", JSONObject().apply {
                        put("http_status_code", statusCode)
                        put("redirect_chain_length", if (isRedirect) 1 else 0)
                        put("response_time_ms", responseTime)
                        put("server_header", serverHeader)
                        put("x_frame_options_present", xFramePresent)
                    })

                    put("security_signals", JSONObject().apply {
                        put("has_ssl_from_client", true)
                        put("server_verified_ssl", JSONObject().apply {
                            put("is_valid", true)
                            put("issuer", issuer)
                            put("created_days_ago", createdDaysAgo)
                            put("expires_in_days", expiresInDays)
                            put("is_free_ssl", isFreeSsl)
                        })
                    })

                    put("content_signals", JSONObject().apply {
                        put("metadata", JSONObject().apply {
                            put("title", title.take(100))
                            put("description", description.take(200))
                            put("has_favicon", hasFavicon)
                        })

                        put("dom_structure_signals", JSONObject().apply {
                            put("password_input_count", passwordRegex.findAll(bodySnippet).count())
                            put("iframe_count", iframeRegex.findAll(bodySnippet).count())
                            put("external_script_count", externalScriptRegex.findAll(bodySnippet).count())
                            put("hidden_elements_count", hiddenElementRegex.findAll(bodySnippet).count())
                            put("total_links", totalLinks)
                            put("external_links_ratio", String.format(Locale.US, "%.2f", externalLinksRatio).toDouble())
                            put("empty_href_count", emptyHrefCount)
                        })

                        put("heuristic_word_counts", JSONObject().apply {
                            put("gambling_score", gamblingRegex.findAll(bodySnippet).count())
                            put("adult_score", adultRegex.findAll(bodySnippet).count())
                            put("phishing_score", phishingRegex.findAll(bodySnippet).count())
                            put("crypto_score", cryptoRegex.findAll(bodySnippet).count())
                        })

                        val cleanBody = bodySnippet.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                        put("body_snippet", cleanBody.take(500) + if (cleanBody.length > 500) "..." else "")
                    })
                }

                Log.d(TAG, "⚡ Scrape OK: $domain tốn ${System.currentTimeMillis() - startTime}ms")
                return ServerApiClient.sendPayloadToAi(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi OkHttp Scraper cho $domain: ${e.message}")
            return false
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