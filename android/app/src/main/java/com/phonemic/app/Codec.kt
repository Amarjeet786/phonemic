package com.phonemic.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * IMA ADPCM, 4:1 compression (about 64 kbps at 16 kHz mono).
 * Every packet carries its own predictor/index so a lost packet never breaks the next one.
 * Payload = pred(int16 LE) + index(byte) + 4-bit codes (2 samples per byte).
 */
class AdpcmEncoder {
    private companion object {
        val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88,
            97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660,
            4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818,
            18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
        val IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    }

    private var pred = 0
    private var index = 0

    /** n must be even. */
    fun encode(s: ShortArray, n: Int): ByteArray {
        val out = ByteArray(3 + n / 2)
        out[0] = (pred and 0xFF).toByte()
        out[1] = ((pred shr 8) and 0xFF).toByte()
        out[2] = index.toByte()
        var i = 0
        while (i + 1 < n) {
            val c0 = sample(s[i].toInt())
            val c1 = sample(s[i + 1].toInt())
            out[3 + i / 2] = (c0 or (c1 shl 4)).toByte()
            i += 2
        }
        return out
    }

    private fun sample(v: Int): Int {
        var diff = v - pred
        var sign = 0
        if (diff < 0) { sign = 8; diff = -diff }
        var step = STEPS[index]
        var code = 0
        var vp = step shr 3
        if (diff >= step) { code = 4; diff -= step; vp += step }
        step = step shr 1
        if (diff >= step) { code = code or 2; diff -= step; vp += step }
        step = step shr 1
        if (diff >= step) { code = code or 1; vp += step }
        pred = if (sign != 0) pred - vp else pred + vp
        pred = pred.coerceIn(-32768, 32767)
        code = code or sign
        index = (index + IDX[code]).coerceIn(0, 88)
        return code
    }
}

/** AES-256-GCM. Key = PBKDF2(PIN). Nonce = session(4) + seq(4) + 0(4). Header is authenticated. */
object Crypto {
    fun deriveKey(pin: String): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(pin.toCharArray(), "PhoneMic-v2".toByteArray(), 20000, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(key: SecretKeySpec, header: ByteArray, session: Int, seq: Int, plain: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(session).putInt(seq).putInt(0).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        return c.doFinal(plain)
    }

    fun newSession(): Int = SecureRandom().nextInt()
}

/** Minimal 16-bit mono WAV writer (header is patched on close). */
class WavWriter(file: File, private val rate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1.toShort()); b.putShort(1.toShort())
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2.toShort()); b.putShort(16.toShort())
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }

    fun write(s: ShortArray, n: Int) {
        val b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) b.putShort(s[i])
        raf.write(b.array())
        dataBytes += n * 2
    }

    fun close() {
        try {
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.close()
        } catch (_: Exception) {
        }
    }
}
