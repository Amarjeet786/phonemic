package com.phonemic.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

private fun startMic(
    ctx: Context, ip: String, pin: String, aec: Boolean, ns: Boolean, agc: Boolean, mode: String, record: Boolean
) {
    val i = Intent(ctx, MicService::class.java)
        .setAction(MicService.ACTION_START)
        .putExtra(MicService.EXTRA_IP, ip.trim())
        .putExtra(MicService.EXTRA_PORT, 50505)
        .putExtra(MicService.EXTRA_PIN, pin.trim())
        .putExtra(MicService.EXTRA_AEC, aec)
        .putExtra(MicService.EXTRA_NS, ns)
        .putExtra(MicService.EXTRA_AGC, agc)
        .putExtra(MicService.EXTRA_MODE, mode)
        .putExtra(MicService.EXTRA_REC, record)
    ContextCompat.startForegroundService(ctx, i)
}

private fun stopMic(ctx: Context) {
    ctx.startService(Intent(ctx, MicService::class.java).setAction(MicService.ACTION_STOP))
}

private val NOISE_NAMES = listOf("Off", "Low", "Medium", "High")

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("phonemic", Context.MODE_PRIVATE) }

    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var aec by remember { mutableStateOf(prefs.getBoolean("aec", true)) }
    var ns by remember { mutableStateOf(prefs.getBoolean("ns", true)) }
    var agc by remember { mutableStateOf(prefs.getBoolean("agc", true)) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "wifi") ?: "wifi") }
    var recordOnPhone by remember { mutableStateOf(prefs.getBoolean("rec", false)) }

    LaunchedEffect(Unit) {
        MicState.wifiConnected = Discovery.isWifiConnected(ctx)
        if (!MicState.running) {
            MicState.noiseLevel = prefs.getInt("noise", 2)
            MicState.voice = prefs.getString("voice", "Natural") ?: "Natural"
            MicState.boldness = prefs.getFloat("bold", 0.6f)
        }
    }

    fun savePrefs() {
        prefs.edit()
            .putString("ip", ip).putString("pin", pin).putString("mode", mode)
            .putBoolean("aec", aec).putBoolean("ns", ns).putBoolean("agc", agc).putBoolean("rec", recordOnPhone)
            .putInt("noise", MicState.noiseLevel).putString("voice", MicState.voice)
            .putFloat("bold", MicState.boldness)
            .apply()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            startMic(ctx, ip, pin, aec, ns, agc, mode, recordOnPhone)
        } else {
            MicState.status = "Microphone permission is off. Enable it in Android Settings."
        }
    }

    val statusColor = when {
        MicState.running && MicState.connected -> Color(0xFF2E7D32)
        MicState.running -> Color(0xFFF9A825)
        MicState.status.contains("error", true) || MicState.status.contains("permission", true) || MicState.status.contains("Could not", true) -> Color(0xFFC62828)
        else -> Color.Gray
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PHONE MIC", style = MaterialTheme.typography.headlineMedium)
        Text("● ${MicState.status}", color = statusColor)
        Text(
            "Microphone access is needed only to send your voice to the PC you choose. " +
                "Audio is encrypted and stays on your local connection. Nothing is sent anywhere else.",
            style = MaterialTheme.typography.bodySmall
        )

        // ---- Connection ----
        Text("Connection type", style = MaterialTheme.typography.titleSmall)
        ChipRow(listOf("Wi-Fi", "USB cable", "Bluetooth"), when (mode) { "usb" -> "USB cable"; "bluetooth" -> "Bluetooth"; else -> "Wi-Fi" }) {
            if (!MicState.running) mode = when (it) { "USB cable" -> "usb"; "Bluetooth" -> "bluetooth"; else -> "wifi" }
        }
        when (mode) {
            "wifi" -> Text(
                if (MicState.wifiConnected) "Wi-Fi: Connected" else "Wi-Fi: Not connected. Join the same Wi-Fi as your PC.",
                color = if (MicState.wifiConnected) Color(0xFF2E7D32) else Color(0xFFF9A825)
            )
            "usb" -> Text(
                "1. Connect the phone to the PC with a USB cable.\n" +
                    "2. On the phone: Settings > Network > Hotspot & tethering > turn on USB tethering.\n" +
                    "3. Press \"Find my PC\" below. Works without Wi-Fi or internet, with the lowest delay.",
                style = MaterialTheme.typography.bodySmall
            )
            else -> Text(
                "Bluetooth uses Bluetooth tethering, not a real Bluetooth microphone.\n" +
                    "1. Pair the phone and PC in Bluetooth settings.\n" +
                    "2. Phone: turn on Bluetooth tethering. PC: join the phone's Personal Area Network.\n" +
                    "3. Press \"Find my PC\". Expect higher delay and less stability than Wi-Fi or USB.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF9A825)
            )
        }

        OutlinedButton(
            onClick = {
                MicState.wifiConnected = Discovery.isWifiConnected(ctx)
                MicState.scanning = true
                Thread {
                    MicState.pcs = Discovery.scan()
                    MicState.scanning = false
                }.start()
            },
            enabled = !MicState.scanning && !MicState.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (MicState.scanning) "Searching for computers..." else "Find my PC") }
        if (!MicState.scanning && MicState.pcs.isEmpty()) {
            Text("No PC found yet. Make sure the Windows receiver is running, or type the IP below.", style = MaterialTheme.typography.bodySmall)
        }
        MicState.pcs.forEach { pc ->
            FilledTonalButton(onClick = { ip = pc.ip }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (ip == pc.ip) "✓ " else ""}${pc.name}  (${pc.ip}) via ${pc.via}")
            }
        }
        OutlinedTextField(ip, { ip = it }, label = { Text("PC IP address (shown in the PC app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN (shown in the PC app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        // ---- Level ----
        Text("Input level", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { MicState.level }, modifier = Modifier.fillMaxWidth().height(12.dp))

        // ---- Noise reduction ----
        Text("Noise Reduction", style = MaterialTheme.typography.titleSmall)
        ChipRow(NOISE_NAMES, NOISE_NAMES[MicState.noiseLevel.coerceIn(0, 3)]) { MicState.noiseLevel = NOISE_NAMES.indexOf(it) }
        Text(
            "Lowers steady background noise between words (fan, AC, hum). Higher levels remove more but can clip word endings.",
            style = MaterialTheme.typography.bodySmall
        )

        // ---- Voice enhancement ----
        Text("Voice Enhancement", style = MaterialTheme.typography.titleSmall)
        ChipRow(VoicePresets.names, MicState.voice) { MicState.voice = it }
        if (MicState.voice == "Bold") {
            Text("Boldness: ${(MicState.boldness * 100).toInt()}%")
            Slider(value = MicState.boldness, onValueChange = { MicState.boldness = it }, valueRange = 0f..1f)
            Text("Adds warmth and body to the voice and evens out the volume.", style = MaterialTheme.typography.bodySmall)
        }

        // ---- Phone effects ----
        Text("Phone audio effects (apply on next connect)", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Echo Cancellation", aec, { aec = it }, note = if (MicState.aecAvailable || !MicState.running) null else "Not supported on this phone")
        SwitchRow("Built-in Noise Suppression", ns, { ns = it }, note = if (MicState.nsAvailable || !MicState.running) null else "Not supported on this phone")
        SwitchRow("Auto Gain", agc, { agc = it }, note = if (MicState.agcAvailable || !MicState.running) null else "Not supported on this phone")
        SwitchRow("Also record on this phone (WAV)", recordOnPhone, { recordOnPhone = it }, note = null)

        Button(
            onClick = {
                if (MicState.running) {
                    stopMic(ctx)
                } else {
                    savePrefs()
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    permLauncher.launch(perms.toTypedArray())
                }
            },
            enabled = MicState.running || (ip.isNotBlank() && pin.length == 6),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (MicState.running) "DISCONNECT" else "CONNECT & START MIC") }

        if (MicState.recordPath.isNotEmpty()) {
            Text("Recording file: ${MicState.recordPath}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { name ->
            FilterChip(selected = name == selected, onClick = { onSelect(name) }, label = { Text(name) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, note: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
