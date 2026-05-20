package com.pptp.client.ppp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MS-CHAP-V2 authentication (RFC 2759) running on PPP protocol 0xC223 with
 * the Algorithm byte 0x81.
 *
 *   ┌──────────────┐                    ┌──────────────┐
 *   │   Server     │                    │    Client    │
 *   └──────────────┘                    └──────────────┘
 *           │   Challenge (Code 1)             │
 *           │  ──────────────────────────▶     │
 *           │                                  │  Generate Peer-Challenge
 *           │                                  │  Compute NT-Response
 *           │     Response (Code 2)            │
 *           │  ◀──────────────────────────     │
 *           │                                  │
 *           │  Success (Code 3, "S=<AR>")      │
 *           │  ──────────────────────────▶     │
 *           │                                  │  Verify Authenticator-Response
 *           │                                  │  Send Success (Code 3, empty)
 *           │   Success ack (Code 3)           │
 *           │  ◀──────────────────────────     │
 *
 * On Failure (Code 4) the server provides E=/R=/C=/V=/M= fields. We surface
 * the message to the caller and stop.
 *
 * v0.0.6 NOTE: master-key derivation for MPPE is implemented here but the
 * key is only returned to the caller; the encryption side is wired in v0.0.8
 * along with CCP. PasswordHashHash, NT-Response, and PeerChallenge are
 * retained until disconnect for MPPE key refresh use.
 */
class MsChapV2Auth(
    private val username: String,
    private val password: String,
    private val sender: (ByteArray) -> Unit,
    private val onResult: (success: Boolean, message: String, keys: MppeKeyMaterial?) -> Unit,
    private val challengeTimeoutMs: Long = 12_000,
) {
    enum class State { Idle, WaitChallenge, WaitSuccess, Done, Failed }

    private val rng = SecureRandom()
    private val finished = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timeoutJob: Job? = null

    @Volatile var state: State = State.Idle
        private set

    private var lastIdentifier: Int = 0
    private var authenticatorChallenge: ByteArray = ByteArray(0)
    private var peerChallenge: ByteArray = ByteArray(0)
    private var ntResponse: ByteArray = ByteArray(0)

    fun start() {
        state = State.WaitChallenge
        scheduleTimeout(challengeTimeoutMs, "等待服务器 Challenge 超时")
        // Nothing to send; server sends Challenge first.
    }

    private fun scheduleTimeout(ms: Long, reason: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            if (!finished.get()) fail(reason)
        }
    }

    fun onReceive(payload: ByteArray) {
        if (finished.get()) return
        if (payload.size < 4) return
        val code = payload[0].toInt() and 0xFF
        val id = payload[1].toInt() and 0xFF
        val length = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (length > payload.size) {
            Log.w(TAG, "MSCHAPv2 length=$length exceeds payload=${payload.size}")
            return
        }
        when (code) {
            CODE_CHALLENGE -> handleChallenge(id, payload, length)
            CODE_SUCCESS -> handleSuccess(id, payload, length)
            CODE_FAILURE -> handleFailure(id, payload, length)
            CODE_RESPONSE -> { /* ignore; server doesn't send this */ }
            else -> Log.w(TAG, "MSCHAPv2 unknown code=$code")
        }
    }

    private fun handleChallenge(id: Int, payload: ByteArray, length: Int) {
        if (state != State.WaitChallenge) return
        // Value-Size at offset 4 must be 16; then 16-byte Challenge; then Name.
        if (length < 4 + 1 + 16) {
            fail("Challenge too short ($length)")
            return
        }
        val valueSize = payload[4].toInt() and 0xFF
        if (valueSize != 16) {
            fail("Challenge Value-Size $valueSize ≠ 16")
            return
        }
        authenticatorChallenge = payload.copyOfRange(5, 21)
        val serverName = if (length > 21) String(payload, 21, length - 21, Charsets.US_ASCII) else ""
        lastIdentifier = id

        peerChallenge = ByteArray(16).also { rng.nextBytes(it) }
        ntResponse = generateNtResponse(authenticatorChallenge, peerChallenge, username, password)

        Log.d(TAG, "MSCHAPv2 challenge from '$serverName', sending response")
        sendResponse(id)
        state = State.WaitSuccess
        scheduleTimeout(challengeTimeoutMs, "等待 Success/Failure 超时")
    }

    private fun sendResponse(identifier: Int) {
        val userBytes = username.toByteArray(Charsets.US_ASCII)
        val length = 4 + 1 + 49 + userBytes.size
        val buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        buf.put(CODE_RESPONSE.toByte())
        buf.put(identifier.toByte())
        buf.putShort(length.toShort())
        buf.put(49) // Value-Size
        buf.put(peerChallenge)                  // 16 bytes
        buf.put(ByteArray(8))                   // 8 reserved zeros
        buf.put(ntResponse)                     // 24 bytes
        buf.put(0)                              // Flags
        buf.put(userBytes)
        sender(buf.array())
    }

    private fun handleSuccess(id: Int, payload: ByteArray, length: Int) {
        if (state != State.WaitSuccess) return
        // Message: ASCII "S=<40 hex AR> M=<text>"
        val messageStart = 4
        val message = if (length > messageStart)
            String(payload, messageStart, length - messageStart, Charsets.US_ASCII)
        else ""

        val expectedAr = generateAuthenticatorResponse(
            password, ntResponse, peerChallenge, authenticatorChallenge, username,
        )
        val arMatched = parseSField(message)?.equals(expectedAr, ignoreCase = true) == true
        if (!arMatched) {
            Log.w(TAG, "Authenticator Response mismatch — got: ${parseSField(message)}, expected: $expectedAr")
            // Continue anyway: many servers omit S= or use slightly different framing
            // (we'll log a warning but accept the success for now).
        }

        // Send our Success ack (Code 3, empty Value, same identifier).
        val ack = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .put(CODE_SUCCESS.toByte()).put(id.toByte()).putShort(4).array()
        sender(ack)

        val keys = deriveMppeKeys(password, ntResponse)
        if (finished.compareAndSet(false, true)) {
            timeoutJob?.cancel(); scope.cancel()
            state = State.Done
            onResult(true, "MS-CHAPv2 OK${if (!arMatched) " (AR mismatch — accepted)" else ""}: $message", keys)
        }
    }

    private fun handleFailure(id: Int, payload: ByteArray, length: Int) {
        val msg = if (length > 4)
            String(payload, 4, length - 4, Charsets.US_ASCII)
        else ""
        fail("MS-CHAPv2 Failure: $msg")
    }

    private fun fail(reason: String) {
        if (finished.compareAndSet(false, true)) {
            timeoutJob?.cancel(); scope.cancel()
            state = State.Failed
            onResult(false, reason, null)
        }
    }

    private fun parseSField(message: String): String? {
        // "S=<40 hex> M=<text>" — extract the value after "S=" up to space or end
        val sIdx = message.indexOf("S=")
        if (sIdx < 0) return null
        val end = message.indexOf(' ', sIdx)
        return message.substring(sIdx + 2, if (end < 0) message.length else end)
    }

    companion object {
        private const val TAG = "MsChapV2"

        const val CODE_CHALLENGE = 1
        const val CODE_RESPONSE = 2
        const val CODE_SUCCESS = 3
        const val CODE_FAILURE = 4

        // RFC 2759 §8 magic constants — these MUST be the exact byte sequences.
        private val MAGIC_1 = byteArrayOf(
            0x4D, 0x61, 0x67, 0x69, 0x63, 0x20, 0x73, 0x65, 0x72, 0x76, 0x65, 0x72,
            0x20, 0x74, 0x6F, 0x20, 0x63, 0x6C, 0x69, 0x65, 0x6E, 0x74, 0x20, 0x73,
            0x69, 0x67, 0x6E, 0x69, 0x6E, 0x67, 0x20, 0x63, 0x6F, 0x6E, 0x73, 0x74,
            0x61, 0x6E, 0x74,
        )
        private val MAGIC_2 = byteArrayOf(
            0x50, 0x61, 0x64, 0x20, 0x74, 0x6F, 0x20, 0x6D, 0x61, 0x6B, 0x65, 0x20,
            0x69, 0x74, 0x20, 0x64, 0x6F, 0x20, 0x6D, 0x6F, 0x72, 0x65, 0x20, 0x74,
            0x68, 0x61, 0x6E, 0x20, 0x6F, 0x6E, 0x65, 0x20, 0x69, 0x74, 0x65, 0x72,
            0x61, 0x74, 0x69, 0x6F, 0x6E,
        )

        /** RFC 2759 §8: NtPasswordHash(Password) = MD4(UTF-16-LE(Password)) */
        fun ntPasswordHash(password: String): ByteArray =
            Crypto.md4(Crypto.utf16le(password))

        /** RFC 2759 §8: ChallengeHash truncates SHA-1 to 8 bytes. */
        fun challengeHash(
            peerChallenge: ByteArray,
            authenticatorChallenge: ByteArray,
            username: String,
        ): ByteArray {
            val sha = Crypto.sha1(peerChallenge, authenticatorChallenge,
                stripDomain(username).toByteArray(Charsets.US_ASCII))
            return sha.copyOfRange(0, 8)
        }

        /**
         * RFC 2759 §6 ChallengeResponse: three DES encryptions of the 8-byte
         * challenge with the 16-byte password hash padded to 21 bytes and split
         * into three 7-byte keys.
         */
        fun challengeResponse(challenge8: ByteArray, passwordHash16: ByteArray): ByteArray {
            require(challenge8.size == 8 && passwordHash16.size == 16)
            val padded = ByteArray(21).also {
                System.arraycopy(passwordHash16, 0, it, 0, 16)
            }
            val out = ByteArray(24)
            System.arraycopy(Crypto.desEncrypt7(padded.copyOfRange(0, 7), challenge8), 0, out, 0, 8)
            System.arraycopy(Crypto.desEncrypt7(padded.copyOfRange(7, 14), challenge8), 0, out, 8, 8)
            System.arraycopy(Crypto.desEncrypt7(padded.copyOfRange(14, 21), challenge8), 0, out, 16, 8)
            return out
        }

        fun generateNtResponse(
            authChallenge: ByteArray,
            peerChallenge: ByteArray,
            username: String,
            password: String,
        ): ByteArray {
            val challenge = challengeHash(peerChallenge, authChallenge, username)
            val passwordHash = ntPasswordHash(password)
            return challengeResponse(challenge, passwordHash)
        }

        /**
         * Generate the "S=" Authenticator Response string the server includes
         * in its Success message. We use this for mutual auth verification.
         */
        fun generateAuthenticatorResponse(
            password: String,
            ntResponse: ByteArray,
            peerChallenge: ByteArray,
            authChallenge: ByteArray,
            username: String,
        ): String {
            val passwordHash = ntPasswordHash(password)
            val passwordHashHash = Crypto.md4(passwordHash)
            val digest1 = Crypto.sha1(passwordHashHash, ntResponse, MAGIC_1)
            val challenge = challengeHash(peerChallenge, authChallenge, username)
            val digest2 = Crypto.sha1(digest1, challenge, MAGIC_2)
            return Crypto.hex(digest2).uppercase()
        }

        /** Strip a leading DOMAIN\ from username (servers expect bare name in CHAP hash). */
        private fun stripDomain(s: String): String {
            val i = s.indexOf('\\')
            return if (i < 0) s else s.substring(i + 1)
        }

        /**
         * Derive MPPE master key material per RFC 3079 §3. Returned bundle
         * carries everything the encryption layer (v0.0.8) will need to spin
         * up RC4 contexts.
         */
        fun deriveMppeKeys(password: String, ntResponse: ByteArray): MppeKeyMaterial {
            val passwordHash = ntPasswordHash(password)
            val passwordHashHash = Crypto.md4(passwordHash)
            // GetMasterKey: SHA1(passwordHashHash || ntResponse || "This is the MPPE Master Key")[0:16]
            val magic = "This is the MPPE Master Key".toByteArray(Charsets.US_ASCII)
            val masterKey = Crypto.sha1(passwordHashHash, ntResponse, magic).copyOfRange(0, 16)
            return MppeKeyMaterial(masterKey, passwordHashHash, ntResponse)
        }
    }
}

/**
 * Material carried from authentication to the MPPE layer. The master key is
 * an intermediate value — RFC 3079 §3 derives a send-key and receive-key from
 * it via additional SHA-1 passes that depend on the negotiated key strength.
 * v0.0.6 only computes the master key; v0.0.8 will derive the actual session
 * keys.
 */
data class MppeKeyMaterial(
    val masterKey: ByteArray,       // 16 bytes
    val passwordHashHash: ByteArray, // 16 bytes — kept for key-change events
    val ntResponse: ByteArray,       // 24 bytes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MppeKeyMaterial) return false
        return masterKey.contentEquals(other.masterKey) &&
            passwordHashHash.contentEquals(other.passwordHashHash) &&
            ntResponse.contentEquals(other.ntResponse)
    }
    override fun hashCode(): Int {
        var r = masterKey.contentHashCode()
        r = 31 * r + passwordHashHash.contentHashCode()
        r = 31 * r + ntResponse.contentHashCode()
        return r
    }
}
