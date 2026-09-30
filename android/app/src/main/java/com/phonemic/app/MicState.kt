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

    // Live settings: the service reads these every frame, so changes apply instantly.
    var noiseLevel by mutableIntStateOf(2)          // 0 Off, 1 Low, 2 Medium, 3 High
    var voice by mutableStateOf("Natural")          // see VoicePresets.names
    var boldness by mutableFloatStateOf(0.6f)       // 0..1, used by the "Bold" preset

    var recordPath by mutableStateOf("")
}
