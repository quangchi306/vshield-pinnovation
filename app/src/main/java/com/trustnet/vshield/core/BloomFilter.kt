package com.trustnet.vshield.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import kotlin.math.ln
import kotlin.math.roundToInt

class BloomFilter private constructor(
    private val bitSize: Int,
    private val numHashFunctions: Int
) {
    private val bits = BitSet(bitSize)
    private var insertedCount: Int = 0

    fun add(value: String) {
        val (h1, h2) = hashPair(value)
        for (i in 0 until numHashFunctions) {
            val idx = positiveMod(h1 + i * h2, bitSize)
            bits.set(idx)
        }
        insertedCount++
    }

    fun mightContain(value: String): Boolean {
        val (h1, h2) = hashPair(value)
        for (i in 0 until numHashFunctions) {
            val idx = positiveMod(h1 + i * h2, bitSize)
            if (!bits.get(idx)) return false
        }
        return true
    }

    fun elementCount(): Int = insertedCount

    /**
     * Serialize to byte array: [bitSize (int), numHashFunctions (int), bits.toByteArray()]
     */
    fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(bitSize)
            dos.writeInt(numHashFunctions)
            val bitsBytes = bits.toByteArray()
            dos.writeInt(bitsBytes.size)
            dos.write(bitsBytes)
        }
        return baos.toByteArray()
    }

    companion object {
        fun create(expectedInsertions: Int, falsePositiveRate: Double = 0.0000001): BloomFilter {
            val n = expectedInsertions.coerceAtLeast(1)
            val p = falsePositiveRate.coerceIn(1e-9, 0.5)
            val m = (-(n * ln(p)) / (ln(2.0) * ln(2.0))).roundToInt().coerceAtLeast(64)
            val k = ((m.toDouble() / n) * ln(2.0)).roundToInt().coerceIn(1, 30)
            return BloomFilter(m, k)
        }

        fun fromBytes(bytes: ByteArray): BloomFilter {
            ByteArrayInputStream(bytes).use { bais ->
                DataInputStream(bais).use { dis ->
                    val bitSize = dis.readInt()
                    val numHashFunctions = dis.readInt()
                    val bitsLength = dis.readInt()
                    val bitsBytes = ByteArray(bitsLength)
                    dis.readFully(bitsBytes)

                    val filter = BloomFilter(bitSize, numHashFunctions)
                    filter.bits.clear()
                    filter.bits.or(BitSet.valueOf(bitsBytes))
                    // insertedCount giữ nguyên 0 (không cần thiết)
                    return filter
                }
            }
        }

        fun empty(): BloomFilter = BloomFilter(64, 1)

        private fun positiveMod(x: Int, m: Int): Int {
            val r = x % m
            return if (r < 0) r + m else r
        }

        private fun hashPair(s: String): Pair<Int, Int> {
            val b = s.toByteArray(Charsets.UTF_8)
            val h1 = murmur3_32(b, seed = 0x9747b28c.toInt())
            val h2 = murmur3_32(b, seed = 0x1b873593)
            return h1 to (if (h2 == 0) 0x5bd1e995 else h2)
        }

        private fun murmur3_32(data: ByteArray, seed: Int): Int {
            var h1 = seed
            val c1 = 0xcc9e2d51.toInt()
            val c2 = 0x1b873593

            var i = 0
            while (i + 3 < data.size) {
                var k1 = (data[i].toInt() and 0xff) or
                        ((data[i + 1].toInt() and 0xff) shl 8) or
                        ((data[i + 2].toInt() and 0xff) shl 16) or
                        ((data[i + 3].toInt() and 0xff) shl 24)

                k1 *= c1
                k1 = (k1 shl 15) or (k1 ushr 17)
                k1 *= c2

                h1 = h1 xor k1
                h1 = (h1 shl 13) or (h1 ushr 19)
                h1 = h1 * 5 + 0xe6546b64.toInt()
                i += 4
            }

            var k1 = 0
            val remaining = data.size - i
            if (remaining == 3) k1 = k1 xor ((data[i + 2].toInt() and 0xff) shl 16)
            if (remaining >= 2) k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
            if (remaining >= 1) {
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 *= c1
                k1 = (k1 shl 15) or (k1 ushr 17)
                k1 *= c2
                h1 = h1 xor k1
            }

            h1 = h1 xor data.size
            h1 = h1 xor (h1 ushr 16)
            h1 *= 0x85ebca6b.toInt()
            h1 = h1 xor (h1 ushr 13)
            h1 *= 0xc2b2ae35.toInt()
            h1 = h1 xor (h1 ushr 16)
            return h1
        }
    }
}