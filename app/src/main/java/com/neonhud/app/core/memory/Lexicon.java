package com.neonhud.app.core.memory;

import java.util.HashMap;
import java.util.Map;

/**
 * A small built-in vocabulary (English + Hinglish) that maps words to broad subject domains.
 * It lets "Isme 2.5mm wire chalega?" be recognised as electrical even though it shares no exact
 * word with "AC ke liye 20A MCB kaise lagta hai?". Topics outside this vocabulary still work through
 * plain keyword overlap - they just lose the domain shortcut.
 */
final class Lexicon {
    private static final Map<String, String> DOMAIN_OF_STEM = new HashMap<String, String>();
    private static final Map<String, String> DISPLAY = new HashMap<String, String>();

    private Lexicon() { }

    static String domainOf(String stem) { return DOMAIN_OF_STEM.get(stem); }

    static String display(String domain) {
        String d = DISPLAY.get(domain);
        return d == null ? domain : d;
    }

    /** "20a", "2.5mm", "1500w" ... -> domain implied by the unit, or null. */
    static String domainOfMeasure(String token) {
        if (token.endsWith("mm") || token.endsWith("kw") || token.endsWith("hp")) return "electrical";
        if (token.length() > 1 && Character.isDigit(token.charAt(0))) {
            char last = token.charAt(token.length() - 1);
            if (last == 'a' || last == 'v') return "electrical";
        }
        return null;
    }

    private static void add(String domain, String display, String words) {
        DISPLAY.put(domain, display);
        for (String w : words.trim().split("\\s+")) {
            String stem = TextTools.stemWord(TextTools.canonWord(w));
            if (stem.length() >= 2 && !DOMAIN_OF_STEM.containsKey(stem)) DOMAIN_OF_STEM.put(stem, domain);
        }
    }

    static {
        add("electrical", "Electrical wiring",
            "ac mcb mccb elcb rccb wire wiring cable switch socket plug earthing earth voltage volt ampere amp "
          + "current fuse breaker phase neutral inverter geyser fan cooler bulb led tubelight meter copper aluminium "
          + "transformer ups stabilizer electrician bijli kilowatt watt capacitor relay contactor switchboard "
          + "conduit insulation shock overload tripping trip mcb taar tar bijlee wattage motor pump starter "
          + "compressor refrigerator fridge heater geyser induction");
        add("transport", "Train & transport",
            "train rail railway irctc station platform ticket pnr coach sleeper tatkal bus metro flight airport "
          + "boarding express rajdhani waitlist berth local locomotive engine gaadi "
          + "gadi railgadi vande shatabdi duronto departure arrival delayed");
        add("android", "Android app development",
            "android apk gradle kotlin manifest activity fragment layout xml adb emulator sdk jetpack compose "
          + "recyclerview listview permission viewmodel sqlite room aab playstore keystore proguard lint material "
          + "theme appcompat androidx bitmap canvas drawable notification foreground immersive statusbar cutout "
          + "insets keyboard ime scroll ui screen apkbuild buildgradle logcat mainactivity intent");
        add("coding", "Programming",
            "python java javascript js typescript cpp csharp code coding program function loop array list dict "
          + "string variable class object bug error exception compile compiler debug algorithm recursion sort api "
          + "json sql database git github regex script syntax pointer thread async lambda stack queue graph leetcode "
          + "oop inheritance html css react node docker linux bash terminal pip npm dsa programming coder");
        add("ai", "AI models",
            "gemma llm model token prompt litert litertlm gguf quantization inference gpu npu context ai chatbot "
          + "neural transformer parameter e2b e4b mediapipe qwen llama gpt embedding finetune dataset training "
          + "hallucination offline");
        add("cooking", "Cooking & food",
            "recipe roti chapati dal sabzi paneer chawal rice masala tadka biryani pasta chai tea sugar namak salt "
          + "tel ghee dough atta aata oven bake boil fry curry khana cook pakana pakaana kadhai cooker tawa "
          + "haldi mirch jeera dhaniya pyaz onion tomato tamatar aloo potato dosa idli samosa pizza");
        add("cricket", "Cricket",
            "cricket duckworth lewis icc format t20 super lbw match ipl wicket batting bowling over run kohli dhoni rohit score innings odi t20 bumrah "
          + "captain umpire stadium tournament batsman bowler fielder catch six four psl worldcup pitch toss");
        add("health", "Health",
            "bukhar fever sardi cough khansi dawai medicine doctor dard pain headache stomach pet vitamin diet "
          + "exercise gym yoga sleep neend bp diabetes protein weight calorie injury tablet paracetamol symptom "
          + "infection allergy thakan nutrition fitness workout");
        add("finance", "Money & finance",
            "deposit fixed recurring loan emi bank sip mutual fund gst tax itr share stock nifty sensex interest byaj fd rd insurance upi "
          + "credit debit card salary budget invest investment ppf nps demat crypto bitcoin savings paisa rupee "
          + "rupaye profit loss dividend");
        add("weather", "Weather",
            "mausam weather barish rain temperature garmi humidity forecast monsoon thand dhup storm toofan cyclone "
          + "aandhi snow fog kohra heatwave sunny cloudy");
        add("education", "Study & exams",
            "newton laws motion gravity exam syllabus physics chemistry maths math biology history geography neet jee upsc board school "
          + "college homework notes chapter formula theorem result admission scholarship degree mock pariksha "
          + "padhai study subject teacher paper marks");
        add("devices", "Phone & gadgets",
            "phone mobile charger wifi bluetooth camera ram storage laptop tablet earphone headphone smartwatch "
          + "printer router tv television speaker samsung iphone realme redmi oneplus pixel firmware");
        add("travel", "Travel & stays",
            "hotel visa passport tour trip vacation resort itinerary goa manali ladakh kashmir shimla holiday "
          + "sightseeing homestay hostel backpacking cruise");
    }
}
