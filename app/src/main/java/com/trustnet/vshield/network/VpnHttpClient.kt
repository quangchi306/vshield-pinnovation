package com.trustnet.vshield.network

import android.net.VpnService
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object VpnHttpClient {
    @Volatile
    private var instance: OkHttpClient? = null

    // Đảm bảo chỉ có 1 instance duy nhất để tận dụng Connection Pool
    fun getClient(vpnService: VpnService): OkHttpClient {
        return instance ?: synchronized(this) {
            instance ?: buildClient(vpnService).also { instance = it }
        }
    }

    private fun buildClient(vpnService: VpnService): OkHttpClient {
        // 1. CHÌA KHÓA: Ép tất cả Socket tạo ra phải đi xuyên qua VPN (Protect)
        val protectedSocketFactory = object : SocketFactory() {
            val defaultFactory = getDefault()
            override fun createSocket(): Socket {
                val socket = defaultFactory.createSocket()
                vpnService.protect(socket)
                return socket
            }
            override fun createSocket(host: String?, port: Int): Socket = createSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
        }

        // 2. Client phụ chỉ dùng cho việc gọi DoH (1.1.1.1)
        val dohClient = OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        // 3. Ghi đè bộ giải mã DNS của OkHttp, bắt nó dùng DoH Cloudflare
        val dohDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val request = Request.Builder()
                    .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                    .header("accept", "application/dns-json")
                    .build()

                try {
                    dohClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return emptyList()
                            val json = JSONObject(body)
                            if (json.optInt("Status") == 0 && json.has("Answer")) {
                                val answers = json.getJSONArray("Answer")
                                for (i in 0 until answers.length()) {
                                    val record = answers.getJSONObject(i)
                                    if (record.optInt("type") == 1) { // A Record (IPv4)
                                        return listOf(InetAddress.getByName(record.optString("data")))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Lỗi mạng thì fallback về DNS hệ thống
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        }

        // 4. Client chính để Scrape dữ liệu
        return OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .dns(dohDns) // Tích hợp DoH siêu tốc
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS) // Ép timeout sớm nếu server chết
            .followRedirects(true)
            .followSslRedirects(true) // Không chạy theo Redirect để tối ưu thời gian
            .build()
    }
}