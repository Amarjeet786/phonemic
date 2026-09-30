package com.phonemic.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
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
    val lowDb: Double,       // low shelf @ 180 Hz (warmth / body)
    val presenceDb: Double,  // peak @ 3 kHz (clarity)
    val harshDb: Double,     // high shelf @ 6.5 kHz (negative = tame harshness)
    val thresholdDb: Double, // compressor threshold
    val ratio: Double,
    val makeupDb: Double,
    val enabled: Boolean = true
)

object VoicePresets {
    val names = listOf("Off", "Natural", "Clear", "Bold", "Podcast", "Streaming", "Meeting")

    fun get(name: String, boldness: Float): VoicePreset = when (name) {
        "Off" -> VoicePreset(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, enabled = false)
        "Clear" -> VoicePreset(-2.0, 4.0, -1.0, -20.0, 2.5, 3.0)
        "Bold" -> {
            val b = boldness.toDouble().coerceIn(0.0, 1.0)
            VoicePreset(1.0 + 6.0 * b, 2.0 + 2.0 * b, -1.0 - 1.5 * b, -20.0 - 4.0 * b, 2.0 + 2.5 * b, 3.0 + 3.0 * b)
        }
        "Podcast" -> VoicePreset(3.0, 2.0, -1.5, -22.0, 3.0, 4.0)
        "Streaming" -> VoicePreset(2.0, 3.0, -1.0, -20.0, 3.5, 4.0)
        "Meeting" -> VoicePreset(-1.0, 4.0, -1.0, -18.0, 2.5, 3.0)
        else -> VoicePreset(0.0, 1.0, 0.0, -20.0, 1.8, 2.0) // Natural
    }
}

/**
 * Software voice pipeline (per 20 ms frame, in place):
 * high-pass 90 Hz -> adaptive noise expander -> voice EQ -> compressor -> soft limiter.
 * The noise stage is a time-domain expander (not spectral / AI): it lowers the level
 * between words. Steady noise that is present WHILE you speak is not removed by it.
 */
class AudioProcessor(private val fs: Int) {
    @Volatile var noiseLevel = 0

    private val hpf = Biquad.highPass(fs.toDouble(), 90.0)
    private var low = Biquad.passthrough()
    private var pres = Biquad.passthrough()
    private var harsh = Biquad.passthrough()
    private var enhance = false
    private var thrLin = 1f
    private var ratio = 1.0
    private var makeupLin = 1f

    private var floor = 0.01f
    private var hold = 0
    private var gateGain = 1f
    private var env = 0f

    private val attack = (1 - exp(-1.0 / (0.005 * fs))).toFloat()
    private val release = (1 - exp(-1.0 / (0.12 * fs))).toFloat()
    private val openCoef = (1 - exp(-1.0 / (0.003 * fs))).toFloat()
    private val closeCoef = (1 - exp(-1.0 / (0.03 * fs))).toFloat()

    fun setVoice(p: VoicePreset) {
        val f = fs.toDouble()
        low = Biquad.lowShelf(f, 180.0, p.lowDb)
        pres = Biquad.peaking(f, 3000.0, 0.8, p.presenceDb)
        harsh = Biquad.highShelf(f, 6500.0, p.harshDb)
        enhance = p.enabled
        thrLin = 10.0.pow(p.thresholdDb / 20.0).toFloat()
        ratio = p.ratio.coerceAtLeast(1.0)
        makeupLin = 10.0.pow(p.makeupDb / 20.0).toFloat()
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
            if (rms < floor) floor = floor * 0.5f + rms * 0.5f else floor += (rms - floor) * 0.002f
            floor = floor.coerceIn(0.0005f, 0.05f)
            val open = rms > floor * factor
            if (open) hold = 5 else if (hold > 0) hold--
            target = if (open || hold > 0) 1f else depth
        }

        for (i in 0 until n) {
            gateGain += (target - gateGain) * (if (target > gateGain) openCoef else closeCoef)
            var v = x[i] * gateGain
            if (enhance) {
                v = low.process(v)
                v = pres.process(v)
                v = harsh.process(v)
                val a = abs(v)
                env += (a - env) * (if (a > env) attack else release)
                var g = 1f
                if (env > thrLin) {
                    val overDb = 20.0 * log10((env / thrLin).toDouble())
                    g = 10.0.pow(-overDb * (1.0 - 1.0 / ratio) / 20.0).toFloat()
                }
                v = v * g * makeupLin
            }
            val a2 = abs(v)
            if (a2 > 0.85f) v = sign(v) * (0.85f + 0.15f * tanh((a2 - 0.85f) / 0.15f))
            buf[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
