package com.phonemic.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Foreground service: captures the microphone and sends 20 ms PCM16 mono 16 kHz
 * frames over UDP. Packet = "PMIC"(4) + pin(int32 LE) + seq(int32 LE) + PCM.
 * NOTE: Phase 1 sends raw PCM, unencrypted. Opus + encryption come in later phases.
 */
class MicService : Service() {
    companion object {
        const val ACTION_START = "com.phonemic.START"
        const val ACTION_STOP = "com.phonemic.STOP"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val EXTRA_AEC = "aec"
        const val EXTRA_NS = "ns"
        const val EXTRA_AGC = "agc"
        private const val CHANNEL_ID = "mic_stream"
        private const val SAMPLE_RATE = 16000
        private const val FRAME = 320 // 20 ms
    }

    @Volatile private var active = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopStreaming(); stopSelf() }
            ACTION_START -> {
                startForegroundCompat()
                if (!active) begin(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Microphone streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Mic is streaming")
            .setContentText("Your microphone is being sent to your PC")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun begin(intent: Intent) {
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 50505)
        val pin = intent.getStringExtra(EXTRA_PIN)?.toIntOrNull() ?: 0
        val wantAec = intent.getBooleanExtra(EXTRA_AEC, true)
        val wantNs = intent.getBooleanExtra(EXTRA_NS, true)
        val wantAgc = intent.getBooleanExtra(EXTRA_AGC, true)
        active = true
        worker = Thread { streamLoop(ip, port, pin, wantAec, wantNs, wantAgc) }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun streamLoop(ip: String, port: Int, pin: Int, wantAec: Boolean, wantNs: Boolean, wantAgc: Boolean) {
        var rec: AudioRecord? = null
        var socket: DatagramSocket? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            MicState.aecAvailable = AcousticEchoCanceler.isAvailable()
            MicState.nsAvailable = NoiseSuppressor.isAvailable()
            MicState.agcAvailable = AutomaticGainControl.isAvailable()

            val addr = InetAddress.getByName(ip)
            socket = DatagramSocket()
            Discovery.bindToWifi(this, socket)
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME * 2 * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                MicState.status = "Could not open microphone"
                return
            }
            val sid = rec.audioSessionId
            try { if (wantAec && MicState.aecAvailable) aec = AcousticEchoCanceler.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}
            try { if (wantNs && MicState.nsAvailable) ns = NoiseSuppressor.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}
            try { if (wantAgc && MicState.agcAvailable) agc = AutomaticGainControl.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}

            rec.startRecording()
            MicState.running = true
            MicState.status = "Streaming to $ip"

            val shorts = ShortArray(FRAME)
            val buf = ByteBuffer.allocate(12 + FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
            var seq = 0
            while (active) {
                val n = rec.read(shorts, 0, FRAME)
                if (n <= 0) continue
                var peak = 0
                buf.clear()
                buf.put('P'.code.toByte()).put('M'.code.toByte()).put('I'.code.toByte()).put('C'.code.toByte())
                buf.putInt(pin).putInt(seq++)
                for (i in 0 until n) {
                    val s = shorts[i].toInt()
                    if (abs(s) > peak) peak = abs(s)
                    buf.putShort(shorts[i])
                }
                MicState.level = (peak / 32768f).coerceIn(0f, 1f)
                socket.send(DatagramPacket(buf.array(), 12 + n * 2, addr, port))
            }
        } catch (e: Exception) {
            MicState.status = "Connection error: check the IP address and Wi-Fi"
        } finally {
            try { aec?.release() } catch (_: Throwable) {}
            try { ns?.release() } catch (_: Throwable) {}
            try { agc?.release() } catch (_: Throwable) {}
            try { rec?.stop() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
            socket?.close()
            MicState.running = false
            MicState.level = 0f
            if (MicState.status.startsWith("Streaming")) MicState.status = "Disconnected"
            active = false
        }
    }

    private fun stopStreaming() {
        active = false
        worker?.join(500)
        worker = null
    }
}
