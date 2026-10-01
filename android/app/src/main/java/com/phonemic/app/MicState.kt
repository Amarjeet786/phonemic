package com.phonemic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared UI state, written by MicService and read by the UI (and vice versa for live settings). */
object MicState {
    var running by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var status by mutableStateOf("Disconnected")
    var aecAvailable by mutableStateOf(false)
    var nsAvailable by mutableStateOf(false)
    var agcAvailable by mutableStateOf(false)
    var wifiConnected by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var pcs by mutableStateOf(listOf<Discovery.Pc>())
    var recordPath by mutableStateOf("")

    // ---- Live settings: the service reads these every frame, so changes apply instantly ----
    var noiseLevel by mutableIntStateOf(2)      // Noise gate: 0 Off, 1 Low, 2 Medium, 3 High
    var quality by mutableIntStateOf(1)         // Opus bitrate: 0 Low, 1 Medium, 2 High, 3 Ultra
    var latencyMode by mutableIntStateOf(1)     // 0 Ultra Low(10 ms), 1 Low(20), 2 Balanced(20), 3 High Quality(40); applied on connect

    var voice by mutableStateOf("Natural")
    var boldness by mutableFloatStateOf(0.6f)
    var enhance by mutableStateOf(true)
    var lowDb by mutableFloatStateOf(0f)        // EQ warmth (low shelf 180 Hz)
    var presenceDb by mutableFloatStateOf(1f)   // EQ clarity (peak 3 kHz)
    var harshDb by mutableFloatStateOf(0f)      // EQ air / harshness (high shelf 6.5 kHz)
    var thresholdDb by mutableFloatStateOf(-20f) // compressor threshold
    var ratio by mutableFloatStateOf(1.8f)
    var makeupDb by mutableFloatStateOf(2f)
    var deEss by mutableFloatStateOf(0.2f)      // de-esser amount 0..1
    var ceilingDb by mutableFloatStateOf(-1.5f) // limiter ceiling

    fun applyPreset(name: String) {
        voice = name
        val p = VoicePresets.get(name, boldness)
        enhance = p.enabled
        lowDb = p.lowDb.toFloat(); presenceDb = p.presenceDb.toFloat(); harshDb = p.harshDb.toFloat()
        thresholdDb = p.thresholdDb.toFloat(); ratio = p.ratio.toFloat(); makeupDb = p.makeupDb.toFloat()
        deEss = p.deEss.toFloat(); ceilingDb = p.ceilingDb.toFloat()
    }

    /** Called when a slider is moved by hand. */
    fun markCustom() {
        voice = "Custom"
        enhance = true
    }

    fun current() = VoicePreset(
        lowDb.toDouble(), presenceDb.toDouble(), harshDb.toDouble(),
        thresholdDb.toDouble(), ratio.toDouble(), makeupDb.toDouble(),
        deEss.toDouble(), ceilingDb.toDouble(), enhance
    )
}
