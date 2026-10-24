package dev.useunion.internal

import java.security.SecureRandom

/**
 * RFC 9562 UUIDv7: 48-bit unix ms, version nibble, 12 bits (monotonic within the same ms), variant,
 * 62 random bits. Time-ordered ids keep `events`/`sessions` inserts append-friendly on the server.
 */
internal object UUIDv7 {
    private val random = SecureRandom()
    private val lock = Any()
    private var lastMs = 0L
    private var lastRandA = 0

    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        val ms = maxOf(0L, nowMs)
        val randA = synchronized(lock) {
            if (ms == lastMs) {
                lastRandA = (lastRandA + 1) and 0x0FFF
            } else {
                lastMs = ms
                lastRandA = random.nextInt(0x1000)
            }
            lastRandA
        }
        val bytes = ByteArray(16)
        for (i in 0 until 6) bytes[i] = ((ms shr (8 * (5 - i))) and 0xFF).toByte()
        bytes[6] = (0x70 or ((randA shr 8) and 0x0F)).toByte()
        bytes[7] = (randA and 0xFF).toByte()
        val tail = ByteArray(8).also { random.nextBytes(it) }
        tail.copyInto(bytes, 8)
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

        val hex = StringBuilder(32)
        for (b in bytes) {
            hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private const val HEX = "0123456789abcdef"
}
