package com.trustnet.vshield.network

import android.content.Context
import android.util.Log
import com.trustnet.vshield.core.DomainBlacklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object FilterSyncManager {
    private const val TAG = "FilterSyncManager"

    // URL tải các file Binary từ Server của bạn
    private const val BASE_URL = "http://YOUR_SERVER_IP/downloads"

    suspend fun syncFilters(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                // Tải 4 file song song hoặc tuần tự tùy ý
                downloadAndSave(context, "$BASE_URL/whitelist.bin", "whitelist.bin")
                downloadAndSave(context, "$BASE_URL/phishing.bin", "phishing.bin")
                downloadAndSave(context, "$BASE_URL/adult.bin", "adult.bin")
                downloadAndSave(context, "$BASE_URL/gambling.bin", "gambling.bin")

                // Sau khi tải xong, nạp lại bộ lọc vào RAM
                Log.i(TAG, "Đồng bộ hoàn tất, đang nạp lại RAM...")
                DomainBlacklist.init(context) // Lúc này nó sẽ đọc từ file .bin mới tải
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi đồng bộ Filters: ${e.message}")
            }
        }
    }

    private fun downloadAndSave(context: Context, urlString: String, fileName: String) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val file = File(context.filesDir, fileName)

                // Ghi đè file binary mới tải về
                file.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                Log.d(TAG, "Đã lưu thành công: $fileName")
            } else {
                Log.w(TAG, "Server trả về mã lỗi ${connection.responseCode} cho $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tải $fileName: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}