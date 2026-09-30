# Phone Mic (v0.2)

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

## Features
- Wi-Fi, USB cable (USB tethering), Bluetooth (Bluetooth tethering; असली BT mic नहीं, delay ज़्यादा).
- Encrypted audio (AES-256-GCM, key = PIN से) + ADPCM compression (~77 kbps).
- Noise Reduction Off/Low/Medium/High (adaptive expander), Voice presets: Off, Natural, Clear, Bold (slider), Podcast, Streaming, Meeting. Live बदलते हैं.
- Auto reconnect + connection status (ACK), Wi-Fi ही चुना जाए (बिना इंटरनेट के भी).
- Windows: latency selector, jitter buffer, loss/jitter/bitrate/latency display, monitor (headphones), recording (WAV), tray, optional auto-start, installer.
- Phone पर optional WAV recording (app की Music folder में).

## सीमाएँ (ईमानदारी से)
- Opus नहीं (ADPCM). RNNoise/AI noise removal नहीं. QR pairing नहीं. Sample-rate selector नहीं (16 kHz mono).
- PIN से बनी key: आम sniffing से बचाती है, पर 6-digit PIN के कारण दृढ़ हमलावर के लिए कमज़ोर है. असली key-exchange बाद में.
- अपना virtual mic driver नहीं; VB-Cable चाहिए (installer उसका link खोल सकता है, bundle नहीं करता).
- कुछ भी अभी compile/test नहीं हुआ. Build error आए तो log भेजें.
