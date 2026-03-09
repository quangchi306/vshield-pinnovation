package com.trustnet.vshield.network

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

// ĐÃ THÊM: Data class chứa kết quả phong phú hơn từ AI
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
            env = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("vshield_model.onnx").readBytes()
            session = env?.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "🤖 Khởi tạo On-Device AI thành công!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi load model ONNX: ${e.message}")
        }
    }

    fun predict(features: FloatArray): AiResult {
        val currentEnv = env ?: return AiResult(false)
        val currentSession = session ?: return AiResult(false)
        var tensor: OnnxTensor? = null

        return try {
            if (features.size != 3029) return AiResult(false)

            val floatBuffer = FloatBuffer.wrap(features)
            val shape = longArrayOf(1, 3029)
            tensor = OnnxTensor.createTensor(currentEnv, floatBuffer, shape)

            val inputs = mapOf("input" to tensor)

            currentSession.run(inputs).use { result ->
                // 1. LẤY NHÃN DỰ ĐOÁN (PREDICTED LABEL) TỪ result[0]
                val labelResult = result[0].value
                var label = -1

                if (labelResult is LongArray) label = labelResult[0].toInt()
                else if (labelResult is IntArray) label = labelResult[0]

                // 2. LẤY XÁC SUẤT (PROBABILITIES) TỪ result[1]
                var confidence = 1.0f
                if (result.size() > 1 && label != -1) {
                    try {
                        val probValue = result[1].value
                        if (probValue is Iterable<*>) {
                            // Xử lý ZipMap (List<Map<Long, Float>>)
                            val map = probValue.firstOrNull() as? Map<*, *>
                            val prob = (map?.get(label.toLong()) as? Number)?.toFloat()
                                ?: (map?.get(label) as? Number)?.toFloat()
                            if (prob != null) confidence = prob
                        } else if (probValue is Array<*>) {
                            // Xử lý mảng 2 chiều (FloatArray hoặc DoubleArray)
                            val row = probValue[0]
                            if (row is FloatArray) confidence = row[label]
                            else if (row is DoubleArray) confidence = row[label].toFloat()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Không thể trích xuất xác suất: ${e.message}")
                    }
                }

                Log.d(TAG, "🧠 AI Output -> Label: $label, Confidence: ${confidence * 100}%")

                // 3. LOGIC XỬ LÝ DỰA TRÊN LABEL VÀ THRESHOLD
                // Label 1 là Benign (Web an toàn)
                if (label == 1 || label == -1) {
                    return AiResult(false)
                }

                // NGƯỠNG TỰ TIN: Chỉ chặn khi AI chắc chắn >= 85%
                if (confidence < 0.85f) {
                    Log.d(TAG, "👉 Tha bổng do độ tự tin AI quá thấp (< 85%)")
                    return AiResult(false)
                }

                // Chuyển đổi mã số thành Lý do báo cáo
                val reason = when (label) {
                    0 -> "Nội dung 18+ (Phát hiện bởi AI)"
                    2 -> "Cờ bạc (Phát hiện bởi AI)"
                    3 -> "Lừa đảo & Mã độc (Phát hiện bởi AI)"
                    else -> "Trang web đáng ngờ (AI)"
                }

                AiResult(true, reason, confidence)
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