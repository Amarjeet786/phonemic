# Virtual Microphone (Windows)

## अभी जो काम करता है: VB-Cable
Phone Mic receiver audio को "CABLE Input" (VB-Cable का virtual speaker) में भेजता है.
Windows में उसी का दूसरा सिरा "CABLE Output" एक microphone की तरह दिखता है.
Zoom / OBS / Discord / Meet / Teams / Audacity आदि में microphone = "CABLE Output" चुनें.

1. vb-audio.com/Cable से VB-Cable डाउनलोड करके install करें (Run as administrator), PC restart करें.
2. Phone Mic खोलें -> "Refresh devices" -> Output device = "CABLE Input".
3. दूसरे app में microphone = "CABLE Output".

VB-Cable का license उसे अपने installer में साथ बाँटने (bundle) से रोकता है, इसलिए installer सिर्फ उसका डाउनलोड पेज खोलता है.

## अपना खुद का virtual mic driver (अलग प्रोजेक्ट)
यह इस repo में शामिल नहीं है, क्योंकि:
- Windows का virtual audio device kernel-mode driver होता है (WDK में SysVAD / Virtual Audio Driver sample पर आधारित).
- इसे बनाने के लिए Visual Studio + WDK चाहिए, और चलाने के लिए Microsoft की signing (EV code-signing certificate + Partner Center attestation) ज़रूरी है. बिना signing के Windows इसे load नहीं करता.
- Driver और receiver के बीच audio पहुँचाने का अलग तरीका (shared memory / IOCTL) बनाना पड़ता है.

अगर आगे यह करना हो, तो receiver की तरफ बस Output device की जगह driver का endpoint लिखना होगा; बाकी pipeline वैसी ही रहेगी.
