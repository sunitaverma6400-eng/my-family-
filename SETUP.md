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

## बबल (Milestone 2)
1. ऐप खोलकर ऊपर "🫧 बबल" दबाइए > "दूसरे ऐप्स के ऊपर दिखाएँ" की अनुमति दीजिए > वापस आकर फिर "🫧 बबल" दबाइए।
2. vivo/Funtouch: Settings > Apps > Stark Family > Permissions में "Display pop-ups while running in background" (बैकग्राउंड पॉप-अप) और "Autostart" चालू कीजिए, और Battery में "Allow background activity" चुनिए।
3. YouTube जैसे किसी ऐप पर जाइए > बबल दबाइए > किरदार चुनिए > लिखकर बात कीजिए। ✕ से बबल बन जाता है, 25 सेकंड बाद अपने-आप भी।

## औज़ार (Milestone 3)
बबल में Jarvis से: "छह बजे का अलार्म लगाओ", "टॉर्च चालू करो", "YouTube खोलो", "राहुल को कॉल करो" (कॉल से पहले "हाँ/नहीं" पूछता है)।
- Friday: अलार्म/टाइमर/रिमाइंडर/ऐप खोलना; Edith, Dum-E, U: टॉर्च और ऐप खोलना; Vision, Ultron, Karen: कोई फ़ोन-औज़ार नहीं।
- संपर्क से कॉल के लिए ऐप को "संपर्क" की अनुमति चाहिए (पहली बार ऐप खोलने पर पूछेगा)।
- कॉल "डायलर" खोलता है; हरा बटन आप खुद दबाते हैं।

## याददाश्त (Milestone 4)
- ऐप में ऊपर "📚" दबाइए: तथ्य / सार / बातचीत / नोट्स। हर चीज़ बदली या मिटाई जा सकती है; "सब मिटाओ" से सब हमेशा के लिए हट जाता है।
- किसी से कहिए "याद रखो, मेरी परीक्षा 20 तारीख़ को है" तो वह तथ्य सहेज लेता है। पासवर्ड, OTP, कार्ड नंबर कभी नहीं सहेजे जाते।
- पिछले दिनों की बातचीत से रोज़ का सार, फिर हफ़्ते का, फिर आपका प्रोफ़ाइल अपने-आप बनता है (ऐप खुलने पर, ज़्यादा से ज़्यादा 30 मिनट में एक बार)।
- 💾 बैकअप से एक zip बनती है, 📥 रीस्टोर से उसे वापस ला सकते हैं। सारी याददाश्त फ़ोन पर रहती है; relay सिर्फ़ सार बनाने के लिए बातचीत देखता है, कुछ सहेजता नहीं।
