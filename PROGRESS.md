# Stark Family AI — प्रगति (Milestone 6 तक)

इस एक zip में अब तक का पूरा प्रोजेक्ट है: relay (Flask/Render) + Android ऐप + GitHub Actions + टूल्स + मूल PDF (docs/)।

## फ़ोल्डर
- `relay/`            Render पर चलने वाला Flask relay (Model Router, personas, औज़ार, याददाश्त का /summarize)
- `android/`          Android ऐप (Kotlin + Compose + Room)
- `.github/workflows` APK बनाने वाला GitHub Actions
- `docs/`             मूल Master Prompt PDF (v5)
- `tools/push_s1.sh`  Termux में push करने की स्क्रिप्ट
- `render.yaml`, `SETUP.md`  Render और ऐप की सेटिंग के नोट्स

## Milestones (PDF के क्रम में)
| # | काम | स्थिति |
|---|-----|--------|
| 1 | Relay + Model Router (key pool, fallback, cooldown, Brain Status), Hindi personas, चैट | ✅ पूरा, चल रहा है |
| 2 | Overlay + floating bubble + animated अवतार | ✅ पूरा, चल रहा है |
| 3 | औज़ार: अलार्म, टाइमर, रिमाइंडर, ऐप खोलना, टॉर्च, कॉल (पूछकर) | ✅ पूरा, चल रहा है |
| 4 | याददाश्त: Room, सार-सीढ़ी, याद ढूँढना, Memory स्क्रीन, बैकअप | ✅ पूरा, चल रहा है |
| 5 | Agent Engine v1 (Accessibility: स्क्रीन पढ़ना, टैप, स्क्रॉल, टाइप, verify loop, STOP, pause, Activity log) | 🟡 install हो गया; "Settings में बैटरी usage" वाली कसौटी की रिपोर्ट बाक़ी |
| 6 | Agent Engine v2 (Chrome + Render जाँच, screenshot-vision, रिपोर्ट कार्ड, prompt-injection और privacy नियम) | 🟡 बन गया; फ़ोन पर परखना बाक़ी |
| 7 | गहरी नेविगेशन + चीज़ें ढूँढना + ऐप-स्किल याददाश्त | ⏳ |
| 8 | Context engine + Rule engine + बोलकर सिखाना + Rules स्क्रीन | ⏳ |
| 9 | Proactive व्यवहार + autonomy levels + kill switch | ⏳ |
| 10 | Research Engine + talent registry + परिवार से पूछना | ⏳ |
| 11 | Hindi आवाज़: नाम से जगाना, STT, हर किरदार की TTS, lip-sync | ⏳ |
| 12 | बाक़ी किरदारों की पूरी सुविधाएँ, group chat, परिवार के रिश्ते | 🟡 8 किरदार (सिर्फ़ बातचीत + औज़ार) बन चुके |
| 13 | Family Room (live scenes) | ⏳ |
| 14 | Stark Tower theme packs, Tower होम स्क्रीन | ⏳ |
| 15 | Holograms + Realistic 3D, auto-quality | ⏳ |
| 16 | परिवार की गतिशीलता (Ultron-Vision तनाव, moods), polish, battery | ⏳ |

## M5 में क्या है / क्या नहीं (ईमानदारी से)
- है: Accessibility से स्क्रीन पढ़ना (node tree), टैप/लंबा दबाना/स्क्रॉल/स्वाइप/टाइप/Enter/Back/Home/Recents/ऐप खोलना, हर क़दम के बाद जाँच (verify), एक बार दूसरा तरीक़ा, फिर आपसे पूछना, लूप की पहचान, 30 क़दम और 5 मिनट की सीमा, ⏹ STOP, स्क्रीन छूने पर ठहरना, छूने वाली चीज़ की रंगीन रूपरेखा, 📋 गतिविधि लॉग (हर क़दम), संवेदनशील ऐप पर रोक, पासवर्ड-फ़ील्ड/OTP/कार्ड की छँटाई, "कुछ बदलने से पहले पूछना", Ultron सिर्फ़ Observer, notification में "⏹ काम रोको"।
- नहीं (आगे): screenshot-vision fallback और रिपोर्ट कार्ड (M6), ऐप-स्किल याददाश्त (M7), पूरा global kill switch और सेटिंग में block/allow सूची (M9), गतिविधि लॉग में thumbnail (M6)।

## M6 में क्या है / क्या नहीं (ईमानदारी से)
- है: स्क्रीनशॉट से पढ़ना (Accessibility की takeScreenshot, Android 11+; मेमोरी में ही, डिस्क पर कभी नहीं; खींचते समय बबल छिप जाता है), relay का vision tier, जिस स्क्रीन का tree ख़ाली हो वहाँ अपने-आप दृश्य-मोड, मॉडल 'look' माँगकर चार्ट पढ़वा सकता है, सटीक 'calc' (अंदाज़ा नहीं), रिपोर्ट कार्ड (स्थिति/सबूत/संभावित कारण/सुझाव + रिपोर्ट/सुझाव कॉपी + फिर जाँचो), prompt-injection की दोहरी पहचान (relay में शब्द-पैटर्न + मॉडल का झंडा) और स्क्रीन-पाठ पर बाड़, 📤 संकेत जब स्क्रीन relay को जा रही हो, ऐप-वार बंद करने की सूची, क़दम-सीमा की सेटिंग, Chrome/Render की जाँच के निर्देश।
- शर्तें: vision के लिए relay में चालू Gemini key (या कोई vision-सक्षम OpenRouter मॉडल) चाहिए; Gemini key अभी ठीक करनी बाक़ी है। जो चीज़ tree में दिखे (Chrome की वेब-सामग्री अक्सर दिखती है) उसके लिए vision ज़रूरी नहीं।
- नहीं (आगे): ऐप-स्किल याददाश्त और गहरी खोज (M7), global kill switch (M9), रिपोर्ट आवाज़ में (M11)।

## अभी के छोटे बाक़ी काम
- Gemini की key ठीक करना (backup provider), OpenRouter का free model चुनना।
- याद ढूँढने में embedding, database encryption (PDF में optional)।
- Quick Settings tile, app shortcut, voice wake, Level-2 स्वचालित काम आदि (आगे के milestones में)।
