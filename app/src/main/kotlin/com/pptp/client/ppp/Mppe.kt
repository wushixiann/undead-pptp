package com.pptp.client.ppp

import android.util.Log

/**
 * MPPE (Microsoft Point-to-Point Encryption) per RFC 3078 + RFC 3079.
 *
 * v0.0.8 implements **stateless 128-bit only** — the most common mode used
 * by Windows RRAS, accel-ppp, MikroTik, etc. Stateful mode is more efficient
 * but requires a more elaborate coherency-count window handshake.
 *
 * Per-packet flow (stateless mode):
 *
 *   sender:
 *     - increment 12-bit coherency count
 *     - derive new session key from initial key + current key (RFC 3078 §7.7)
 *     - re-init RC4 with new key
 *     - write MPPE header: A=1 (flushed), B=0, C=0, D=1 (encrypted), CC
 *     - encrypt PPP protocol+payload starting from where header ends
 *
 *   receiver:
 *     - read MPPE header
 *     - if coherency count != expected → send CCP Reset-Request, drop packet
 *     - re-derive recv key (mirror of sender), re-init RC4
 *     - decrypt payload
 *
 * Asymmetric send/recv keys are derived once at session start using the
 * MS-CHAPv2 master key (RFC 3079 §3.4). The "current" key starts equal to
 * the initial key and gets rotated per packet.
 *
 * Wire layout of an MPPE-encrypted PPP frame:
 *
 *   PPP-Protocol(0x00FD) || ABCD+CC(2B) || encrypted(PPP-Protocol + payload)
 *
 * Note that the *inner* PPP protocol field (typically 0x0021 for IPv4) is
 * encrypted along with the data, so the receiver must decrypt before
 * dispatching by inner protocol.
 */
class Mppe(
    private val masterKey: ByteArray,
    private val isServer: Boolean = false,
) {

    val sendInitialKey: ByteArray = getAsymmetricStartKey(masterKey, KEY_LEN_BYTES, isSend = true, isServer = isServer)
    val recvInitialKey: ByteArray = getAsymmetricStartKey(masterKey, KEY_LEN_BYTES, isSend = false, isServer = isServer)

    /**
     * "Base" key after the one-time initial rekey (RFC 3078 §7.7 done with
     * NO subsequent RC4, equivalent to pppd's `mppe_rekey(initial=1)`).
     *
     * pppd's flow:
     *   1. session_key = asymmetric_start_key
     *   2. mppe_rekey(initial=1): session_key = SHA1(asym || pad1 || asym || pad2)[0:16]
     *      (no RC4 self-encrypt on initial rekey)
     *   3. Per-packet mppe_rekey(initial=0):
     *      InterimKey = SHA1(asym || pad1 || session_key || pad2)[0:16]
     *      session_key = RC4(InterimKey, InterimKey)
     *      (now used to encrypt this packet)
     *
     * So cc=N packet uses (1 SHA1) + (N+1 SHA1+RC4) rotations from asym.
     * Earlier client missed step 2 → our key sequence was offset by one
     * SHA1 transform from pppd's, breaking decryption irrecoverably.
     */
    private val sendBaseKey: ByteArray = sha1Only(sendInitialKey, sendInitialKey)
    private val recvBaseKey: ByteArray = sha1Only(recvInitialKey, recvInitialKey)

    init {
        // Key fingerprints only at DEBUG to avoid leaking material to default logcat.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "MPPE init: master=${prefix(masterKey)} sendInit=${prefix(sendInitialKey)} recvInit=${prefix(recvInitialKey)}")
            Log.d(TAG, "MPPE base: send=${prefix(sendBaseKey)} recv=${prefix(recvBaseKey)}")
        }
    }

    private fun prefix(k: ByteArray): String = k.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private var sendCurrent: ByteArray = sendBaseKey.copyOf()
    private var recvCurrent: ByteArray = recvBaseKey.copyOf()

    private var sendCC: Int = 0    // 12-bit coherency count (0..0xFFF)
    /** Last successfully-processed receive CC; -1 means "no packets yet". */
    private var lastRecvCc: Int = -1

    @Volatile var lastReceivedCC: Int = -1
        private set

    @Synchronized
    fun encrypt(pppProtoAndPayload: ByteArray): ByteArray {
        // Rotate the send key BEFORE this packet's CC.
        // (v0.2.3 added an extra rekey at every 256th packet under the theory
        // that pppd does it — but that's only the stateful-mode path. In
        // stateless mode pppd rotates exactly once per packet, period. The
        // extra rekey we added made our send key diverge from the server's
        // receive key starting at packet 256, server saw garbage inner
        // protocol after MPPE decrypt and burst-Protocol-Rejected us.)
        sendCurrent = nextKey(sendInitialKey, sendCurrent)
        val rc4 = Rc4(sendCurrent)
        val encrypted = pppProtoAndPayload.copyOf()
        rc4.process(encrypted)

        val cc = sendCC
        sendCC = (sendCC + 1) and 0xFFF

        // Header byte 0 layout (RFC 3078 §2): A B C D CC11 CC10 CC9 CC8
        //   A (0x80) = Flushed   — required every packet in stateless mode
        //   B (0x40) = unused for MPPE-only (was MPPC-specific)
        //   C (0x20) = unused
        //   D (0x10) = Encrypted — must be 1, otherwise server treats payload as plaintext
        // We must set A | D = 0x90. Earlier code wrote 0xA0 (A | C) which left D=0,
        // causing servers to mis-interpret our packets as MPPC-compressed plaintext.
        val flagsAndCcHi = ((0x90 or ((cc shr 8) and 0x0F)) and 0xFF)
        val ccLo = cc and 0xFF
        val out = ByteArray(2 + encrypted.size)
        out[0] = flagsAndCcHi.toByte()
        out[1] = ccLo.toByte()
        System.arraycopy(encrypted, 0, out, 2, encrypted.size)
        if (cc < 4 && Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "encrypt cc=$cc key=${prefix(sendCurrent)} plain[0..3]=${prefix(pppProtoAndPayload)}")
        }
        return out
    }

    /**
     * Decrypt an MPPE-encrypted packet.
     *
     * The key for a packet with coherency count cc is the initial key rotated
     * (cc + 1) times. Earlier code rotated once per receive — which assumes
     * sequential, unbroken stream starting at cc=0. After a CCP Reset-Request
     * the server's CC continues from wherever it was, but our state was
     * starting from scratch each time → guaranteed key mismatch and garbage
     * decryption. We now derive the key from cc directly, with an incremental
     * cache so the common sequential case stays cheap.
     *
     * Returns null on truly malformed packets.
     */
    @Synchronized
    fun decrypt(packet: ByteArray): ByteArray? {
        if (packet.size < 3) {
            Log.w(TAG, "MPPE packet too short (${packet.size})")
            return null
        }
        val flagsCcHi = packet[0].toInt() and 0xFF
        val ccLo = packet[1].toInt() and 0xFF
        val cc = ((flagsCcHi and 0x0F) shl 8) or ccLo
        val flushed = flagsCcHi and 0x80 != 0
        val encrypted = flagsCcHi and 0x10 != 0
        if (!encrypted) {
            Log.w(TAG, "MPPE D bit not set (flags=0x${"%02x".format(flagsCcHi)}) — dropping")
            return null
        }
        lastReceivedCC = cc

        // Per RFC 3078 §8.1 (and Linux kernel ppp_mppe.c sanity checks): in stateless
        // mode the Flushed bit MUST be set on every packet. A packet without it is
        // malformed and the kernel adds +100 to sanity_errors. We drop it outright.
        if (!flushed) {
            Log.w(TAG, "MPPE A bit not set in stateless mode (cc=$cc) — dropping")
            return null
        }

        if (!advanceRecvKeyTo(cc)) {
            // Late / out-of-order / duplicate packet — drop silently without
            // perturbing recvCurrent. Subsequent in-window packets must still
            // decode correctly.
            return null
        }

        val rc4 = Rc4(recvCurrent)
        val payload = packet.copyOfRange(2, packet.size)
        rc4.process(payload)
        if (cc < 4 && Log.isLoggable(TAG, Log.DEBUG)) {
            // First few packets: 8-byte plaintext dump. Expected for IPv4:
            //   00 21 45 xx ..  (uncompressed protocol)  or
            //   21 45 xx ..     (PFC-compressed)
            val dump = payload.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            Log.d(TAG, "decrypt cc=$cc key=${prefix(recvCurrent)} → first8=$dump")
        }
        return payload
    }

    /**
     * Advance recvCurrent so it matches the per-packet key for [targetCc].
     *
     * **Returns false** if the packet should be silently dropped (late or
     * duplicate). On false, [recvCurrent] and [lastRecvCc] are NOT modified
     * — subsequent in-window packets must still decode correctly.
     *
     * Algorithm mirrors Linux kernel `drivers/net/ppp/ppp_mppe.c`
     * `mppe_decompress` for the stateless path:
     *
     * ```c
     * if ((ccount - state->ccount) % MPPE_CCOUNT_SPACE > MPPE_CCOUNT_SPACE / 2) {
     *     state->sanity_errors++;
     *     goto sanity_error;    // discard late/out-of-order packet
     * }
     * while (state->ccount != ccount) {
     *     mppe_rekey(state, 0);
     *     state->ccount = (state->ccount + 1) % MPPE_CCOUNT_SPACE;
     * }
     * ```
     *
     * The key insight (which v0.2.5 and earlier got wrong): a forward delta of
     * e.g. 4094 in a 12-bit counter is almost certainly NOT "we lost 4094
     * packets" — it's a single out-of-order packet arriving 2 slots before the
     * latest one we processed. Catching-up rekey 4094 times in that case would
     * irrecoverably scramble our key stream. The kernel's modulo-half-space
     * test discards such packets and the recv stream self-heals on the next
     * in-order packet.
     */
    private fun advanceRecvKeyTo(targetCc: Int): Boolean {
        if (lastRecvCc < 0) {
            // First packet ever — rotate from base (targetCc + 1) times.
            recvCurrent = recvBaseKey.copyOf()
            for (k in 0..targetCc) {
                recvCurrent = nextKey(recvInitialKey, recvCurrent)
            }
            lastRecvCc = targetCc
            return true
        }
        val delta = (targetCc - lastRecvCc) and 0xFFF
        if (delta == 0) {
            // Duplicate of the packet we just processed — drop.
            return false
        }
        if (delta > MPPE_CCOUNT_SPACE / 2) {
            // Late / out-of-order packet (the kernel's "discard late packet"
            // path). Do NOT rotate the recv key — leave state untouched.
            Log.w(TAG, "MPPE late/out-of-order packet cc=$targetCc lastCc=$lastRecvCc delta=$delta — dropping")
            return false
        }
        if (delta > 64) {
            // Genuine loss of `delta-1` packets. Recoverable as long as
            // delta < 2048 (MPPE_CCOUNT_SPACE/2). Log so we can see if this
            // becomes frequent — usually means UDS bridge ring overflow.
            Log.w(TAG, "MPPE recv: large forward delta=$delta (lastCc=$lastRecvCc → targetCc=$targetCc) — recovering")
        }
        for (k in 0 until delta) {
            recvCurrent = nextKey(recvInitialKey, recvCurrent)
        }
        lastRecvCc = targetCc
        return true
    }

    /**
     * Reset after a SERVER-initiated CCP Reset-Request reaches us. Per pppd
     * semantics, this means the server's receiver lost sync — it asks us
     * (the sender) to flush and re-start. We reset SEND only; server's
     * sender hasn't reset, so our RECV state stays put.
     *
     * In v0.2.6 we no longer initiate Reset-Requests ourselves — stateless
     * MPPE self-heals via the late-packet test in [advanceRecvKeyTo], and
     * pppd's behavior on receiving Reset-Request in stateless mode is
     * under-specified, so trying to use it as a recovery mechanism caused
     * more problems than it solved (see CHANGELOG v0.2.5 → v0.2.6).
     */
    @Synchronized
    fun resetForServerRequest() {
        sendCurrent = sendBaseKey.copyOf()
        sendCC = 0
    }

    companion object {
        private const val TAG = "Mppe"
        const val KEY_LEN_BYTES = 16 // 128-bit only for v0.0.8

        /** 12-bit coherency count space (RFC 3078 §2). */
        private const val MPPE_CCOUNT_SPACE = 0x1000

        /**
         * RFC 3079 §3.4 Magic 2: text used for the CLIENT→SERVER direction
         * ("On the client side, this is the send key; on the server side,
         *  it is the receive key.").
         */
        private val MAGIC_CLIENT_TO_SERVER = byteArrayOf(
            0x4F, 0x6E, 0x20, 0x74, 0x68, 0x65, 0x20, 0x63, 0x6C, 0x69, 0x65, 0x6E,
            0x74, 0x20, 0x73, 0x69, 0x64, 0x65, 0x2C, 0x20, 0x74, 0x68, 0x69, 0x73,
            0x20, 0x69, 0x73, 0x20, 0x74, 0x68, 0x65, 0x20, 0x73, 0x65, 0x6E, 0x64,
            0x20, 0x6B, 0x65, 0x79, 0x3B, 0x20, 0x6F, 0x6E, 0x20, 0x74, 0x68, 0x65,
            0x20, 0x73, 0x65, 0x72, 0x76, 0x65, 0x72, 0x20, 0x73, 0x69, 0x64, 0x65,
            0x2C, 0x20, 0x69, 0x74, 0x20, 0x69, 0x73, 0x20, 0x74, 0x68, 0x65, 0x20,
            0x72, 0x65, 0x63, 0x65, 0x69, 0x76, 0x65, 0x20, 0x6B, 0x65, 0x79, 0x2E,
        )
        /**
         * RFC 3079 §3.4 Magic 3: text used for the SERVER→CLIENT direction
         * ("On the client side, this is the receive key; on the server side,
         *  it is the send key.").
         */
        private val MAGIC_SERVER_TO_CLIENT = byteArrayOf(
            0x4F, 0x6E, 0x20, 0x74, 0x68, 0x65, 0x20, 0x63, 0x6C, 0x69, 0x65, 0x6E,
            0x74, 0x20, 0x73, 0x69, 0x64, 0x65, 0x2C, 0x20, 0x74, 0x68, 0x69, 0x73,
            0x20, 0x69, 0x73, 0x20, 0x74, 0x68, 0x65, 0x20, 0x72, 0x65, 0x63, 0x65,
            0x69, 0x76, 0x65, 0x20, 0x6B, 0x65, 0x79, 0x3B, 0x20, 0x6F, 0x6E, 0x20,
            0x74, 0x68, 0x65, 0x20, 0x73, 0x65, 0x72, 0x76, 0x65, 0x72, 0x20, 0x73,
            0x69, 0x64, 0x65, 0x2C, 0x20, 0x69, 0x74, 0x20, 0x69, 0x73, 0x20, 0x74,
            0x68, 0x65, 0x20, 0x73, 0x65, 0x6E, 0x64, 0x20, 0x6B, 0x65, 0x79, 0x2E,
        )
        private val PAD1 = ByteArray(40) { 0x00 }
        private val PAD2 = ByteArray(40) { 0xF2.toByte() }

        /**
         * RFC 3079 §3.4 GetAsymmetricStartKey(MasterKey, KeyLen, IsSend, IsServer).
         * Direction = (isSend xor isServer) ? Client→Server : Server→Client
         */
        fun getAsymmetricStartKey(
            masterKey: ByteArray,
            keyLenBytes: Int,
            isSend: Boolean,
            isServer: Boolean,
        ): ByteArray {
            // Truth table (mapping each role+direction to the underlying packet direction):
            //   client+send  → packets travel  C→S  (use MAGIC_CLIENT_TO_SERVER)
            //   client+recv  → packets travel  S→C  (use MAGIC_SERVER_TO_CLIENT)
            //   server+send  → packets travel  S→C  (use MAGIC_SERVER_TO_CLIENT)
            //   server+recv  → packets travel  C→S  (use MAGIC_CLIENT_TO_SERVER)
            // Packet direction:
            //   client+send (F,T): C→S    server+send (T,T): S→C
            //   client+recv (F,F): S→C    server+recv (T,F): C→S
            // i.e., direction = C→S iff (isServer xor isSend) is true.
            val magic = if (isServer xor isSend) MAGIC_CLIENT_TO_SERVER else MAGIC_SERVER_TO_CLIENT
            val sha = Crypto.sha1(masterKey.copyOf(keyLenBytes), PAD1, magic, PAD2)
            return sha.copyOfRange(0, keyLenBytes)
        }

        /**
         * Per-packet rekey, equivalent to pppd's `mppe_rekey(initial=0)`.
         *
         *   InterimKey = SHA1(InitialKey || pad1 || CurrentKey || pad2)[0:keyLen]
         *   NewKey     = RC4(InterimKey, InterimKey)
         *
         * RFC 3078 §7.7 documents only the SHA1 half; the RC4 self-encrypt
         * step is implemented by pppd / Microsoft RRAS / accel-ppp for every
         * key length (the spec mentions it only in the 40/56-bit reduction
         * section, but the wire-format requires this self-encrypt for
         * interop with the dominant implementations).
         */
        fun nextKey(initialKey: ByteArray, currentKey: ByteArray): ByteArray {
            val keyLen = initialKey.size
            val sha = Crypto.sha1(initialKey, PAD1, currentKey, PAD2).copyOfRange(0, keyLen)
            val rc4 = Rc4(sha)
            val out = sha.copyOf()
            rc4.process(out)
            return out
        }

        /**
         * The "initial rekey" form pppd uses once during `mppe_init`, before
         * any packet has been processed. Same SHA1 as [nextKey] but WITHOUT
         * the final RC4 self-encrypt.
         */
        fun sha1Only(initialKey: ByteArray, currentKey: ByteArray): ByteArray {
            val keyLen = initialKey.size
            return Crypto.sha1(initialKey, PAD1, currentKey, PAD2).copyOfRange(0, keyLen)
        }
    }
}
