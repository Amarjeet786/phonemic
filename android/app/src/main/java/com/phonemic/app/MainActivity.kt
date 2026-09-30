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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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

private fun startMic(ctx: Context, ip: String, pin: String, aec: Boolean, ns: Boolean, agc: Boolean) {
    val i = Intent(ctx, MicService::class.java)
        .setAction(MicService.ACTION_START)
        .putExtra(MicService.EXTRA_IP, ip.trim())
        .putExtra(MicService.EXTRA_PORT, 50505)
        .putExtra(MicService.EXTRA_PIN, pin.trim())
        .putExtra(MicService.EXTRA_AEC, aec)
        .putExtra(MicService.EXTRA_NS, ns)
        .putExtra(MicService.EXTRA_AGC, agc)
    ContextCompat.startForegroundService(ctx, i)
}

private fun stopMic(ctx: Context) {
    ctx.startService(Intent(ctx, MicService::class.java).setAction(MicService.ACTION_STOP))
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    var ip by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var aec by remember { mutableStateOf(true) }
    var ns by remember { mutableStateOf(true) }
    var agc by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            startMic(ctx, ip, pin, aec, ns, agc)
        } else {
            MicState.status = "Microphone permission is off. Enable it in Android Settings."
        }
    }

    LaunchedEffect(Unit) { MicState.wifiConnected = Discovery.isWifiConnected(ctx) }

    val statusColor = when {
        MicState.running -> Color(0xFF2E7D32)
        MicState.status.contains("error", true) || MicState.status.contains("permission", true) || MicState.status.contains("Could not", true) -> Color(0xFFC62828)
        else -> Color.Gray
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PHONE MIC", style = MaterialTheme.typography.headlineMedium)
        Text("● ${MicState.status}", color = statusColor)
        Text(
            "Microphone access is needed only to send your voice to the PC you choose. " +
                "Nothing is recorded or sent anywhere else.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            if (MicState.wifiConnected) "Wi-Fi: Connected" else "Wi-Fi: Not connected. Join the same Wi-Fi as your PC.",
            color = if (MicState.wifiConnected) Color(0xFF2E7D32) else Color(0xFFF9A825)
        )
        OutlinedButton(
            onClick = {
                MicState.wifiConnected = Discovery.isWifiConnected(ctx)
                MicState.scanning = true
                Thread {
                    MicState.pcs = Discovery.scan(ctx)
                    MicState.scanning = false
                }.start()
            },
            enabled = !MicState.scanning && !MicState.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (MicState.scanning) "Searching for computers..." else "Find my PC on Wi-Fi") }
        if (!MicState.scanning && MicState.pcs.isEmpty()) {
            Text("No PC found yet. Make sure the Windows receiver is running on the same Wi-Fi, or type the IP below.", style = MaterialTheme.typography.bodySmall)
        }
        MicState.pcs.forEach { pc ->
            FilledTonalButton(onClick = { ip = pc.ip }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (ip == pc.ip) "✓ " else ""}${pc.name}  (${pc.ip})")
            }
        }
        OutlinedTextField(ip, { ip = it }, label = { Text("PC IP address (shown in the PC app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN (shown in the PC app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Input level")
        LinearProgressIndicator(progress = { MicState.level }, modifier = Modifier.fillMaxWidth().height(12.dp))

        SwitchRow("Echo Cancellation", aec, { aec = it }, note = if (MicState.aecAvailable || !MicState.running) null else "Not supported on this phone")
        SwitchRow("Noise Reduction", ns, { ns = it }, note = if (MicState.nsAvailable || !MicState.running) null else "Not supported on this phone")
        SwitchRow("Auto Gain", agc, { agc = it }, note = if (MicState.agcAvailable || !MicState.running) null else "Not supported on this phone")

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                if (MicState.running) {
                    stopMic(ctx)
                } else {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    permLauncher.launch(perms.toTypedArray())
                }
            },
            enabled = MicState.running || (ip.isNotBlank() && pin.length == 6),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(if (MicState.running) "DISCONNECT" else "CONNECT & START MIC") }
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
