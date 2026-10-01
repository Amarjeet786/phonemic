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
import org.concentus.OpusApplication
import org.concentus.OpusEncoder

/** Opus (pure-Java Concentus), 48 kHz mono, VOIP mode. One packet per frame (10/20/40 ms). */
class OpusPacketEncoder(bitrate: Int) {
    private val enc = OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    private val out = ByteArray(1275)
    private var currentBitrate = bitrate

    init {
        enc.setBitrate(bitrate)
        enc.setComplexity(5)
    }

    fun setBitrate(b: Int) {
        if (b != currentBitrate) {
            enc.setBitrate(b)
            currentBitrate = b
        }
    }

    fun encode(pcm: ShortArray, frameSize: Int): ByteArray {
        val n = enc.encode(pcm, 0, frameSize, out, 0, out.size)
        return out.copyOf(n)
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
