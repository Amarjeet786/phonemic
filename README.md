# Phone Mic (Phase 1)

Android phone mic -> Wi-Fi (UDP) -> Windows receiver -> audio output (use VB-Cable "CABLE Input" for a virtual mic).

## GitHub पर build करना
1. GitHub पर नया repo बनाएँ और यह पूरा folder upload/push करें.
2. Repo में **Actions** tab खोलें. दोनों workflows अपने-आप चलेंगे (या "Run workflow" दबाएँ).
3. Run पूरा होने पर नीचे **Artifacts** से डाउनलोड करें:
   - `PhoneMic-Android-APK` (debug APK)
   - `PhoneMic-Windows-Receiver` (PhoneMic.Windows.exe)

## इस्तेमाल
1. PC पर VB-Cable install करें (vb-audio.com/Cable).
2. Windows app खोलें -> Output device में "CABLE Input" चुनें -> START RECEIVER.
3. Phone और PC एक ही Wi-Fi पर रखें. Phone app में "Find my PC on Wi-Fi" दबाएँ, अपना PC चुनें (न मिले तो IP हाथ से डालें), PIN डालें -> CONNECT.
4. Zoom/OBS/Discord में microphone: "CABLE Output".

## अभी की सीमाएँ (Phase 1)
- Raw PCM, encrypted नहीं. Opus, encryption, noise-reduction engine अगले phases में.
- Bluetooth, installer, custom virtual driver अभी नहीं.
- कुछ भी अभी compile/test नहीं हुआ. Build error आए तो log मुझे भेजें.

## बिना इंटरनेट के
App audio और खोज दोनों सीधे Wi-Fi network से भेजता है, इसलिए router में इंटरनेट न हो तब भी चलता है. Laptop का Mobile Hotspot भी इस्तेमाल कर सकते हैं.
