package com.trustnet.vshield.network

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

object Preprocessor {
    private const val TAG = "Preprocessor"

    // Lưu trữ TF-IDF
    private val vocab = HashMap<String, Int>()
    private lateinit var idf: DoubleArray
    private var numFeatures = 0

    // Lưu trữ Ordinal Encoder (Danh mục)
    private val tldCategories = HashMap<String, Float>()
    private val serverCategories = HashMap<String, Float>()
    private val issuerCategories = HashMap<String, Float>()

    fun init(context: Context) {
        if (vocab.isNotEmpty()) return // Tránh nạp lại nhiều lần
        try {
            val jsonString = context.assets.open("preprocessor_data.json").bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            // 1. Nạp bộ từ điển Vocab
            val vocabObj = json.getJSONObject("vocab")
            numFeatures = vocabObj.length()
            vocabObj.keys().forEach { key ->
                vocab[key] = vocabObj.getInt(key)
            }

            // 2. Nạp mảng IDF
            val idfArray = json.getJSONArray("idf")
            idf = DoubleArray(idfArray.length())
            for (i in 0 until idfArray.length()) {
                idf[i] = idfArray.getDouble(i)
            }

            // 3. Nạp OrdinalEncoder
            val catArray = json.getJSONArray("categories")

            // TLD (Index 0)
            val tldArr = catArray.getJSONArray(0)
            for (i in 0 until tldArr.length()) tldCategories[tldArr.getString(i)] = i.toFloat()

            // Server Header (Index 1)
            val serverArr = catArray.getJSONArray(1)
            for (i in 0 until serverArr.length()) serverCategories[serverArr.getString(i)] = i.toFloat()

            // Issuer (Index 2)
            val issuerArr = catArray.getJSONArray(2)
            for (i in 0 until issuerArr.length()) issuerCategories[issuerArr.getString(i)] = i.toFloat()

            Log.i(TAG, "✅ Nạp Preprocessor thành công! TF-IDF Features: $numFeatures")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Lỗi nạp Preprocessor JSON: ${e.message}")
        }
    }

    /**
     * Băm chuỗi Text ra mảng TF-IDF (Khớp 100% logic char_wb của scikit-learn)
     */
    fun transformText(text: String): FloatArray {
        val vector = FloatArray(numFeatures) { 0f }
        if (vocab.isEmpty() || text.isBlank()) return vector

        val lowerText = text.lowercase(Locale.ROOT)
        val wordRegex = Regex("""\b\w\w+\b""")
        val words = wordRegex.findAll(lowerText).map { it.value }.toList()

        val tfCount = HashMap<Int, Int>()

        for (w in words) {
            val paddedWord = " $w "
            val n = paddedWord.length

            for (nGramLen in 2..5) {
                for (i in 0..n - nGramLen) {
                    val nGram = paddedWord.substring(i, i + nGramLen)
                    val idx = vocab[nGram]
                    if (idx != null) {
                        tfCount[idx] = tfCount.getOrDefault(idx, 0) + 1
                    }
                }
            }
        }

        var sumSquares = 0.0
        for ((idx, count) in tfCount) {
            val tfidfValue = count * idf[idx]
            vector[idx] = tfidfValue.toFloat()
            sumSquares += tfidfValue * tfidfValue
        }

        // L2 Normalization
        if (sumSquares > 0) {
            val norm = sqrt(sumSquares).toFloat()
            for ((idx, _) in tfCount) {
                vector[idx] = vector[idx] / norm
            }
        }

        return vector
    }

    /**
     * Chuyển đổi Danh mục thành các con số Ordinal
     */
    fun transformCategory(tld: String, serverHeader: String, issuer: String): FloatArray {
        val catVec = FloatArray(3)
        // Dùng handle_unknown='use_encoded_value', unknown_value=-1 (Giống code Python)
        catVec[0] = encodeOrUnknown(tld, tldCategories)
        catVec[1] = encodeOrUnknown(serverHeader, serverCategories)
        catVec[2] = encodeOrUnknown(issuer, issuerCategories)
        return catVec
    }

    private fun encodeOrUnknown(value: String, map: Map<String, Float>): Float {
        val cleanValue = value.lowercase(Locale.ROOT).trim()
        // Tìm kiếm chính xác trước, nếu không có thì trả về -1f
        return map[cleanValue] ?: -1f
    }
}