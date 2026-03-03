package com.trustnet.vshield.network

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ServerApiClient {
    private const val TAG = "ServerApiClient"
    // IP Server FastAPI của bạn
    private const val SERVER_URL = "http://YOUR_SERVER_IP:8000/v1/analyze"

    fun sendPayloadToAi(payload: JSONObject): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(SERVER_URL)
            connection = url.openConnection() as HttpURLConnection

            // Timeout chờ AI phân tích (1 giây)
            connection.connectTimeout = 500
            connection.readTimeout = 1000

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.doOutput = true

            // THÊM DÒNG NÀY ĐỂ IN JSON RA MÀN HÌNH ANDROID STUDIO:
            // Tham số '4' giúp format JSON thụt lề cho dễ đọc
            Log.d(TAG, "🚀 Chuẩn bị gửi Payload lên Server:\n${payload.toString(4)}")

            // Gửi toàn bộ cục payload to béo lên Server
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(responseStr)
                return responseJson.optString("action", "allow") == "block"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gọi API AI: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return false
    }
}