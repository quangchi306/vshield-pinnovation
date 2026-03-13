package com.trustnet.vshield.network

import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import okhttp3.Request
import java.security.cert.X509Certificate
import kotlin.math.ln
import kotlin.math.max

object LocalScraper {
    private const val TAG = "LocalScraper"
    private const val MAX_HTML_SIZE = 50_000

    private val passwordRegex       = Regex("""type=["']password["']""", RegexOption.IGNORE_CASE)
    private val iframeRegex         = Regex("""<iframe""", RegexOption.IGNORE_CASE)
    private val externalScriptRegex = Regex("""<script[^>]+src=["'](http|//)[^"']*["']""", RegexOption.IGNORE_CASE)
    private val hiddenElementRegex  = Regex("""type=["']hidden["']|display:\s*none|visibility:\s*hidden""", RegexOption.IGNORE_CASE)
    private val linkRegex           = Regex("""<a[^>]+href=["'](.*?)["']""", RegexOption.IGNORE_CASE)
    private val faviconRegex        = Regex("""rel=["'](shortcut )?icon["']""", RegexOption.IGNORE_CASE)
    private val titleRegex          = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE)
    private val metaDescRegex       = Regex("""<meta[^>]+name=["']description["'][^>]+content=["'](.*?)["']""", RegexOption.IGNORE_CASE)

    // Regex đã đồng bộ hoàn toàn với Python
    private val gamblingRegex = Regex("""nổ hũ|tài xỉu|đánh bài|game bài|cá độ|cá cược|lô đề|xóc đĩa|bầu cua|số đề|đỏ đen|nhà cái|chia bài|đại lý|hoa hồng|nạp tiền|rút tiền|khuyến mãi|poker|blackjack|roulette|baccarat|slot machine|pokies|sports betting|horse racing|lottery|scratch card|bingo|loot box|mystery box|in-app purchase|gamble|wager|stake|odds|bookmaker|bookie|high roller|house edge|bluff|all in|side bet|mu88|bong88|sbobet|fun88|tf88|12bet""", RegexOption.IGNORE_CASE)
    private val adultRegex = Regex("""18\+|khiêu dâm|người lớn|địt|đụ|lồn|bướm|clip nóng|kích dục|thủ dâm|quay lén|hình nóng|đồi trụy|\bporn\b|xxx|adult content|\bnude\b|\bnaked\b|erotic|\bnsfw\b|onlyfans|cam girl|striptease|\bhorny\b|\bboobs\b|\bnipple\b|\bgenitals\b|\bpenis\b|\bvagina\b|\bmilf\b|\bhentai\b|\banal\b|threesome|BDSM""", RegexOption.IGNORE_CASE)
    private val phishingRegex = Regex("""khẩn cấp|ngay lập tức|hết hạn|tạm khóa|đăng nhập lạ|phát hiện bất thường|lừa đảo|chiếm đoạt|đánh cắp|việc nhẹ lương cao|combo du lịch giá rẻ|tuyển cộng tác viên|unusual activity|suspicious login|security alert|unauthorized transaction|confirm your identity|update your payment|blocked account|account suspended|account terminated|verify your account|limited access|your account will be|within 24 hours|spear phishing|whaling|business email compromise|\bBEC\b|CEO fraud|\bransomware\b|\bvishing\b|\bsmishing\b|PayPal|Netflix|Amazon|Apple ID|Microsoft account|\bIRS\b|\bDHL\b|\bFedEx\b|\bUPS\b""", RegexOption.IGNORE_CASE)

    private val brands = listOf("facebook", "google", "apple", "paypal", "microsoft", "amazon", "netflix", "shopee", "tiktok", "zalo", "bank")
    private val TOP_BRANDS = listOf("google", "facebook", "paypal", "apple", "microsoft", "amazon", "netflix", "shopee", "tiktok", "zalo", "vietcombank", "techcombank", "mbbank", "tpbank", "bidv", "agribank", "sacombank", "vpbank", "acb")
    private val CONFUSABLE_CHARS = setOf('\u0430', '\u0435', '\u043e', '\u0440', '\u0441', '\u0445', '\u0456', '\u04cf', '\u03bf', '\u03c1', '\u03b5')

    fun scrapeAndAnalyze(domain: String, service: VShieldVpnService): AiResult {
        if (domain.endsWith(".arpa")) return AiResult(false)
        val startTime = System.currentTimeMillis()

        val isIpBased = domain.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        val parts = domain.split(".")

        // 1. GIẢ LẬP TLDEXTRACT CỦA PYTHON
        val tld = if (parts.size >= 2 && !isIpBased) {
            val p1 = parts[parts.size - 2]
            val p2 = parts[parts.size - 1]
            if (p1 in listOf("com", "co", "net", "org", "edu", "gov", "ac", "in", "uk", "vn")) "$p1.$p2" else p2
        } else if (parts.isNotEmpty() && !isIpBased) {
            parts.last()
        } else ""

        val domainWithoutTld = if (tld.isNotEmpty() && domain.endsWith(".$tld")) {
            domain.substring(0, domain.length - tld.length - 1)
        } else domain

        val subParts = domainWithoutTld.split(".")
        val domainName = subParts.lastOrNull() ?: "" // Lấy đúng tên gốc, ví dụ: "facebook"
        val subdomainCount = max(0, subParts.size - 1)

        val fakeUrl = "https://$domain"
        val specialCharsRatio = fakeUrl.count { !it.isLetterOrDigit() }.toDouble() / fakeUrl.length

        // 2. SO SÁNH EXTERNAL BẰNG DOMAIN GỐC (domainName)
        val containsBrand = brands.any { domain.contains(it) } && !brands.contains(domainName)
        val entropy = calculateEntropy(domain)

        val client  = VpnHttpClient.getClient(service)
        val request = Request.Builder().url("https://$domain")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36").build()

        try {
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime
                val statusCode   = response.code
                val serverHeader = response.header("Server") ?: ""
                val xFramePresent= response.header("X-Frame-Options") != null

                val originalHost = domain
                val finalHost = response.request.url.host
                val redirectCount = if (finalHost != originalHost) 1 else 0

                val handshake = response.handshake
                val x509      = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val issuer    = x509?.issuerX500Principal?.name ?: ""
                val validFrom = x509?.notBefore?.time ?: 0L
                val validTo   = x509?.notAfter?.time ?: 0L
                val now       = System.currentTimeMillis()

                val hasSsl          = x509 != null
                val isValidSsl      = hasSsl && (now in validFrom..validTo)
                val createdDaysAgo  = if (hasSsl) max(0, ((now - validFrom) / 86_400_000).toInt()) else 0
                val expiresInDays   = if (hasSsl) max(0, ((validTo - now)   / 86_400_000).toInt()) else 0
                val isFreeSsl       = issuer.contains("Let's Encrypt", true) || issuer.contains("ZeroSSL", true) || issuer.contains("Cloudflare", true) || issuer.contains("cPanel", true)

                val contentType = response.header("Content-Type") ?: ""
                if (contentType.isNotEmpty() && !contentType.contains("text/html", ignoreCase = true)) {
                    return AiResult(false)
                }

                val bodyStream  = response.body?.byteStream() ?: return AiResult(false)
                val buffer      = ByteArray(4096)
                var bytesRead   = 0
                val bodyBuilder = StringBuilder()

                while (bytesRead < MAX_HTML_SIZE) {
                    val read = bodyStream.read(buffer)
                    if (read == -1) break
                    bodyBuilder.append(String(buffer, 0, read, Charsets.UTF_8))
                    bytesRead += read
                }
                val bodySnippet = bodyBuilder.toString()

                val title       = titleRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""
                val hasFavicon  = faviconRegex.containsMatchIn(bodySnippet)
                val description = metaDescRegex.find(bodySnippet)?.groupValues?.get(1) ?: ""

                val passwordCount      = passwordRegex.findAll(bodySnippet).count()
                val iframeCount        = iframeRegex.findAll(bodySnippet).count()
                val externalScriptCount= externalScriptRegex.findAll(bodySnippet).count()
                val hiddenElementsCount= hiddenElementRegex.findAll(bodySnippet).count()

                val links           = linkRegex.findAll(bodySnippet).map { it.groupValues[1] }.toList()
                val totalLinks      = links.size
                val emptyHrefCount  = links.count { it == "#" || it.isBlank() || it.startsWith("javascript:") }

                // 3. FIX LỖI EXTERNAL LINKS BẰNG TÊN MIỀN GỐC
                val externalLinks      = links.count { it.startsWith("http") && !it.contains(domainName) }
                val externalLinksRatio = if (totalLinks > 0) externalLinks.toDouble() / totalLinks else 0.0

                val cleanBody  = bodySnippet.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

                // 4. FIX LỖI FORM ACTION EXTERNAL
                val formActionRegex = Regex("""<form[^>]+action=["'](\s*https?://[^"']*?)["']""", RegexOption.IGNORE_CASE)
                val formActionExternal = formActionRegex.findAll(bodySnippet).count { !it.groupValues[1].contains(domainName) }

                val metaRefreshPresent = if (Regex("""<meta[^>]+http-equiv=["']refresh["']""", RegexOption.IGNORE_CASE).containsMatchIn(bodySnippet)) 1 else 0
                val inputFieldsCount = Regex("""<input""", RegexOption.IGNORE_CASE).findAll(bodySnippet).count()
                val scriptCount  = Regex("""<script""", RegexOption.IGNORE_CASE).findAll(bodySnippet).count()
                val totalTagCount= Regex("""<[a-zA-Z]""").findAll(bodySnippet).count().coerceAtLeast(1)
                val jsHeavyScore = scriptCount.toDouble() / totalTagCount
                val loginFormWithoutSsl = if (passwordCount > 0 && !hasSsl) 1 else 0
                val typosquattingScore = calculateTyposquatting(domainName)
                val homographScore = if (domain.any { it in CONFUSABLE_CHARS }) 1 else 0

                val combinedText = "${title.take(100)} ${description.take(200)} ${cleanBody.take(500)}".trim()
                val tfidfFeatures = Preprocessor.transformText(combinedText)
                val catFeatures = Preprocessor.transformCategory(tld, serverHeader, issuer)

                val features = FloatArray(3033)
                System.arraycopy(tfidfFeatures, 0, features, 0, tfidfFeatures.size)
                System.arraycopy(catFeatures, 0, features, tfidfFeatures.size, catFeatures.size)

                var offset = tfidfFeatures.size + catFeatures.size

                features[offset++] = domain.length.toFloat()
                features[offset++] = subdomainCount.toFloat()
                features[offset++] = specialCharsRatio.toFloat()
                features[offset++] = entropy.toFloat()
                features[offset++] = statusCode.toFloat()
                features[offset++] = redirectCount.toFloat()
                features[offset++] = responseTime.toFloat()
                features[offset++] = passwordCount.toFloat()
                features[offset++] = iframeCount.toFloat()
                features[offset++] = externalScriptCount.toFloat()
                features[offset++] = hiddenElementsCount.toFloat()
                features[offset++] = totalLinks.toFloat()
                features[offset++] = externalLinksRatio.toFloat()
                features[offset++] = emptyHrefCount.toFloat()
                // Phishing, Adult, Gambling scores removed per pipeline v3
                features[offset++] = createdDaysAgo.toFloat()
                features[offset++] = expiresInDays.toFloat()
                features[offset++] = formActionExternal.toFloat()
                features[offset++] = metaRefreshPresent.toFloat()
                features[offset++] = inputFieldsCount.toFloat()
                features[offset++] = jsHeavyScore.toFloat()
                features[offset++] = loginFormWithoutSsl.toFloat()
                features[offset++] = typosquattingScore.toFloat()
                features[offset++] = homographScore.toFloat()
                features[offset++] = if (isIpBased) 1f else 0f
                features[offset++] = if (containsBrand) 1f else 0f
                features[offset++] = if (xFramePresent) 1f else 0f
                features[offset++] = if (hasFavicon) 1f else 0f
                features[offset++] = if (hasSsl) 1f else 0f
                features[offset++] = if (isValidSsl) 1f else 0f
                features[offset++] = if (isFreeSsl) 1f else 0f

                Log.d(TAG, "Scrape OK: $domain | features=${features.size} | redirects=$redirectCount")
                return OnDeviceAi.predict(features)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi Scraper cho $domain: ${e.message}")
            return AiResult(false)
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

    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - dp[a.length][b.length].toDouble() / maxLen
    }

    private fun calculateTyposquatting(domainName: String): Float {
        var best = 0.0
        for (brand in TOP_BRANDS) {
            val sim = levenshteinSimilarity(domainName.lowercase(), brand)
            if (sim in 0.70..0.95) best = maxOf(best, sim)
        }
        return best.toFloat()
    }
}