package com.neonhud.app.core.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw chat message into comparable pieces: canonical tokens, content stems, intent,
 * follow-up / return cues and subject domains. Works for English, Hinglish (Hindi in Latin script)
 * and (approximately) Devanagari. Pure Java, no Android dependency.
 */
public final class TextTools {

    public enum Intent { DEFINE, HOWTO, WHY, WHEN, WHERE, WHO, QUANTITY, YESNO, COMPARE, CONTINUE, THANKS, OTHER }

    public static final class Parsed {
        public String raw;
        public List<String> tokens = new ArrayList<String>();          // canonical tokens in order
        public List<String> contentAll = new ArrayList<String>();      // distinct content stems (incl. numbers)
        public List<String> contentWords = new ArrayList<String>();    // distinct content stems without pure numbers
        public Set<String> domains = new LinkedHashSet<String>();
        /** Domain hit counts used to disambiguate mixed words such as "Dal" (food) inside a travel query. */
        public Map<String, Integer> domainHits = new HashMap<String, Integer>();
        /** stem -> the word as the user typed it (for readable topic names). */
        public Map<String, String> surface = new HashMap<String, String>();
        public List<String> rawTokens = new ArrayList<String>();
        public Intent intent = Intent.OTHER;
        public boolean returnCue;        // "wapas", "back to", "pichle topic" ...
        public boolean strongAnaphora;   // isme / uska / it / that ...
        public boolean weakAnaphora;     // ye / wo / this ...
        public boolean followStart;      // starts with aur / phir / and / also / what about ...
        public boolean ack;              // ok / thanks / haan ... (no real question)
        public boolean correctionCue;    // "nahi tum sikhao", "no code likho" -> correct the previous reply
    }

    private TextTools() { }

    // ------------------------------------------------------------------ word lists

    private static Set<String> setOf(String words) {
        return new HashSet<String>(Arrays.asList(words.trim().split("\\s+")));
    }

    private static final Set<String> STOP = setOf(
        "kya hai hain ho hoga hogi tha thi the kaise kaisa kaisi kyun kyu kyon kab kahan kahaan kaun kon kitna "
      + "kitni kitne kaunsa konsa kis kisne kisko kisi ka ki ke ko se me mein main par pe per ne aur ya bhi hi "
      + "to toh tho na nahi nahin mat nhi ye yeh yah wo woh wahi yahi is us in un isme ismein isko iska iski "
      + "isse iske ispe usme usmein usko uska uski usse uske uspe iss uss mujhe mujhko mera meri mere hume "
      + "humein hum aap aapko tum tumhe tera teri apna apni apne batao bata btao bataiye bataye bataen samjhao "
      + "samjha samjhaiye dikhao dikha karo kar kare kariye karna karte karta karti karein karun chahiye chahie "
      + "sakta sakti sakte lagta lagti lagte lagana lagao lagaye lete lena dena do de diya liye liya lie wala "
      + "wali wale jo jab tab agar magar lekin kuch koi sab sabhi bahut bohot thoda zyada jyada kam abhi ab "
      + "phir fir wapas wapis pehle pichle pichhle ek rahe raha rahi hoon hun hu sirf bas hoti hota hote "
      + "gaya gayi gaye jata jati jate aata aati aate milta milti milte "
      + "the a an is are was were be been am does did can could will would should shall may might must i me my "
      + "we you your he she it its they them this that these those of on at for from with by about as and or "
      + "but if so then than what how why when where who which tell explain please pls plz give show let get "
      + "need want know use using like just also some any all more most very much many there here into out up "
      + "down over again now ok okay yes no not hey hi hello thanks thank sir bro bhai yaar dost suno sun dekho "
      + "dekh kindly help tha bolo bol sakte hain kr krna krte kro hoga hga kitna bhaiya didi ji jee "
      + "ho jayega jayegi jaega jaegi jayenge chalega chalegi chalenge chalta chalti sahi theek thik possible "
      + "toh na kaha kya question topic sawal conversation chalo chalte chalein chalen aao aana back go lets let's continue next aage ao jao pura "
      + "bare baare wapas");

    private static final Set<String> STRONG_ANAPHORA = setOf(
        "isme ismein isko iska iski iske isse ispe usme usmein usko uska uski uske usse uspe it its them that same");
    private static final Set<String> WEAK_ANAPHORA = setOf("ye yeh yah wo woh vo this these those iss uss");
    private static final Set<String> FOLLOW_START = setOf("aur phir fir toh to and also but lekin magar then ya or");
    private static final Set<String> ACK = setOf(
        "ok okay haan han ha theek thik acha accha achha thanks thank thx shukriya dhanyavad samajh samjha hmm hm "
      + "yes no nahi nahin done great nice cool");
    private static final Set<String> RETURN_WORDS = setOf("wapas wapis waapas wapass pichle pichhle purane purana purani");

    private static final Map<String, String> VARIANTS = new HashMap<String, String>();
    static {
        String[][] v = {
            {"kese", "kaise"}, {"kaisay", "kaise"}, {"kaisey", "kaise"}, {"kesa", "kaisa"}, {"kaisi", "kaisa"},
            {"kia", "kya"}, {"kyaa", "kya"}, {"he", "hai"}, {"h", "hai"}, {"hy", "hai"}, {"hae", "hai"},
            {"mai", "mein"}, {"me", "mein"}, {"men", "mein"}, {"meinn", "mein"}, {"mien", "mein"},
            {"vapas", "wapas"}, {"vapis", "wapas"}, {"wapis", "wapas"}, {"waapas", "wapas"}, {"vaapas", "wapas"},
            {"vo", "wo"}, {"woh", "wo"}, {"vale", "wale"}, {"wali", "wale"}, {"vali", "wale"}, {"wala", "wale"},
            {"vala", "wale"}, {"liye", "lie"}, {"lie", "lie"}, {"btao", "batao"}, {"bta", "batao"}, {"bata", "batao"},
            {"bataiye", "batao"}, {"bataye", "batao"}, {"bataen", "batao"}, {"btaiye", "batao"},
            {"nhi", "nahi"}, {"nahin", "nahi"}, {"nai", "nahi"}, {"kr", "kar"}, {"krna", "karna"}, {"karo", "kar"},
            {"kro", "kar"}, {"plz", "please"}, {"pls", "please"}, {"thx", "thanks"}, {"u", "you"}, {"r", "are"},
            {"aap", "aap"}, {"tumhe", "tum"}, {"ko", "ko"}, {"kon", "kaun"}, {"konsa", "kaunsa"},
            {"pe", "par"}, {"per", "par"}, {"acha", "accha"}, {"achha", "accha"}, {"thik", "theek"},
            {"ismai", "isme"}, {"ismein", "isme"}, {"usmein", "usme"}, {"usmai", "usme"},
            {"sikho", "sikhao"}, {"sikhado", "sikhao"}, {"sikhado", "sikhao"}, {"sikhana", "sikhao"},
            {"likhdo", "likho"}, {"likhnaa", "likho"}, {"bnao", "banao"}, {"bnado", "banao"},
            {"kahaan", "kahan"}, {"kyu", "kyun"}, {"kyon", "kyun"}, {"kitni", "kitna"}, {"kitne", "kitna"},
            {"hain", "hai"}, {"hoon", "hun"}, {"hu", "hun"}, {"tho", "toh"}, {"to", "toh"}, {"esi", "ac"},
            // light bilingual / synonym folding so "toofan" = "cyclone", "ghar" = "home", "kiraya" = "fare" ...
            {"toofan", "tufan"}, {"tufaan", "tufan"}, {"cyclone", "tufan"}, {"storm", "tufan"},
            {"fare", "cost"}, {"kiraya", "cost"}, {"price", "cost"}, {"kimat", "cost"}, {"daam", "cost"},
            {"kharcha", "cost"}, {"home", "ghar"}, {"house", "ghar"}, {"timing", "time"}, {"timetable", "time"},
            {"samay", "time"}, {"schedule", "time"}, {"connection", "connect"}, {"installation", "install"},
            {"lagana", "install"}, {"lagta", "install"}, {"lagao", "install"}, {"lagate", "install"},
            {"installing", "install"}, {"wiring", "wire"}, {"taar", "wire"}, {"tar", "wire"}, {"taarein", "wire"},
            {"barish", "rain"}, {"baarish", "rain"}, {"garmi", "hot"}, {"thand", "cold"}, {"thandi", "cold"},
            {"dawai", "medicine"}, {"dawa", "medicine"}, {"ilaaj", "treatment"}, {"ilaj", "treatment"},
            {"remedies", "treatment"}, {"remedy", "treatment"}, {"khana", "food"}, {"kaise", "kaise"},
            {"tarika", "kaise"}, {"tariqa", "kaise"}, {"kitne", "kitna"},
            // Cross-language semantic folding used by the conversation matcher. These are
            // deliberately domain words, not generic grammar words, so an English question
            // and its Hinglish/Hindi equivalent share the same retrieval vocabulary.
            {"mausam", "weather"}, {"mosam", "weather"}, {"taapman", "temperature"}, {"darja", "temperature"},
            {"garmi", "hot"}, {"sardi", "cold"}, {"nami", "humidity"}, {"baarish", "rain"},
            {"barish", "rain"}, {"hawa", "wind"}, {"dhoop", "sun"}, {"badal", "cloud"},
            {"tatva", "element"}, {"tatvon", "element"}, {"avart", "periodic"}, {"sarni", "table"},
            {"rasayan", "chemistry"}, {"bhoutik", "physics"}, {"jeev", "biology"}, {"paudhe", "plant"},
            {"padhai", "study"}, {"pariksha", "exam"}, {"imtihaan", "exam"}, {"pathyakram", "syllabus"},
            {"anukram", "sort"}, {"kram", "order"}, {"chhant", "filter"}, {"talika", "list"},
            {"anumati", "permission"}, {"ijazat", "permission"}, {"adhikar", "permission"}, {"soochna", "notification"},
            {"suchna", "notification"}, {"sankalan", "compile"}, {"truti", "error"}, {"galti", "error"},
            {"samasy", "problem"}, {"dikkat", "problem"}, {"tarq", "logic"}, {"tark", "logic"},
            {"model", "model"}, {"prashikshan", "training"}, {"sikhana", "training"}, {"seekhna", "learn"},
            {"sikhna", "learn"}, {"anuman", "inference"}, {"tarkik", "reasoning"}, {"soch", "reasoning"},
            {"bijli", "electricity"}, {"bijlee", "electricity"}, {"taar", "wire"}, {"tar", "wire"},
            {"dhara", "current"}, {"vidyut", "voltage"}, {"pravah", "current"}, {"suraksha", "protection"},
            {"kiraya", "cost"}, {"bhada", "cost"}, {"mulya", "cost"}, {"dhan", "money"},
            {"byaj", "interest"}, {"bachat", "savings"}, {"nivesh", "invest"}, {"kar", "tax"},
            {"bima", "insurance"}, {"jama", "deposit"}, {"nikasi", "withdrawal"},
            {"rasoi", "cooking"}, {"pakwan", "recipe"}, {"vidhi", "recipe"}, {"samagri", "ingredient"},
            {"swaad", "taste"}, {"pakana", "cook"}, {"bhunna", "fry"}, {"ubalana", "boil"},
            {"dawai", "medicine"}, {"ilaaj", "treatment"}, {"bimari", "disease"}, {"lakshan", "symptom"},
            {"dard", "pain"}, {"bukhar", "fever"}, {"sehat", "health"}, {"vajan", "weight"},
            {"yatra", "travel"}, {"safar", "trip"}, {"rehna", "stay"}, {"thikana", "hotel"},
            {"rail", "train"}, {"gaadi", "train"}, {"ticket", "ticket"}, {"manzil", "destination"},
            {"daud", "run"}, {"gend", "ball"}, {"viket", "wicket"}, {"jeet", "win"}, {"haar", "loss"},
            {"khiladi", "player"}, {"muqabla", "match"}, {"mukabla", "match"}, {"ank", "score"},
            {"tasveer", "picture"}, {"chitra", "picture"}, {"dikhana", "show"}, {"drishya", "image"},
            {"website", "site"}, {"jankari", "info"}, {"jaankari", "info"},
            {"aj", "today"}, {"aaj", "today"}, {"kal", "tomorrow"}, {"abhi", "now"},
            {"kaunse", "which"},
            {"banao", "make"}, {"banaye", "make"}, {"banayein", "make"}, {"banate", "make"}, {"banane", "make"},
            {"ban", "make"}, {"bana", "make"}, {"mak", "make"}, {"tezi", "fast"}, {"tez", "fast"},
            {"raftar", "fast"}, {"kitni", "howmany"}, {"kitne", "howmany"}, {"kitna", "howmuch"}
        };
        for (String[] p : v) VARIANTS.put(p[0], p[1]);
    }

    // ------------------------------------------------------------------ normalisation

    private static final Pattern UNIT = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(sq\\s*\\.?\\s*mm|sqmm|mm|amperes?|amps?|a|volts?|v|watts?|w|kw|hp|gb|mb|tb|ghz|mhz|kg|km|cm|ml|l)(?![a-z])");
    private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{M}\\p{Nd}.+#]");
    private static final Pattern STRAY_DOT = Pattern.compile("(?<!\\d)\\.|\\.(?!\\d)");

    private static String canonUnit(String u) {
        u = u.replaceAll("\\s|\\.", "");
        if (u.startsWith("amp") || u.equals("a")) return "a";
        if (u.equals("sqmm") || u.equals("mm")) return "mm";
        if (u.startsWith("volt") || u.equals("v")) return "v";
        if (u.startsWith("watt") || u.equals("w")) return "w";
        return u;
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = transliterateDevanagari(s);
        s = s.replace("c++", "cpp").replace("c#", "csharp");
        Matcher m = UNIT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + canonUnit(m.group(2))));
        m.appendTail(sb);
        s = NON_TOKEN.matcher(sb.toString()).replaceAll(" ");
        s = STRAY_DOT.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /** Hindi-ish vowel spellings collapse together ("taar"/"tar", "seekhna"/"sikhna"). */
    private static String collapseVowels(String t) {
        return t.replace("aa", "a").replace("ee", "i").replace("ii", "i").replace("oo", "u").replace("uu", "u");
    }

    private static boolean hasDigit(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) return true;
        return false;
    }

    /** Light stemmer shared by messages and the lexicon (so both sides always agree). */
    private static final String[] HI_SUFFIX = {"ayein", "ayen", "aye", "ate", "ati", "ata", "ana", "ane", "ao", "ein", "ta", "ti", "te"};

    static String stemWord(String w) {
        String t = collapseVowels(w.toLowerCase(Locale.ROOT));
        if (hasDigit(t)) return t;
        if (t.length() >= 5) {
            for (String suf : HI_SUFFIX) {
                if (t.endsWith(suf) && t.length() - suf.length() >= 3) { t = t.substring(0, t.length() - suf.length()); break; }
            }
        }
        if (t.length() > 3 && t.endsWith("ies")) t = t.substring(0, t.length() - 3) + "y";
        else if (t.length() > 4 && t.endsWith("es") && !t.endsWith("ses")) t = t.substring(0, t.length() - 2);
        else if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
        if (t.length() > 4 && t.endsWith("ing")) {
            t = t.substring(0, t.length() - 3);
            if (t.length() > 3 && t.charAt(t.length() - 1) == t.charAt(t.length() - 2)) t = t.substring(0, t.length() - 1);
        } else if (t.length() > 4 && t.endsWith("ed")) {
            t = t.substring(0, t.length() - 2);
        }
        if (t.length() > 3 && t.endsWith("e")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Same folding a message token goes through (vowel collapse + spelling/synonym variants). */
    static String canonWord(String w) {
        String r = w.toLowerCase(Locale.ROOT);
        String t = collapseVowels(r);
        String v = VARIANTS.get(t);
        if (v == null) v = VARIANTS.get(r);
        return v != null ? v : t;
    }

    // ------------------------------------------------------------------ parsing

    public static Parsed parse(String text) {
        Parsed p = new Parsed();
        p.raw = text == null ? "" : text;
        String norm = normalize(p.raw);
        if (norm.isEmpty()) return p;
        String[] raw = norm.split("\\s+");
        p.rawTokens.addAll(Arrays.asList(raw));
        for (String r : raw) {
            String t = collapseVowels(r);
            String v = VARIANTS.get(t);
            if (v == null) v = VARIANTS.get(r);
            p.tokens.add(v != null ? v : t);
        }
        // cues -------------------------------------------------------------------------
        String joined = " " + join(p.tokens) + " ";
        p.returnCue = containsAny(p.tokens, RETURN_WORDS)
                || joined.contains(" back to ") || joined.contains(" go back ") || joined.contains(" return to ")
                || joined.contains(" pehle wale ") || joined.contains(" us wale ") || joined.contains(" us topic ")
                || joined.contains(" us sawal ") || joined.contains(" us question ") || joined.contains(" previous topic ")
                || joined.contains(" earlier topic ") || joined.contains(" previous question ")
                || joined.contains(" earlier question ") || joined.contains(" earlier conversation ")
                || joined.contains(" previous conversation ") || joined.contains(" us baat ");
        p.strongAnaphora = containsAny(p.tokens, STRONG_ANAPHORA);
        p.weakAnaphora = containsAny(p.tokens, WEAK_ANAPHORA)
                || ((joined.startsWith(" is ") || joined.startsWith(" us ") || joined.startsWith(" aur is ") || joined.startsWith(" aur us ")
                     || joined.startsWith(" iss ") || joined.startsWith(" uss "))
                    && (joined.contains(" ka ") || joined.contains(" ki ") || joined.contains(" ke ") || joined.contains(" ko ")
                        || joined.contains(" se ") || joined.contains(" par ") || joined.contains(" pe ")
                        || joined.contains(" mein ") || joined.contains(" kya ")));
        String first = p.tokens.get(0);
        p.followStart = FOLLOW_START.contains(first)
                || joined.startsWith(" what about ") || joined.startsWith(" how about ");
        // A correction/redirect such as "nahi tum sikhao" is not a standalone ACK.
        // It means: reject the previous direction and perform the requested action now.
        p.correctionCue = containsAny(p.tokens, setOf("nahi naheen no nahin"))
                && containsAny(p.tokens, setOf("sikhao teach likho code banao bana karo kar continue aage samjhao explain dikhao batao"));
        // content stems ----------------------------------------------------------------
        Set<String> all = new LinkedHashSet<String>();
        Set<String> words = new LinkedHashSet<String>();
        for (String t : p.tokens) {
            if (STOP.contains(t) || RETURN_WORDS.contains(t) || ACK.contains(t)) continue;
            if (t.length() < 2 && !hasDigit(t)) continue;
            String stem = stemWord(t);
            if (stem.length() < 1) continue;
            all.add(stem);
            if (!p.surface.containsKey(stem)) p.surface.put(stem, p.rawTokens.get(p.tokens.indexOf(t)));
            if (!isPureNumber(stem)) words.add(stem);
        }
        p.contentAll.addAll(all);
        p.contentWords.addAll(words);
        for (String w : words) {
            String d = hasDigit(w) ? Lexicon.domainOfMeasure(w) : Lexicon.domainOf(w);
            if (d != null) {
                p.domains.add(d);
                Integer c = p.domainHits.get(d);
                p.domainHits.put(d, c == null ? 1 : c + 1);
            }
        }
        // "model ko train karna / isko train karna" means AI model training, not a railway topic.
        if (joined.contains(" model train ") || joined.contains(" model ko train ")
                || joined.contains(" isko train ") || joined.contains(" llm train ") || joined.contains(" ai train ")) {
            p.domains.remove("transport");
            p.domains.add("ai");
        }
        p.ack = !p.correctionCue && words.isEmpty() && p.tokens.size() <= 4
                && containsAny(p.tokens, ACK) && allIn(p.tokens, ACK, STOP);
        p.intent = detectIntent(p, joined);
        return p;
    }

    /** True for words that are useful for grammar but usually add no task identity. */
    public static boolean isStructuralToken(String s) {
        return STOP.contains(s) || ACK.contains(s) || RETURN_WORDS.contains(s);
    }

    private static boolean isPureNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c) && c != '.') return false;
        }
        return true;
    }

    private static boolean allIn(List<String> toks, Set<String> a, Set<String> b) {
        for (String t : toks) if (!a.contains(t) && !b.contains(t)) return false;
        return true;
    }

    private static boolean containsAny(List<String> toks, Set<String> set) {
        for (String t : toks) if (set.contains(t)) return true;
        return false;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(' '); sb.append(s); }
        return sb.toString();
    }

    private static Intent detectIntent(Parsed p, String j) {
        List<String> t = p.tokens;
        if (containsAny(t, setOf("thanks shukriya dhanyavad"))) return Intent.THANKS;
        if (p.contentWords.isEmpty() && containsAny(t, setOf("aur next continue aage phir more then"))) return Intent.CONTINUE;
        if (containsAny(t, setOf("kyun why kaaran reason"))) return Intent.WHY;
        if (containsAny(t, setOf("kaise how tarika tariqa steps step method procedure process guide tutorial")))
            return Intent.HOWTO;
        if (containsAny(t, setOf("kab when timing baje schedule")) || j.contains(" time ")) return Intent.WHEN;
        if (containsAny(t, setOf("kahan kidhar where"))) return Intent.WHERE;
        if (containsAny(t, setOf("kaun who"))) return Intent.WHO;
        if (containsAny(t, setOf("kitna size price cost kimat rate daam range")) || j.contains(" how much ")
                || j.contains(" how many ") || j.contains(" how big ")) return Intent.QUANTITY;
        if (containsAny(t, setOf("vs versus difference farq better behtar compare comparison"))) return Intent.COMPARE;
        if (containsAny(t, setOf("chalega chalegi chalenge chalta chalti possible safe"))) return Intent.YESNO;
        if (j.contains(" kya hai ") || j.contains(" kya hota ") || j.contains(" what is ") || j.contains(" what are ")
                || j.contains(" whats ") || j.contains(" define ") || j.contains(" meaning ") || j.contains(" matlab ")
                || j.contains(" baare mein ") || j.contains(" bare mein ") || j.contains(" baare ") || j.contains(" bare ")
                || containsAny(t, setOf("explain samjhao describe detail details info jankari introduction intro overview batao about kya")))
            return Intent.DEFINE;
        return Intent.OTHER;
    }

    /** Are two intents close enough to count as "the same kind of question"? */
    public static boolean intentsCompatible(Intent a, Intent b) {
        if (a == b) return true;
        if (a == Intent.OTHER || b == Intent.OTHER) return a == Intent.OTHER && b == Intent.OTHER;
        // English/Hinglish translations can express the same request with a different
        // surface question word: "How much GST?" vs "GST kitna hai?" or
        // "How fast?" vs "kitni fast?". Content overlap is checked separately.
        if ((a == Intent.HOWTO && b == Intent.QUANTITY) || (a == Intent.QUANTITY && b == Intent.HOWTO)) return true;
        if ((a == Intent.DEFINE && b == Intent.WHO) || (a == Intent.WHO && b == Intent.DEFINE)) return true;
        if ((a == Intent.DEFINE && b == Intent.WHEN) || (a == Intent.WHEN && b == Intent.DEFINE)) return true;
        return false;
    }

    /**
     * Coarse action contract used to tell a small local model what the user wants done.
     * This is deliberately explicit for short imperative Hinglish where the subject is carried by context.
     */
    public static String actionOf(Parsed p) {
        if (p == null) return "answer";
        List<String> t = p.tokens;
        if (containsAny(t, setOf("code likho code do pura code full code coding code banao implement script likhne"))) return "write_code";
        if (containsAny(t, setOf("sikhao sikhao teach teaching lesson tutorial"))) return "teach";
        if (containsAny(t, setOf("compare difference farq versus vs behtar better"))) return "compare";
        if (containsAny(t, setOf("samjhao explain explanation matlab meaning"))) return "explain";
        if (containsAny(t, setOf("batao bata tell jawab answer"))) return "answer";
        if (p.intent == Intent.HOWTO) return "how_to";
        if (p.intent == Intent.WHY) return "explain_why";
        if (p.intent == Intent.QUANTITY) return "give_quantity";
        if (p.intent == Intent.YESNO) return "answer_yes_no";
        if (p.intent == Intent.COMPARE) return "compare";
        if (p.intent == Intent.CONTINUE || p.followStart || p.ack || p.correctionCue) return "continue";
        return "answer";
    }

    /**
     * Conservative count of distinct user requests. It catches numbered lists and repeated question marks
     * without treating normal "aur/or" wording as extra tasks.
     */
    public static int requestCount(String raw) {
        String x = raw == null ? "" : raw.trim();
        if (x.isEmpty()) return 0;
        int count = 0;
        Matcher m = Pattern.compile("(?m)(?:^|\\n)\\s*([0-9]{1,2})[.)]\\s+").matcher(x);
        int numbered = 0; while (m.find()) numbered++;
        if (numbered > 1) count = numbered;
        int q = 0; for (int i = 0; i < x.length(); i++) if (x.charAt(i) == '?') q++;
        if (q > count) count = q;
        if (count == 0) {
            int actionClauses = 0;
            String[] parts = x.split("\\b(?:aur|or|and)\\b|[;|]");
            for (String part : parts) {
                Parsed sub = parse(part);
                String a = actionOf(sub);
                if (!sub.contentWords.isEmpty() && !"answer".equals(a)) actionClauses++;
            }
            if (actionClauses > 1) count = actionClauses;
        }
        return Math.max(1, Math.min(8, count));
    }

    // ------------------------------------------------------------------ similarity helpers

    public static int levenshtein(String a, String b, int cap) {
        if (Math.abs(a.length() - b.length()) > cap) return cap + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > cap) return cap + 1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** Same word, allowing one typo in longer words. Tokens containing digits must match exactly. */
    public static boolean tokenMatch(String a, String b) {
        if (a.equals(b)) return true;
        if (hasDigit(a) || hasDigit(b)) return false;
        int min = Math.min(a.length(), b.length());
        if (min >= 8) return levenshtein(a, b, 2) <= 2;
        if (min >= 5) return levenshtein(a, b, 1) <= 1;
        return false;
    }

    /** True when the two lists mention the same measurement kind with different values (20a vs 32a). */
    public static boolean measureConflict(List<String> a, List<String> b) {
        // plain numbers ("Gemma 4" vs "Gemma 3") that share nothing also mean a different question
        boolean numA = false, numB = false, numCommon = false;
        for (String x : a) if (isPureNumber(x)) { numA = true; for (String y : b) if (x.equals(y)) numCommon = true; }
        for (String y : b) if (isPureNumber(y)) numB = true;
        if (numA && numB && !numCommon) return true;
        for (String x : a) {
            String ux = unitOf(x);
            if (ux == null) continue;
            for (String y : b) {
                if (ux.equals(unitOf(y)) && !x.equals(y)) return true;
            }
        }
        return false;
    }

    private static String unitOf(String tok) {
        if (tok.isEmpty() || !Character.isDigit(tok.charAt(0))) return null;
        int i = 0;
        while (i < tok.length() && (Character.isDigit(tok.charAt(i)) || tok.charAt(i) == '.')) i++;
        return i == tok.length() ? null : tok.substring(i);
    }

    /** Fuzzy set similarity in 0..1 (mix of overlap relative to the smaller and the larger set). */
    public static double similarity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        boolean[] used = new boolean[b.size()];
        int matched = 0;
        for (String x : a) {
            for (int j = 0; j < b.size(); j++) {
                if (!used[j] && tokenMatch(x, b.get(j))) { used[j] = true; matched++; break; }
            }
        }
        double small = Math.min(a.size(), b.size()), large = Math.max(a.size(), b.size());
        return 0.5 * matched / small + 0.5 * matched / large;
    }

    // ------------------------------------------------------------------ Devanagari (approximate)

    private static final Map<Character, String> CONS = new HashMap<Character, String>();
    private static final Map<Character, String> VOWELS = new HashMap<Character, String>();
    private static final Map<Character, String> MATRAS = new HashMap<Character, String>();
    static {
        String[] c = {"क","k","ख","kh","ग","g","घ","gh","ङ","n","च","ch","छ","ch","ज","j","झ","jh","ञ","n","ट","t","ठ","th",
            "ड","d","ढ","dh","ण","n","त","t","थ","th","द","d","ध","dh","न","n","प","p","फ","f","ब","b","भ","bh","म","m",
            "य","y","र","r","ल","l","व","w","श","sh","ष","sh","स","s","ह","h"};
        for (int i = 0; i < c.length; i += 2) CONS.put(c[i].charAt(0), c[i + 1]);
        String[] v = {"अ","a","आ","a","इ","i","ई","i","उ","u","ऊ","u","ऋ","ri","ए","e","ऐ","ai","ओ","o","औ","au"};
        for (int i = 0; i < v.length; i += 2) VOWELS.put(v[i].charAt(0), v[i + 1]);
        String[] m = {"ा","a","ि","i","ी","i","ु","u","ू","u","े","e","ै","ai","ो","o","ौ","au","ृ","ri"};
        for (int i = 0; i < m.length; i += 2) MATRAS.put(m[i].charAt(0), m[i + 1]);
    }

    static String transliterateDevanagari(String s) {
        boolean any = false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x0900 && s.charAt(i) <= 0x097F) { any = true; break; }
        if (!any) return s;
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (CONS.containsKey(ch)) {
                out.append(CONS.get(ch));
                char next = i + 1 < n ? s.charAt(i + 1) : ' ';
                if (next == '\u094D') { i++; continue; }                       // virama: no inherent a
                if (MATRAS.containsKey(next)) continue;                        // matra supplies the vowel
                if (next == '\u093C') continue;                                // nukta
                boolean wordEnd = !(next >= 0x0900 && next <= 0x097F);
                if (!wordEnd) out.append('a');                                 // inherent a (dropped at word end)
            } else if (VOWELS.containsKey(ch)) {
                out.append(VOWELS.get(ch));
            } else if (MATRAS.containsKey(ch)) {
                out.append(MATRAS.get(ch));
            } else if (ch == '\u0902' || ch == '\u0901') {
                out.append('n');
            } else if (ch == '\u0903') {
                out.append('h');
            } else if (ch >= 0x0966 && ch <= 0x096F) {
                out.append((char) ('0' + (ch - 0x0966)));
            } else if (ch == '\u094D' || ch == '\u093C') {
                // ignore stray signs
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
