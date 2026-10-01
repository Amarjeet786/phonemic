package com.phonemic.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** RBJ-cookbook biquad filter. */
class Biquad private constructor() {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double): Biquad {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        return this
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        fun passthrough() = Biquad()

        fun highPass(fs: Double, f: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * f / fs
            val c = cos(w)
            val al = sin(w) / (2 * q)
            return Biquad().set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
        }

        fun lowShelf(fs: Double, f: Double, gainDb: Double): Biquad {
            if (abs(gainDb) < 0.05) return passthrough()
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs
            val c = cos(w)
            val t = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
            return Biquad().set(
                a * ((a + 1) - (a - 1) * c + t),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - t),
                (a + 1) + (a - 1) * c + t,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - t
            )
        }

        fun highShelf(fs: Double, f: Double, gainDb: Double): Biquad {
            if (abs(gainDb) < 0.05) return passthrough()
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs
            val c = cos(w)
            val t = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
            return Biquad().set(
                a * ((a + 1) + (a - 1) * c + t),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - t),
                (a + 1) - (a - 1) * c + t,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - t
            )
        }

        fun peaking(fs: Double, f: Double, q: Double, gainDb: Double): Biquad {
            if (abs(gainDb) < 0.05) return passthrough()
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * f / fs
            val c = cos(w)
            val al = sin(w) / (2 * q)
            return Biquad().set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }
    }
}

data class VoicePreset(
    val lowDb: Double,        // low shelf @ 180 Hz (warmth / body)
    val presenceDb: Double,   // peak @ 3 kHz (clarity)
    val harshDb: Double,      // high shelf @ 6.5 kHz (negative = tame harshness)
    val thresholdDb: Double,  // compressor threshold
    val ratio: Double,
    val makeupDb: Double,
    val deEss: Double = 0.0,  // 0..1 de-esser amount
    val ceilingDb: Double = -1.5, // limiter ceiling
    val enabled: Boolean = true
)

object VoicePresets {
    val names = listOf("Off", "Natural", "Clear", "Bold", "Podcast", "Streaming", "Meeting")

    fun get(name: String, boldness: Float): VoicePreset = when (name) {
        "Off" -> VoicePreset(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.5, enabled = false)
        "Clear" -> VoicePreset(-2.0, 4.0, -1.0, -20.0, 2.5, 3.0, 0.4)
        "Bold" -> {
            val b = boldness.toDouble().coerceIn(0.0, 1.0)
            VoicePreset(1.0 + 6.0 * b, 2.0 + 2.0 * b, -1.0 - 1.5 * b, -20.0 - 4.0 * b, 2.0 + 2.5 * b, 3.0 + 3.0 * b, 0.3)
        }
        "Podcast" -> VoicePreset(3.0, 2.0, -1.5, -22.0, 3.0, 4.0, 0.4)
        "Streaming" -> VoicePreset(2.0, 3.0, -1.0, -20.0, 3.5, 4.0, 0.4)
        "Meeting" -> VoicePreset(-1.0, 4.0, -1.0, -18.0, 2.5, 3.0, 0.3)
        else -> VoicePreset(0.0, 1.0, 0.0, -20.0, 1.8, 2.0, 0.2) // Natural
    }
}

/**
 * Software voice pipeline (per frame, in place, 48 kHz):
 * high-pass 90 Hz -> noise gate/expander -> EQ (low shelf, presence, air) -> de-esser
 * -> compressor -> limiter.
 * The gate lowers the level between words; steady noise present WHILE you speak is handled by
 * the phone's built-in suppressor and by RNNoise on the PC.
 */
class AudioProcessor(private val fs: Int, frameMs: Int) {
    @Volatile var noiseLevel = 0

    private val hpf = Biquad.highPass(fs.toDouble(), 90.0)
    private var low = Biquad.passthrough()
    private var pres = Biquad.passthrough()
    private var harsh = Biquad.passthrough()
    private val sibBand = Biquad.highPass(fs.toDouble(), 5500.0)
    private var enhance = false
    private var thrLin = 1f
    private var ratio = 1.0
    private var makeupLin = 1f
    private var deEss = 0f
    private var ceilLin = 0.84f

    private var floor = 0.01f
    private var hold = 0
    private var gateGain = 1f
    private var env = 0f
    private var sibEnv = 0f

    private val holdFrames = ceil(100.0 / frameMs).toInt().coerceAtLeast(1)
    private val floorRise = 0.002f * frameMs / 20f
    private val attack = (1 - exp(-1.0 / (0.005 * fs))).toFloat()
    private val release = (1 - exp(-1.0 / (0.12 * fs))).toFloat()
    private val openCoef = (1 - exp(-1.0 / (0.003 * fs))).toFloat()
    private val closeCoef = (1 - exp(-1.0 / (0.03 * fs))).toFloat()
    private val sibAtk = (1 - exp(-1.0 / (0.001 * fs))).toFloat()
    private val sibRel = (1 - exp(-1.0 / (0.02 * fs))).toFloat()

    fun setVoice(p: VoicePreset) {
        val f = fs.toDouble()
        low = Biquad.lowShelf(f, 180.0, p.lowDb)
        pres = Biquad.peaking(f, 3000.0, 0.8, p.presenceDb)
        harsh = Biquad.highShelf(f, 6500.0, p.harshDb)
        enhance = p.enabled
        thrLin = 10.0.pow(p.thresholdDb / 20.0).toFloat()
        ratio = p.ratio.coerceAtLeast(1.0)
        makeupLin = 10.0.pow(p.makeupDb / 20.0).toFloat()
        deEss = p.deEss.coerceIn(0.0, 1.0).toFloat()
        ceilLin = 10.0.pow(p.ceilingDb.coerceIn(-12.0, -0.1) / 20.0).toFloat()
    }

    fun process(buf: ShortArray, n: Int) {
        if (n <= 0) return
        val x = FloatArray(n)
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = hpf.process(buf[i] / 32768f)
            x[i] = v
            sumSq += v * v
        }
        val rms = sqrt(sumSq / n).toFloat()

        var target = 1f
        val level = noiseLevel
        if (level > 0) {
            val factor = when (level) { 1 -> 2.0f; 2 -> 2.5f; else -> 3.0f }
            val depth = when (level) { 1 -> 0.5f; 2 -> 0.25f; else -> 0.1f }
            if (rms < floor) floor = floor * 0.5f + rms * 0.5f else floor += (rms - floor) * floorRise
            floor = floor.coerceIn(0.0005f, 0.05f)
            val open = rms > floor * factor
            if (open) hold = holdFrames else if (hold > 0) hold--
            target = if (open || hold > 0) 1f else depth
        }

        val knee = ceilLin * 0.18f
        for (i in 0 until n) {
            gateGain += (target - gateGain) * (if (target > gateGain) openCoef else closeCoef)
            var v = x[i] * gateGain
            if (enhance) {
                v = low.process(v)
                v = pres.process(v)
                v = harsh.process(v)

                // De-esser: pull down the 5.5 kHz+ band only while it is too strong (s, sh, t sounds).
                val sib = sibBand.process(v)
                if (deEss > 0f) {
                    val sa = abs(sib)
                    sibEnv += (sa - sibEnv) * (if (sa > sibEnv) sibAtk else sibRel)
                    if (sibEnv > 0.04f) {
                        val over = min(sibEnv / 0.04f, 4f)
                        v -= sib * ((1f - 1f / over) * deEss)
                    }
                }

                // Compressor
                val a = abs(v)
                env += (a - env) * (if (a > env) attack else release)
                var g = 1f
                if (env > thrLin) {
                    val overDb = 20.0 * log10((env / thrLin).toDouble())
                    g = 10.0.pow(-overDb * (1.0 - 1.0 / ratio) / 20.0).toFloat()
                }
                v = v * g * makeupLin
            }
            // Limiter (soft knee, never exceeds the ceiling)
            val a2 = abs(v)
            if (a2 > ceilLin - knee) v = sign(v) * (ceilLin - knee + knee * tanh((a2 - (ceilLin - knee)) / knee))
            buf[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
