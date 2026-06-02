// SPDX-License-Identifier: GPL-3.0-or-later
package me.jinsei.pptp.ppp

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives needed by MS-CHAP-V2 (RFC 2759) and MPPE
 * (RFC 3078/3079).
 *
 *   MD4   — self-implemented per RFC 1320. The Java/Android crypto provider
 *           dropped reliable MD4 support by 2026; rolling our own keeps the
 *           project independent of provider quirks.
 *   SHA-1 — via java.security.MessageDigest. Still universally available.
 *   DES   — via javax.crypto.Cipher("DES/ECB/NoPadding"). Still available on
 *           Android, used only for ChallengeResponse hashing (not for
 *           encryption-at-rest).
 *
 * Everything operates on raw ByteArrays; no String charsets unless documented.
 */
object Crypto {

    // -------------------- MD4 (RFC 1320) --------------------

    fun md4(input: ByteArray): ByteArray {
        // 1. Pad: append 0x80, then zeros until length ≡ 56 (mod 64), then 8-byte LE length-in-bits.
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val padded = ByteArray(input.size + 1 + padLen + 8)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = 0x80.toByte()
        // 8-byte little-endian bit length at the end:
        var idx = padded.size - 8
        var bl = bitLen
        for (i in 0 until 8) { padded[idx + i] = (bl and 0xFF).toByte(); bl = bl ushr 8 }

        // 2. Initialize state.
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476

        // 3. Process each 64-byte block.
        val x = IntArray(16)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                x[i] = (padded[off + i * 4].toInt() and 0xFF) or
                    ((padded[off + i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((padded[off + i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((padded[off + i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d
            // Round 1: f(x,y,z) = (x AND y) OR ((NOT x) AND z); constant = 0
            for (i in 0 until 16) {
                val (vars, sx) = round1Index(i)
                val (k, s) = vars to sx
                val f = (b and c) or (b.inv() and d)
                // We'll inline the variable rotation pattern below for clarity. (See loop unroll.)
                when (i % 4) {
                    0 -> a = rotl(a + f + x[k], s)
                    1 -> d = rotl(d + ((a and b) or (a.inv() and c)) + x[k], s)
                    2 -> c = rotl(c + ((d and a) or (d.inv() and b)) + x[k], s)
                    3 -> b = rotl(b + ((c and d) or (c.inv() and a)) + x[k], s)
                }
            }
            // Round 2: g(x,y,z) = (x AND y) OR (x AND z) OR (y AND z); constant = 0x5A827999
            val r2k = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
            val r2s = intArrayOf(3, 5, 9, 13)
            for (i in 0 until 16) {
                val k = r2k[i]
                val s = r2s[i % 4]
                when (i % 4) {
                    0 -> a = rotl(a + ((b and c) or (b and d) or (c and d)) + x[k] + 0x5A827999, s)
                    1 -> d = rotl(d + ((a and b) or (a and c) or (b and c)) + x[k] + 0x5A827999, s)
                    2 -> c = rotl(c + ((d and a) or (d and b) or (a and b)) + x[k] + 0x5A827999, s)
                    3 -> b = rotl(b + ((c and d) or (c and a) or (d and a)) + x[k] + 0x5A827999, s)
                }
            }
            // Round 3: h(x,y,z) = x XOR y XOR z; constant = 0x6ED9EBA1
            val r3k = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)
            val r3s = intArrayOf(3, 9, 11, 15)
            for (i in 0 until 16) {
                val k = r3k[i]
                val s = r3s[i % 4]
                when (i % 4) {
                    0 -> a = rotl(a + (b xor c xor d) + x[k] + 0x6ED9EBA1.toInt(), s)
                    1 -> d = rotl(d + (a xor b xor c) + x[k] + 0x6ED9EBA1.toInt(), s)
                    2 -> c = rotl(c + (d xor a xor b) + x[k] + 0x6ED9EBA1.toInt(), s)
                    3 -> b = rotl(b + (c xor d xor a) + x[k] + 0x6ED9EBA1.toInt(), s)
                }
            }
            a += aa; b += bb; c += cc; d += dd
            off += 64
        }

        // 4. Output as little-endian 16 bytes.
        val out = ByteArray(16)
        for ((i, v) in listOf(a, b, c, d).withIndex()) {
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = (v ushr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (v ushr 16 and 0xFF).toByte()
            out[i * 4 + 3] = (v ushr 24 and 0xFF).toByte()
        }
        return out
    }

    private fun rotl(v: Int, s: Int): Int = (v shl s) or (v ushr (32 - s))

    private fun round1Index(i: Int): Pair<Int, Int> {
        val k = i  // round 1 reads x[0..15] in order
        val s = when (i % 4) { 0 -> 3; 1 -> 7; 2 -> 11; 3 -> 19; else -> 0 }
        return k to s
    }

    // -------------------- SHA-1 --------------------

    fun sha1(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) md.update(p)
        return md.digest()
    }

    // -------------------- DES (ECB, single block) --------------------

    /**
     * DES-encrypt a single 8-byte plaintext block with the given 7-byte key.
     * The key is "expanded" by adding the standard parity bits (RFC 2759 §6
     * DesEncrypt step).
     */
    fun desEncrypt7(key7: ByteArray, plaintext8: ByteArray): ByteArray {
        require(key7.size == 7) { "DES key must be 7 bytes (we add parity)" }
        require(plaintext8.size == 8) { "DES block must be 8 bytes" }
        val key8 = expand7To8(key7)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key8, "DES"))
        return cipher.doFinal(plaintext8)
    }

    /** Distribute 7 bytes (56 bits) into 8 bytes by inserting a parity bit every 7. */
    private fun expand7To8(k7: ByteArray): ByteArray {
        val out = ByteArray(8)
        out[0] = (k7[0].toInt() and 0xFE).toByte()
        out[1] = (((k7[0].toInt() and 0x01) shl 7) or ((k7[1].toInt() and 0xFE) ushr 1)).toByte()
        out[2] = (((k7[1].toInt() and 0x03) shl 6) or ((k7[2].toInt() and 0xFC) ushr 2)).toByte()
        out[3] = (((k7[2].toInt() and 0x07) shl 5) or ((k7[3].toInt() and 0xF8) ushr 3)).toByte()
        out[4] = (((k7[3].toInt() and 0x0F) shl 4) or ((k7[4].toInt() and 0xF0) ushr 4)).toByte()
        out[5] = (((k7[4].toInt() and 0x1F) shl 3) or ((k7[5].toInt() and 0xE0) ushr 5)).toByte()
        out[6] = (((k7[5].toInt() and 0x3F) shl 2) or ((k7[6].toInt() and 0xC0) ushr 6)).toByte()
        out[7] = ((k7[6].toInt() and 0x7F) shl 1).toByte()
        // Bottom bit is parity; DES ignores it but some implementations check. Compute even parity.
        for (i in 0 until 8) {
            var b = out[i].toInt() and 0xFE
            var ones = 0
            var t = b
            while (t != 0) { ones += t and 1; t = t ushr 1 }
            out[i] = (b or (1 - (ones and 1))).toByte()
        }
        return out
    }

    // -------------------- Encoding helpers --------------------

    /** UTF-16 LE encoding without BOM — what NtPasswordHash() expects (RFC 2759 §8). */
    fun utf16le(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)

    /** Constant-time-ish hex dump for logs. */
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
