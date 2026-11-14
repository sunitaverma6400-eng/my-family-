# Stark Family — Step 1 (सिर्फ़ relay)

## Render
1. Render > New > Blueprint > यही repo चुनिए (render.yaml अपने-आप पढ़ा जाएगा)।
2. Environment में डालिए:
   - APP_TOKEN = कोई लंबा random टेक्स्ट
   - GROQ_KEYS = ["gsk_...","gsk_..."]
   - GEMINI_KEYS = ["AIza...","AIza..."]
   - OPENROUTER_KEYS = ["sk-or-..."]   (चाहें तो खाली)
   (double quotes और square brackets ज़रूरी हैं; एक ही key हो तो ["gsk_..."])
3. Deploy के बाद Logs में ये लाइनें दिखनी चाहिए: `STARTUP groq keys=N`

## टेस्ट (app नहीं, सिर्फ़ browser)
https://<आपकी-service>.onrender.com/test खोलिए > APP_TOKEN डालिए >
- "मॉडल जाँच" दबाइए (कौन-सा model नाम सही है)
- Jarvis/Vision चुनकर "भेजो" दबाइए

## App (APK)
repo में push होते ही GitHub Actions APK बनाता है।
GitHub > Actions > "Build debug APK" हरा होने पर > Releases > `debug-latest` > app-debug.apk डाउनलोड करके install करें।
पहली बार ऐप खोलकर सेटिंग (⚙) में Relay URL (https://….onrender.com) और APP_TOKEN डालें।
