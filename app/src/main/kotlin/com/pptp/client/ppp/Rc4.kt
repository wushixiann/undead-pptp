package com.pptp.client.ppp

/**
 * Pure-Kotlin RC4 stream cipher.
 *
 * Reasons we don't use `javax.crypto.Cipher("RC4")`:
 *   1. By 2026 multiple Android security providers had dropped RC4 from
 *      default builds (Conscrypt removed it before then; BoringSSL even
 *      earlier).
 *   2. MPPE needs frequent key restarts in stateless mode; constructing a
 *      new Cipher per packet through the JCE machinery is heavier than
 *      a 256-byte permutation init.
 *   3. 40 lines of well-trodden algorithm. Easy to keep correct.
 */
class Rc4(key: ByteArray) {

    private val s = IntArray(256) { it }
    private var i = 0
    private var j = 0

    init {
        require(key.isNotEmpty()) { "RC4 key must be non-empty" }
        var jj = 0
        for (idx in 0 until 256) {
            jj = (jj + s[idx] + (key[idx % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[idx]; s[idx] = s[jj]; s[jj] = t
        }
    }

    /** Encrypt or decrypt [data] in place. RC4 is symmetric. */
    fun process(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var ii = i; var jj = j
        val end = offset + length
        for (k in offset until end) {
            ii = (ii + 1) and 0xFF
            jj = (jj + s[ii]) and 0xFF
            val t = s[ii]; s[ii] = s[jj]; s[jj] = t
            val keyByte = s[(s[ii] + s[jj]) and 0xFF]
            data[k] = (data[k].toInt() xor keyByte).toByte()
        }
        i = ii; j = jj
    }

    /** One-shot helper for clarity at call sites that don't want in-place. */
    fun apply(data: ByteArray): ByteArray {
        val out = data.copyOf()
        process(out)
        return out
    }
}
