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
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Foreground service. Pipeline per 20 ms frame:
 * mic (VOICE_COMMUNICATION + AEC/NS/AGC) -> AudioProcessor -> [optional WAV on phone]
 * -> Opus -> AES-GCM -> UDP. The PC sends small ACKs; if they stop, the status shows
 * "Connection lost" and the app keeps trying (streaming resumes by itself).
 *
 * Packet = "PMC2"(4) codec(1) session(4 LE) seq(4 LE) + AES-GCM(payload)+tag(16).
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
        const val EXTRA_MODE = "mode" // wifi | usb | bluetooth
        const val EXTRA_REC = "rec"
        private const val CHANNEL_ID = "mic_stream"
        private const val SAMPLE_RATE = 48000
        private val BITRATES = intArrayOf(16000, 24000, 40000, 64000)
        private val FRAME_MS = intArrayOf(10, 20, 20, 40)
    }

    @Volatile private var active = false
    private var worker: Thread? = null
    private var ackThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var lastAck = 0L
    @Volatile private var everAcked = false
    @Volatile private var session = 0

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
        val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
        val wantAec = intent.getBooleanExtra(EXTRA_AEC, true)
        val wantNs = intent.getBooleanExtra(EXTRA_NS, true)
        val wantAgc = intent.getBooleanExtra(EXTRA_AGC, true)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "wifi"
        val record = intent.getBooleanExtra(EXTRA_REC, false)
        active = true
        // Keep the CPU awake while streaming so audio continues with the screen off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemic:stream")
            .also { it.acquire(12 * 60 * 60 * 1000L) }
        worker = Thread { streamLoop(ip, port, pin, wantAec, wantNs, wantAgc, mode, record) }.also { it.start() }
    }

    private fun newSocket(mode: String): DatagramSocket {
        val s = DatagramSocket()
        if (mode == "wifi") Discovery.bindToWifi(this@MicService, s)
        s.soTimeout = 500
        return s
    }

    private fun startAckListener() {
        ackThread = Thread {
            val buf = ByteArray(32)
            while (active) {
                val s = sock
                if (s == null) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    continue
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (p.length >= 8 && buf[0] == 'P'.code.toByte() && buf[1] == 'M'.code.toByte() &&
                        buf[2] == 'A'.code.toByte() && buf[3] == 'K'.code.toByte()
                    ) {
                        val sid = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (sid == session) {
                            lastAck = System.currentTimeMillis()
                            everAcked = true
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                }
            }
        }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun streamLoop(
        ip: String, port: Int, pin: String,
        wantAec: Boolean, wantNs: Boolean, wantAgc: Boolean,
        mode: String, record: Boolean
    ) {
        var rec: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        var wav: WavWriter? = null
        var errored = false
        try {
            MicState.aecAvailable = AcousticEchoCanceler.isAvailable()
            MicState.nsAvailable = NoiseSuppressor.isAvailable()
            MicState.agcAvailable = AutomaticGainControl.isAvailable()

            val addr = InetAddress.getByName(ip)
            val key: SecretKeySpec = Crypto.deriveKey(pin)
            session = Crypto.newSession()
            sock = newSocket(mode)
            startAckListener()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val frameMs = FRAME_MS[MicState.latencyMode.coerceIn(0, 3)]
            val frame = SAMPLE_RATE / 1000 * frameMs
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame * 2 * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                MicState.status = "Could not open microphone"
                errored = true
                return
            }
            val sid = rec.audioSessionId
            try { if (wantAec && MicState.aecAvailable) aec = AcousticEchoCanceler.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}
            try { if (wantNs && MicState.nsAvailable) ns = NoiseSuppressor.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}
            try { if (wantAgc && MicState.agcAvailable) agc = AutomaticGainControl.create(sid)?.also { it.enabled = true } } catch (_: Throwable) {}

            if (record) {
                try {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    if (dir != null) {
                        dir.mkdirs()
                        val name = "PhoneMic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
                        val f = File(dir, name)
                        wav = WavWriter(f, SAMPLE_RATE)
                        MicState.recordPath = f.absolutePath
                    }
                } catch (_: Exception) {
                    MicState.recordPath = ""
                }
            } else {
                MicState.recordPath = ""
            }

            rec.startRecording()
            MicState.running = true
            lastAck = System.currentTimeMillis()
            everAcked = false

            val proc = AudioProcessor(SAMPLE_RATE, frameMs)
            val enc = OpusPacketEncoder(BITRATES[MicState.quality.coerceIn(0, 3)])
            var applied: VoicePreset? = null

            val shorts = ShortArray(frame)
            val header = ByteArray(13)
            var seq = 0
            while (active) {
                val n = rec.read(shorts, 0, frame)
                if (n != frame) continue

                // Live settings (changes from the UI apply immediately).
                val cur = MicState.current()
                if (cur != applied) {
                    proc.setVoice(cur)
                    applied = cur
                }
                proc.noiseLevel = MicState.noiseLevel
                enc.setBitrate(BITRATES[MicState.quality.coerceIn(0, 3)])
                proc.process(shorts, n)

                wav?.write(shorts, n)

                var peak = 0
                for (i in 0 until n) {
                    val a = abs(shorts[i].toInt())
                    if (a > peak) peak = a
                }
                MicState.level = (peak / 32768f).coerceIn(0f, 1f)

                val payload = enc.encode(shorts, frame)
                val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                hb.put('P'.code.toByte()).put('M'.code.toByte()).put('C'.code.toByte()).put('2'.code.toByte())
                hb.put(2.toByte()) // codec 2 = Opus
                hb.putInt(session).putInt(seq)
                val ct = Crypto.encrypt(key, header, session, seq, payload)
                val out = ByteArray(13 + ct.size)
                System.arraycopy(header, 0, out, 0, 13)
                System.arraycopy(ct, 0, out, 13, ct.size)
                seq++

                try {
                    sock?.send(DatagramPacket(out, out.size, addr, port))
                } catch (e: IOException) {
                    // Network changed or dropped: rebuild the socket and keep going.
                    MicState.status = "Connection lost. Reconnecting..."
                    try { sock?.close() } catch (_: Exception) {}
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                    try { sock = newSocket(mode) } catch (_: Exception) {}
                }

                val now = System.currentTimeMillis()
                val lost = now - lastAck > 3000
                val st = when {
                    !lost -> "Connected to $ip"
                    everAcked -> "Connection lost. Reconnecting..."
                    else -> "Connecting to $ip... (check the PIN and network)"
                }
                MicState.connected = !lost
                if (MicState.status != st) MicState.status = st
            }
        } catch (e: Exception) {
            MicState.status = "Connection error: check the IP address and network"
            errored = true
        } finally {
            try { aec?.release() } catch (_: Throwable) {}
            try { ns?.release() } catch (_: Throwable) {}
            try { agc?.release() } catch (_: Throwable) {}
            try { rec?.stop() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
            wav?.close()
            try { sock?.close() } catch (_: Exception) {}
            sock = null
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
            wakeLock = null
            MicState.running = false
            MicState.connected = false
            MicState.level = 0f
            if (!errored) MicState.status = "Disconnected"
            active = false
        }
    }

    private fun stopStreaming() {
        active = false
        worker?.join(800)
        ackThread?.join(800)
        worker = null
        ackThread = null
    }
}
