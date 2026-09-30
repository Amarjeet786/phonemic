package com.phonemic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared UI state, written by MicService and read by the UI. */
object MicState {
    var running by mutableStateOf(false)
    var level by mutableFloatStateOf(0f)
    var status by mutableStateOf("Disconnected")
    var aecAvailable by mutableStateOf(false)
    var nsAvailable by mutableStateOf(false)
    var agcAvailable by mutableStateOf(false)
    var wifiConnected by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var pcs by mutableStateOf(listOf<Discovery.Pc>())
}
