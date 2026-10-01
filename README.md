# Phone Mic

Android phone mic -> (Wi-Fi / USB cable / Bluetooth tethering) -> Windows receiver -> output device.
Use VB-Cable "CABLE Input" as the output device to get a virtual microphone ("CABLE Output") for Zoom/OBS/Discord.

## GitHub पर build
1. पूरा folder repo में upload करें (hidden `.github` folder भी).
2. Actions tab में दोनों workflows चलने दें.
3. Artifacts: `PhoneMic-Android-APK`, `PhoneMic-Windows-Receiver` (exe), `PhoneMic-Windows-Installer` (Setup exe; fail हो तो exe से काम चलाएँ).

## इस्तेमाल
1. PC पर VB-Cable install करें (vb-audio.com/Cable).
2. Windows app: Output device = "CABLE Input" -> START RECEIVER. IP और PIN दिखेगा (PIN याद रखा जाता है).
3. Phone app: connection type चुनें -> "Find my PC" -> PC चुनें -> PIN -> CONNECT.
4. Zoom/OBS/Discord में microphone = "CABLE Output".

## Features (v0.3)
- **Connection:** Wi-Fi (पहले), USB cable (USB tethering), Bluetooth (tethering; असली BT mic नहीं). Auto-discovery + auto reconnect.
- **Codec:** Opus (48 kHz mono, VOIP) + AES-256-GCM encryption (key = PIN से). Quality: Low/Medium/High/Ultra (16/24/40/64 kbps). Packet loss पर Opus PLC.
- **Ultra-low latency:** Latency Ultra Low (10 ms frames) / Low / Balanced / High Quality; Windows पर छोटा jitter buffer (20 ms तक) जो नेटवर्क बिगड़ने पर अपने-आप थोड़ा बढ़ता है.
- **Phone DSP chain:** High-pass -> Noise Gate -> EQ -> De-esser -> Compressor -> Limiter (सब live, sliders के साथ).
- **Voice presets:** Off, Natural, Clear, Bold (slider), Podcast, Streaming, Meeting.
- **RNNoise (AI noise removal):** Windows receiver में checkbox (native library से; न मिले तो app बिना इसके चलता है).
- **Virtual mic:** VB-Cable के ज़रिए (docs/VIRTUAL_MIC.md).
- Windows: monitor (headphones), WAV recording, tray, optional auto-start, installer, loss/jitter/bitrate/latency display.

## सीमाएँ (ईमानदारी से)
- RNNoise PC पर चलता है (phone पर नहीं), यानी phone की EQ/compressor उससे पहले लगती है. सबसे साफ नतीजे के लिए noisy जगह में "Natural" preset से शुरू करें.
- अपना virtual mic driver नहीं (signed kernel driver चाहिए) -- VB-Cable चाहिए.
- QR pairing, sample-rate selector नहीं. PIN 6 अंकों का है (असली key-exchange बाद में).
- कुछ भी अभी compile/test नहीं हुआ. Build error आए तो log भेजें.

---
Created by Amarjeet K Gupta - WhatsApp No: 8707018073
