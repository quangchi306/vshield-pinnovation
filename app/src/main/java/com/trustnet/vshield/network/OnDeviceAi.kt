package com.trustnet.vshield.network

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.trustnet.vshield.core.DomainBlacklist
import java.nio.FloatBuffer

// Data class chứa kết quả phong phú hơn từ AI
data class AiResult(
    val isMalicious: Boolean,
    val reason: String = "",
    val confidence: Float = 0f
)

object OnDeviceAi {
    private const val TAG = "OnDeviceAi"
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun init(context: Context) {
        if (session != null) return
        try {
            Preprocessor.init(context)
            env = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("vshield_model.onnx").readBytes()
            session = env?.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "🤖 Khởi tạo On-Device AI thành công!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi load model ONNX: ${e.message}")
        }
    }

    fun predict(features: FloatArray): AiResult {
        val currentEnv     = env     ?: return AiResult(false)
        val currentSession = session ?: return AiResult(false)
        var tensor: OnnxTensor? = null

        return try {
            // Model v4: tự động nhận FloatArray(3036) features
            if (features.isEmpty()) return AiResult(false)

            val floatBuffer = FloatBuffer.wrap(features)
            val shape       = longArrayOf(1, features.size.toLong())
            tensor          = OnnxTensor.createTensor(currentEnv, floatBuffer, shape)

            val inputs = mapOf("input" to tensor)

            currentSession.run(inputs).use { result ->
                // 1. Lấy nhãn dự đoán từ result[0]
                val labelResult = result[0].value
                var label = -1

                if (labelResult is LongArray) label = labelResult[0].toInt()
                else if (labelResult is IntArray) label = labelResult[0]

                // 2. Lấy xác suất từ result[1]
                var confidence = 1.0f
                if (result.size() > 1 && label != -1) {
                    try {
                        val probValue = result[1].value
                        if (probValue is Iterable<*>) {
                            // Xử lý ZipMap (List<Map<Long, Float>>)
                            val map  = probValue.firstOrNull() as? Map<*, *>
                            val prob = (map?.get(label.toLong()) as? Number)?.toFloat()
                                ?: (map?.get(label) as? Number)?.toFloat()
                            if (prob != null) confidence = prob
                        } else if (probValue is Array<*>) {
                            // Xử lý mảng 2 chiều (FloatArray hoặc DoubleArray)
                            val row = probValue[0]
                            if (row is FloatArray)  confidence = row[label]
                            else if (row is DoubleArray) confidence = row[label].toFloat()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Không thể trích xuất xác suất: ${e.message}")
                    }
                }

                Log.d(TAG, "🧠 AI Output -> Label: $label, Confidence: ${confidence * 100}%")

                // 3. Logic xử lý kết quả
                // Label mapping (label_classes từ preprocessor_data.json):
                //   0 = adult | 1 = benign | 2 = gambling | 3 = phishing
                if (label == 1 || label == -1) {
                    return AiResult(false)
                }

                // Ngưỡng tự tin: chỉ chặn khi AI chắc chắn >= 85%
                if (confidence < 0.85f) {
                    Log.d(TAG, "👉 Tha bổng do độ tự tin AI quá thấp (< 85%)")
                    return AiResult(false)
                }

                // 4. Đối chiếu nhãn AI với tùy chọn người dùng
                when (label) {
                    0 -> { // Adult (Web 18+)
                        if (DomainBlacklist.blockAdult) {
                            AiResult(true, "Nội dung 18+ - Phát hiện bởi AI", confidence)
                        } else {
                            Log.d(TAG, "👉 AI phát hiện Web 18+ nhưng cho qua vì tùy chọn chặn đã TẮT")
                            AiResult(false)
                        }
                    }
                    2 -> { // Gambling (Cờ bạc)
                        if (DomainBlacklist.blockGambling) {
                            AiResult(true, "Cờ bạc - Phát hiện bởi AI", confidence)
                        } else {
                            Log.d(TAG, "👉 AI phát hiện Web Cờ bạc nhưng cho qua vì tùy chọn chặn đã TẮT")
                            AiResult(false)
                        }
                    }
                    3 -> { // Phishing (Lừa đảo — luôn chặn, không có nút tắt)
                        AiResult(true, "Lừa đảo & Mã độc - Phát hiện bởi AI", confidence)
                    }
                    else -> AiResult(true, "Trang web đáng ngờ (AI)", confidence)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi suy luận AI: ${e.message}")
            AiResult(false)
        } finally {
            tensor?.close()
        }
    }

    fun close() {
        session?.close()
        env?.close()
    }
}