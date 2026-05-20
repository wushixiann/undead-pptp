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

    private var sendCurrent: ByteArray = sendInitialKey.copyOf()
    private var recvCurrent: ByteArray = recvInitialKey.copyOf()

    private var sendCC: Int = 0    // 12-bit coherency count (0..0xFFF)
    private var expectedRecvCC: Int = 0
    private var initial: Boolean = true

    @Volatile var lastReceivedCC: Int = -1
        private set

    @Synchronized
    fun encrypt(pppProtoAndPayload: ByteArray): ByteArray {
        // Rotate the send key BEFORE this packet's CC.
        sendCurrent = nextKey(sendInitialKey, sendCurrent)
        val rc4 = Rc4(sendCurrent)
        val encrypted = pppProtoAndPayload.copyOf()
        rc4.process(encrypted)

        val cc = sendCC
        sendCC = (sendCC + 1) and 0xFFF

        // Header: A=1 (flushed), B=0, C=0, D=1 (encrypted), then 12-bit CC
        val flagsAndCcHi = ((0xA0 or ((cc shr 8) and 0x0F)) and 0xFF)
        val ccLo = cc and 0xFF
        val out = ByteArray(2 + encrypted.size)
        out[0] = flagsAndCcHi.toByte()
        out[1] = ccLo.toByte()
        System.arraycopy(encrypted, 0, out, 2, encrypted.size)
        return out
    }

    /**
     * Decrypt an MPPE-encrypted packet. Returns null on coherency gap (caller
     * should then send CCP Reset-Request and drop the packet).
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
            Log.w(TAG, "MPPE D bit not set — packet not encrypted?")
            return null
        }
        lastReceivedCC = cc

        // In stateless mode every packet must have A (flushed) set. If not,
        // the peer is sending stateful — we drop and request reset.
        if (!flushed) {
            Log.w(TAG, "MPPE packet without Flushed bit — stateful mode? Dropping.")
            return null
        }

        // Coherency check
        if (initial) {
            expectedRecvCC = cc
            initial = false
        }
        if (cc != expectedRecvCC) {
            Log.w(TAG, "MPPE coherency gap: expected=$expectedRecvCC got=$cc — re-syncing")
            // For stateless, we resync the key for this packet:
            // need to rotate recvCurrent from initial until it matches the gap.
            val delta = ((cc - expectedRecvCC) and 0xFFF)
            for (k in 0..delta) {
                recvCurrent = nextKey(recvInitialKey, recvCurrent)
            }
            expectedRecvCC = (cc + 1) and 0xFFF
            // Decrypt with this re-keyed RC4 (already rotated to current cc above).
            val rc4 = Rc4(recvCurrent)
            val payload = packet.copyOfRange(2, packet.size)
            rc4.process(payload)
            return payload
        }

        recvCurrent = nextKey(recvInitialKey, recvCurrent)
        expectedRecvCC = (expectedRecvCC + 1) and 0xFFF
        val rc4 = Rc4(recvCurrent)
        val payload = packet.copyOfRange(2, packet.size)
        rc4.process(payload)
        return payload
    }

    /**
     * Reset the send/recv state. Called when a CCP Reset-Request is processed.
     */
    @Synchronized
    fun reset() {
        sendCurrent = sendInitialKey.copyOf()
        recvCurrent = recvInitialKey.copyOf()
        sendCC = 0
        expectedRecvCC = 0
        initial = true
    }

    companion object {
        private const val TAG = "Mppe"
        const val KEY_LEN_BYTES = 16 // 128-bit only for v0.0.8

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
         * RFC 3078 §7.7 GetNewKeyFromSHA — used both for changing keys
         * stateless-per-packet and for the stateful re-key after 256 packets.
         */
        fun nextKey(initialKey: ByteArray, currentKey: ByteArray): ByteArray {
            val keyLen = initialKey.size
            // Per RFC 3078: SHA1(InitialKey || pad1 || CurrentKey || pad2)[0:keyLen]
            // After SHA, the result is "RC4-encrypted" with itself as a key to
            // produce the final new key. (See §7.7 step 4.)
            val sha = Crypto.sha1(initialKey, PAD1, currentKey, PAD2).copyOfRange(0, keyLen)
            // Final step: RC4(sha, sha) — encrypt the digest with itself as key.
            val rc4 = Rc4(sha)
            val out = sha.copyOf()
            rc4.process(out)
            return out
        }
    }
}
