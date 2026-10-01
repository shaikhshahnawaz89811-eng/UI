package com.neonhud.app.core.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides, for each message, whether the phone should go to the internet and what to do with the answer:
 * show pictures, only read them, give one link / several, give steps, compare.
 * Pure rules (English + Hinglish + typing mistakes), no model call, so it is instant, free and testable.
 *
 * Order of thought: hard "no" (greeting, code, maths, story ...) -> what the user asked for (search words, link, picture,
 * url) -> what a small offline model cannot know (prices, scores, news, weather, who-is-the-current, opening hours,
 * government procedures) -> otherwise answer offline.
 */
public final class SearchPlanner {
    private SearchPlanner() { }

    // ------------------------------------------------------------------ vocabulary

    private static final Pattern URL = Pattern.compile("(https?://[^\\s<>\"']+|www\\.[^\\s<>\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(20[2-9][0-9])\\b");

    private static final Pattern EXPLICIT_SEARCH = Pattern.compile(
            "\\b(search|serch|sarch|seach|google|gogle|dhundh\\w*|dhoondh\\w*|dhund\\w*|khoj\\w*)\\s*(kar\\w*|kro|kr|kijiye|kije|do|de|dena|lagao|lagana|maro|karke|it|this|that|for|on|about)\\b"
          + "|^(search|google|serch)\\s+\\w+"
          + "|\\b(net|internet|inter net|web|online)\\s*(pe|par|se|mein|me|per)\\s+(dekh\\w*|check|search|serch|pata|dhund\\w*|khoj\\w*|padh\\w*|read|find|batao|bata\\w*|kar\\w*)"
          + "|\\b(net|internet|web)\\s*(pe|par|se)\\b"
          + "|\\b(look\\s?up|check\\s+online|find\\s+online|search\\s+online|search\\s+the\\s+web|web\\s+search|net\\s+search|online\\s+search|browse)\\b"
          + "|\\bnetsearch\\b|\\bpata\\s+kar\\w*\\b.*\\b(net|online|internet)\\b"
          + "|\\bonline\\s+[^.?!]*\\b(dhoond\\w*|dhund\\w*|find|search|check|pata\\s+kar\\w*)\\b");
    private static final Pattern NOT_A_SEARCH_REQUEST = Pattern.compile("\\b(binary|linear|depth first|breadth first|dfs|bfs|interpolation)\\s+search\\b|\\bsearch\\s+(engine|algorithm|bar|box|tree)\\b");

    private static final Pattern LINK_WORD = Pattern.compile("\\b(links?|lnk|urls?|website|websites|site|sites|webpage|web page|portal|official page|kahan\\s+milega|kaha\\s+milega|kahan\\s+se\\s+(le|kar|download|mil)\\w*|kaha\\s+se\\s+(le|kar|download|mil)\\w*|where\\s+can\\s+i\\s+(get|find|buy|download|apply|book|register)|download\\s+(link|page)|sources?|reference|references|refrence)\\b");
    private static final Pattern MANY_LINKS = Pattern.compile("\\b(links|urls|websites|sites|sources|references|refrences|kuch\\s+links?|multiple|sab\\s+links?|alag\\s+alag|kai\\s+links?|few\\s+links?|some\\s+links?|more\\s+links?)\\b");

    private static final Pattern IMAGE_NOUN = Pattern.compile("\\b(photos?|foto|fotos|pics?|pictures?|images?|imgs?|tasveer\\w*|tasvir\\w*|wallpapers?|logo|logos|diagram|diagrams|screenshots?|map\\s+of|ka\\s+map|ke\\s+map|blueprint|infographic|chitra)\\b");
    private static final Pattern LOOKS_LIKE = Pattern.compile("\\b(kaisa|kaisi|kaise)\\s+(dikh\\w*|lag\\w*|dikhta|dikhti)\\b|\\blook\\s*s?\\s+like\\b|\\bdikh\\w*\\s+kaisa\\b|\\bkaisa\\s+hota\\s+hai\\s+dikhne\\b");
    private static final Pattern SHOW_WORD = Pattern.compile("\\b(dikha\\w*|dekha\\w*|show\\s+me|show|dikhai\\w*)\\b");
    private static final Pattern MAKE_IMAGE = Pattern.compile("\\b(banao|bana\\s+do|bana\\s+de|generate|create|draw|design|edit|make)\\b.*\\b(image|photo|pic|picture|logo|poster|wallpaper)\\b|\\b(image|photo|pic|picture|logo|poster)\\b.*\\b(banao|bana\\s+do|bana\\s+de|generate|create|draw)\\b");
    private static final Pattern THIS_ATTACHED = Pattern.compile("\\b(is|iss|ye|yeh|this|attached|upar wali|uploaded)\\s+(image|photo|pic|picture|pdf|file|zip|screenshot)\\b");

    private static final Pattern NO_IMG = Pattern.compile("\\b(images?|photos?|pics?|pictures?|tasveer\\w*|foto)\\s+(mat|nahi|nhi|nahin|no)\\b|\\b(mat|nahi|nhi|no)\\s+(dikha\\w*|show)\\s+(images?|photos?|pics?|pictures?)\\b|\\b(without|no)\\s+(any\\s+)?(images?|photos?|pics?|pictures?)\\b|\\b(images?|photos?|pics?)\\s+nahi\\s+chahiye\\b");
    private static final Pattern NO_LINK = Pattern.compile("\\b(links?|urls?|websites?)\\s+(mat|nahi|nhi|nahin|no)\\b|\\b(mat|nahi|nhi)\\s+(do|de|dena|dijiye|bhejo|bhejna)\\s+(links?|urls?)\\b|\\b(without|no|bina)\\s+(any\\s+)?(links?|urls?)\\b|\\blinks?\\s+nahi\\s+chahiye\\b");
    private static final Pattern READ_ONLY = Pattern.compile("\\b(sirf|bas|only|just)\\s+(padh\\w*|read|bata\\w*|btao|samjha\\w*|tell)\\b|\\bpadh\\s*ke\\b|\\bpadhke\\b|\\bread\\s+only\\b|\\bdikha\\w*\\s+(mat|nahi|nhi|nahin)\\b|\\b(do\\s+not|dont|don\\s?t|without)\\s+show\\w*\\b|\\bshow\\s+mat\\b|\\bdikhana\\s+(nahi|nhi|mat)\\b");
    private static final Pattern READ_IMAGE = Pattern.compile("\\b(images?|photos?|pics?|pictures?|tasveer\\w*|screenshot|diagram)\\b.*\\b(padh\\w*|read|dekh\\s*ke|dekhke|analy[sz]e|samjh\\w*)\\b|\\b(padh\\w*|read|dekh\\s*ke|analy[sz]e)\\b.*\\b(images?|photos?|pics?|pictures?|tasveer\\w*|diagram)\\b");

    private static final Pattern STEPS = Pattern.compile("\\b(steps?|step\\s+by\\s+step|stepwise|kaise|kese|kaisey|kase|how\\s+to|how\\s+do\\s+i|how\\s+can\\s+i|process|procedure|tarika|tareeka|tarike|guide|setup|set\\s+up|install\\w*|banwa\\w*|banaye\\w*|banayein|nikalna|nikale\\w*)\\b");
    private static final Pattern PROCEDURE = Pattern.compile("\\b(passport|aadhaar|aadhar|adhar|pan\\s+card|pan|voter\\s+id|driving\\s+licen[cs]e|licen[cs]e|learning\\s+licen[cs]e|registration|register|apply|application|form|portal|visa|refund|certificate|scholarship|rto|gst|itr|income\\s+tax|tax\\s+return|epf|pf|pension|ration\\s+card|birth\\s+certificate|caste\\s+certificate|domicile|fir|challan|e-?challan|fastag|ticket\\s+book\\w*|tatkal|irctc|download|renew\\w*|status\\s+check|track\\w*|cancel\\w*|claim|kyc|bill\\s+(pay\\w*|bhar\\w*)|bharte|bharna|bhare|upi\\s+limit|bank\\s+account(?:\\s+open\\w*)?|loan\\s+apply|passport\\s+seva|digilocker|umang)\\b");
    private static final Pattern COMPARE = Pattern.compile("\\b(vs|v/s|versus|compare|comparison|comparing|farq|fark|difference|differences|tulna|behtar|better|konsa\\s+(accha|achha|best|behtar|lena)|kaun\\s+sa\\s+(accha|achha|best|behtar)|which\\s+is\\s+better|kya\\s+accha|ya\\s+phir)\\b");
    private static final Pattern PRODUCT = Pattern.compile("\\b(phone|mobile|smartphone|laptop|notebook|car|bike|scooter|scooty|tv|television|ac|fridge|refrigerator|washing\\s+machine|watch|smartwatch|earbuds|earphones|headphones|tablet|ipad|camera|plan|recharge|credit\\s+card|insurance|loan|scheme|course|college|hotel|flight|airline|trimmer|geyser|cooler|inverter|printer|router|monitor|processor|gpu|ssd|iphone|galaxy|redmi|oneplus|pixel|realme|vivo|oppo|poco|nothing|macbook|thinkpad|bullet|activa|splendor|creta|nexon|swift|fortuner)\\b");

    private static final Pattern PRICE = Pattern.compile("\\b(price|prices|pricing|rate|rates|kimat|keemat|qimat|daam|dam|cost|costs|kitne\\s+(ka|ki|ke|me|mein|rupaye|rupay|rs)|kitna\\s+(hai|hota|padega|lagega|lagta|milega)|mehnga|mehenga|sasta|sastha|emi|fees?|fee\\s+structure|salary)\\b|₹|\\brs\\.?\\s*\\d|\\brupee\\w*|\\brupay\\w*");
    private static final Pattern PRICE_ENTITY = Pattern.compile("\\b(kitne|kitna|kitni|price|rate|cost|daam|kimat|keemat)\\b");
    private static final Pattern UNDER_BUDGET = Pattern.compile("\\b(under|below|andar|tak|niche|within|budget)\\b.*\\b\\d{4,6}\\b|\\b\\d{4,6}\\s*(ke\\s+)?(andar|tak|niche|me|mein|ke\\s+under|under)\\b");
    private static final Pattern WEATHER = Pattern.compile("\\b(weather|mausam|mosam|barish|baarish|rain|temperature|temp|forecast|aqi|air\\s+quality|garmi|sardi|thand|humidity|heatwave|cyclone|toofan)\\b");
    private static final Pattern SPORT = Pattern.compile("\\b(score|scores|live\\s+score|ipl|t20|odi|test\\s+match|world\\s+cup|points\\s+table|kaun\\s+jeet\\w*|who\\s+won|jeeta|jeet\\s+gaya|haar\\s+gaya|fixture|fixtures|playing\\s+11|playing\\s+xi|squad|wickets?|match\\s+(kab|kitne|kaun|today|aaj|schedule)|cricket\\s+(score|match|news)|fifa|premier\\s+league|la\\s+liga|nba|nfl|olympics?|asia\\s+cup)\\b");
    private static final Pattern NEWS = Pattern.compile("\\b(news|khabar\\w*|khabren|samachar|headlines?|breaking|trending|viral|taaza|taza)\\b");
    private static final Pattern MONEY = Pattern.compile("\\b(stocks?|share\\s+price|sensex|nifty|bitcoin|btc|ethereum|crypto|gold\\s+(rate|price)|silver\\s+(rate|price)|sone\\s+(ka|ke)|chandi\\s+(ka|ke)|petrol|diesel|cng|lpg|dollar|usd|euro|exchange\\s+rate|forex|interest\\s+rate|fd\\s+rate|repo\\s+rate|gst\\s+rate|mutual\\s+fund\\s+nav|nav\\b|ipo|gmp)\\b");
    private static final Pattern RELEASE = Pattern.compile("\\b(release\\s+date|launch\\s+date|kab\\s+(aayega|aayegi|aaega|launch|release|milega|milegi|khulega|start)|when\\s+(is|will).*\\b(release|launch|come|start)\\w*|available\\s+kab|out\\s+now|coming\\s+soon|trailer)\\b");
    private static final Pattern TIME_WORD = Pattern.compile("\\b(aaj|aj|today|abhi|abhi\\s+ka|right\\s+now|current|currently|latest|newest|recent|recently|is\\s+hafte|this\\s+week|is\\s+saal|this\\s+year|is\\s+mahine|this\\s+month|kal\\s+ka|tomorrow|yesterday|live|ab\\s+tak|so\\s+far|nayi|naya|naye)\\b");
    private static final Pattern LATEST_WORD = Pattern.compile("\\b(latest|newest|current|naya|nayi|naye|abhi\\s+ka|recent)\\b");
    private static final Pattern VERSION = Pattern.compile("\\b(version|versions|update|updates|release|released|changelog|patch|upgrade|naya\\s+kya)\\b");
    private static final Pattern ROLE = Pattern.compile("\\b(president|prime\\s+minister|pm|cm|chief\\s+minister|ceo|captain|champion|winner|minister|governor|chairman|coach|mla|mp|speaker|chief\\s+justice|cji|ambassador|head\\s+of|owner\\s+of|richest|highest|largest|fastest|record)\\b");

    private static final Pattern FACT_Q = Pattern.compile("\\b(who|when|where|kaun|kon|kab|kahan|kaha|kitne|kitni|how\\s+many|how\\s+much|population|capital|founder|owner|ceo|height|age|born|birthday|net\\s+worth|address|phone\\s+number|contact\\s+number|helpline|customer\\s+care|toll\\s+free|timings?|opening\\s+hours|open\\s+kab|band\\s+kab|closing\\s+time|distance|kitni\\s+door|kitna\\s+door|ticket\\s+price|entry\\s+fee)\\b");
    private static final Pattern LOCAL_INFO = Pattern.compile("\\b(address|phone\\s+number|contact\\s+number|helpline|customer\\s+care|toll\\s+free|timings?|opening\\s+hours|open\\s+kab|band\\s+kab|closing\\s+time|near\\s+me|mere\\s+paas|nearby|ke\\s+paas|nazdeeki|nearest)\\b");

    private static final Pattern CODE_WRITE = Pattern.compile("\\b(code|program|script|function|query|regex|formula|algorithm|class|method|snippet|app|game)\\b.*\\b(likho|likh|likhna|write|banao|bana|generate|create|fix|debug|error|dikhao|de\\s+do|do)\\b|\\b(likho|likh|write|banao|bana|generate|create|fix|debug)\\b.*\\b(code|program|script|function|query|regex|formula|algorithm|class|method|snippet)\\b");
    private static final Pattern CODE_RAW = Pattern.compile("```|\\bdef\\s+\\w+\\(|\\bpublic\\s+static\\b|^\\s*import\\s+\\w+|#include|\\bselect\\s+.+\\s+from\\b|\\w+\\(.*\\)\\s*\\{");
    private static final Pattern WRITING = Pattern.compile("\\b(poem|kavita|shayari|joke|jokes|chutkula|story|kahani|essay|letter|email|application|paragraph|speech|slogan|caption|bio|resume|cv|cover\\s+letter|translate|anuvad|rewrite|paraphrase|summari[sz]e|grammar|spelling)\\b.*\\b(likho|likh|write|banao|bana|suna|sunao|do|de|karo|kar)\\b|\\b(translate|anuvad|rewrite|paraphrase|summari[sz]e)\\b");
    private static final Pattern MATH = Pattern.compile("\\d+\\s*[+\\-*/x×÷^%]\\s*\\d+|\\b(calculate|calculation|hisab|hisaab|solve|equation|integral|derivative|square\\s+root|percentage\\s+nikal|lcm|hcf|gcd|factorial|simplify)\\b");
    private static final Pattern TEACH = Pattern.compile("\\b(sikhao|sikha\\s+do|sikhado|samjhao|samjha\\s+do|explain|teach|padhao|tutorial|seekh\\w*|learn\\w*|study\\w*|concept|definition|meaning|matlab|arth|kya\\s+hota\\s+hai|kya\\s+hoti\\s+hai|kya\\s+hai|what\\s+is|what\\s+are|kyu|kyun|kyon|why|how\\s+does|kaise\\s+kaam)\\b");
    private static final Pattern SELF = Pattern.compile("\\b(tum\\s+kaun|aap\\s+kaun|who\\s+are\\s+you|tumhara\\s+naam|your\\s+name|mera\\s+naam|my\\s+name|yaad\\s+rakh\\w*|remember\\s+(this|that|me)|tum\\s+kya\\s+kar|what\\s+can\\s+you|kya\\s+kar\\s+sakte|kaise\\s+ho|how\\s+are\\s+you)\\b");
    private static final Set<String> SMALLTALK = new HashSet<String>(Arrays.asList(
            "hi", "hello", "hey", "hii", "hiii", "heyy", "namaste", "namaskar", "thanks", "thank", "thankyou", "shukriya", "dhanyavad", "ok", "okay", "okk", "k",
            "haan", "han", "ha", "hmm", "hmmm", "nahi", "nahin", "nhi", "no", "yes", "yeah", "acha", "accha", "achha", "theek", "thik", "bye", "goodbye",
            "good", "morning", "night", "evening", "afternoon", "gm", "gn", "wah", "wow", "nice", "great", "cool", "sahi", "badhiya", "lol", "haha", "hehe", "sure", "continue", "aur", "next", "you", "too", "welcome", "please", "plz", "karo", "kar", "do", "bhai", "yaar", "bro", "sir", "ji"));

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList((
            "ka ki ke ko se me mein mai main par pe pr per hai hain ho hota hoti hote tha the thi kya kaun kon kaise kese kaisa kaisi kab kahan kaha kyun kyu kyon kitna kitni kitne "
          + "batao bata bataiye bataye btao bataao dikhao dikha dikhaye dikhana dikhai dijiye do de dena karo kar kr kro kijiye kije dekho dekh dekhna dhundo dhundh dhoondo khojo "
          + "search serch sarch google gogle net internet online web link links url urls photo photos image images pic pics picture pictures tasveer tasvir foto "
          + "please plz pls bhai yaar bro sir ji aur or and bhi sirf bas mujhe mereko muje mera meri mere hume humein ek one iss is us uss ye yeh wo woh the a an of in on at to for with about "
          + "me my tell show give find get can you could would i want need chahiye chahie padh padho padhke padhna mat nahi nhi nahin lo le lena sakte sakta sakti skte hoga hogi honge "
          + "steps step kaisey karna karte krte krna kare karein tarika tareeka guide process procedure wala wali wale vala vali vale uska uski uske iska iski iske unka unki unke unhe use isse usse "
          + "banaye banayein banate banana bana banwa banwana nikale nikalna dikhta dikhti dikhte dikhne lagta lagti lagte ise isey usey by who what when where which how why are was were does did will should abhi aaj today now tak kuch sab kisi koi hi na to toh bhi raha rahi rahe gaya gayi gaye jab tab agar lekin par magar phir fir yahan wahan yaha waha idhar udhar is") .split(" ")));
    private static final Set<String> PRONOUN = new HashSet<String>(Arrays.asList(
            "ise", "uski", "uska", "uske", "iski", "iska", "iske", "unki", "unka", "unke", "isko", "usko", "wahi", "yahi", "usse", "isse", "it", "its", "this", "that", "them", "those", "these", "ye", "yeh", "wo", "woh", "uss", "iss", "is", "us"));

    // ------------------------------------------------------------------ entry point

    public static SearchPlan plan(String rawText, PlanContext ctx) {
        String raw = rawText == null ? "" : rawText.trim();
        if (raw.isEmpty()) return SearchPlan.none("empty");
        if (ctx == null) ctx = PlanContext.auto();
        // Intent words are parsed from user text, not URL paths/query strings. A URL like /guide must not become a "steps" intent.
        String n = norm(URL.matcher(raw).replaceAll(" "));
        String p = " " + n + " ";

        List<String> urls = new ArrayList<String>();
        Matcher um = URL.matcher(raw);
        while (um.find()) urls.add(trimUrl(um.group()));

        boolean noImg = NO_IMG.matcher(p).find();
        boolean noLink = NO_LINK.matcher(p).find();
        boolean readOnly = READ_ONLY.matcher(p).find();
        // Image-specific "mat dikhao" is a display constraint, not whole-answer read-only.
        // A generic "mat dikhao" remains read-only only when no image noun is involved.
        if (!readOnly && !noImg && hasAny(p, " mat dikhao ", " nahi dikhao ", " nhi dikhao ", " not show ")) readOnly = true;
        // "photo mat dikhao, bas price batao" means text-only output, preserving the existing read-only contract.
        if (!readOnly && noImg && hasAny(p, " bas ", " sirf ", " only ", " just ")) readOnly = true;
        boolean explicit = EXPLICIT_SEARCH.matcher(p).find() && !NOT_A_SEARCH_REQUEST.matcher(p).find();

        boolean imageNoun = IMAGE_NOUN.matcher(p).find();
        boolean looksLike = LOOKS_LIKE.matcher(p).find();
        boolean steps = STEPS.matcher(p).find();
        boolean showWord = SHOW_WORD.matcher(p).find();
        boolean makeImage = MAKE_IMAGE.matcher(p).find();
        boolean attachedRef = ctx.hasAttachments || THIS_ATTACHED.matcher(p).find();
        boolean imageIntent = (imageNoun || looksLike || (showWord && !steps && !LINK_WORD.matcher(p).find() && !readOnly))
                && !makeImage;
        // "photo" only as a noun the user wants SHOWN: not "photo kaise kheenchu" (how to take a photo) - that is steps
        if (imageNoun && steps && !showWord && !looksLike && !hasAny(p, " dikha", " show", " dekh", " dikhao", " dikhai")) imageIntent = false;
        if (attachedRef) imageIntent = false;
        boolean readImages = READ_IMAGE.matcher(p).find() && !attachedRef;
        boolean wantShowImages = imageIntent && !noImg && !readOnly && (!readImages || showWord);
        boolean linkIntent = LINK_WORD.matcher(p).find();
        boolean wantsLink = linkIntent && !noLink && !readOnly;
        boolean compare = COMPARE.matcher(p).find();
        boolean procedure = PROCEDURE.matcher(p).find();
        boolean product = PRODUCT.matcher(p).find();

        boolean price = PRICE.matcher(p).find() || UNDER_BUDGET.matcher(p).find();
        boolean weather = WEATHER.matcher(p).find();
        boolean sport = SPORT.matcher(p).find();
        boolean news = NEWS.matcher(p).find();
        boolean money = MONEY.matcher(p).find();
        boolean release = RELEASE.matcher(p).find();
        boolean timeWord = TIME_WORD.matcher(p).find();
        boolean role = ROLE.matcher(p).find();
        boolean versionFresh = timeWord && VERSION.matcher(p).find();
        boolean yearMention = false;
        Matcher ym = YEAR.matcher(p);
        while (ym.find()) { if (Integer.parseInt(ym.group(1)) >= ctx.year - 1) yearMention = true; }
        boolean local = LOCAL_INFO.matcher(p).find();
        boolean fact = FACT_Q.matcher(p).find() && (hasProperNoun(raw) || local);

        boolean freshTopic = weather || sport || news || money || release || (price && (product || timeWord || money || PRICE_ENTITY.matcher(p).find()))
                || versionFresh || (timeWord && (role || fact || price || product || procedure || hasProperNoun(raw)))
                || (yearMention && (fact || role || price || news || release))
                || (role && fact) || local;

        boolean hardNone = CODE_WRITE.matcher(p).find() || CODE_RAW.matcher(raw.toLowerCase(Locale.ROOT)).find() || WRITING.matcher(p).find() || MATH.matcher(p).find() || SELF.matcher(p).find()
                || isSmallTalk(n);

        // ---------------------------------------------------------------- decide
        SearchPlan.Kind kind = SearchPlan.Kind.CHAT;
        String reason = "offline answer";
        boolean search = false;

        if (ctx.mode == WebMode.OFF) {
            boolean wanted = explicit || wantsLink || wantShowImages || !urls.isEmpty();
            return finish(false, SearchPlan.Kind.CHAT, raw, n, ctx, urls, wanted ? "web search is OFF in settings" : "off", wanted,
                    steps, compare, readOnly, wantShowImages, readImages, wantsLink, linkIntent, noLink, noImg, price, news, weather, sport, timeWord, latestWord(p));
        }

        if (!urls.isEmpty()) { search = true; kind = SearchPlan.Kind.URL; reason = "user pasted a link"; }
        else if (explicit) { search = true; kind = SearchPlan.Kind.EXPLICIT; reason = "user asked to search the net"; }
        else if (wantShowImages) { search = true; kind = SearchPlan.Kind.IMAGE; reason = "user wants to see pictures"; }
        else if (readImages) { search = true; kind = SearchPlan.Kind.IMAGE; reason = "user wants to read pictures"; }
        else if (wantsLink) { search = true; kind = SearchPlan.Kind.LINK; reason = "user wants a link"; }
        else if (attachedRef && !freshTopic) { search = false; reason = "question is about the attached file"; }
        else if (hardNone) { search = false; reason = "greeting / code / maths / writing"; }
        else if (freshTopic) { search = true; kind = SearchPlan.Kind.FRESH; reason = "changes over time (price / score / news / weather ...)"; }
        else if (steps && procedure) { search = true; kind = SearchPlan.Kind.PROCEDURE; reason = "official procedure"; }
        else if (compare && !TEACH.matcher(p).find()) { search = true; kind = SearchPlan.Kind.COMPARE; reason = "comparing subjects"; }
        else if (fact) { search = true; kind = SearchPlan.Kind.FACT; reason = "fact about a named thing"; }
        else if (ctx.mode == WebMode.ALWAYS && !TEACH.matcher(p).find() && n.split(" ").length >= 2) { search = true; kind = SearchPlan.Kind.ALWAYS; reason = "web mode is ALWAYS"; }
        else if (ctx.mode == WebMode.ALWAYS && n.split(" ").length >= 3) { search = true; kind = SearchPlan.Kind.ALWAYS; reason = "web mode is ALWAYS"; }

        // "uski photo dikhao" right after a chat answer: searching is needed only when a follow-up wants pictures / links
        return finish(search, kind, raw, n, ctx, urls, reason, explicit, steps, compare, readOnly, wantShowImages, readImages, wantsLink,
                linkIntent, noLink, noImg, price, news, weather, sport, timeWord, latestWord(p));
    }

    private static boolean latestWord(String p) { return LATEST_WORD.matcher(p).find(); }

    // ------------------------------------------------------------------ building the plan

    private static SearchPlan finish(boolean search, SearchPlan.Kind kind, String raw, String n, PlanContext ctx, List<String> urls,
                                     String reason, boolean explicit, boolean steps, boolean compare, boolean readOnly,
                                     boolean showImages, boolean readImages, boolean wantsLink, boolean linkIntent, boolean noLink,
                                     boolean noImg, boolean price, boolean news, boolean weather, boolean sport, boolean timeWord,
                                     boolean latest) {
        SearchPlan.Builder b = new SearchPlan.Builder();
        b.search = search; b.kind = kind; b.reason = reason; b.explicit = explicit;
        b.urls.addAll(urls);
        b.question = URL.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (!search) return b.build();

        b.steps = steps; b.compare = compare;
        b.readOnly = readOnly;
        b.showImages = showImages && !readOnly && !noImg;
        // "photo mat dikhana" blocks rendering only; explicit image-reading must still reach vision.
        b.readImages = readImages;

        // links: asked -> one or many; procedures hand out the right official page by themselves; never when the user said no
        SearchPlan.Links links = SearchPlan.Links.NONE;
        if (wantsLink) links = MANY_LINKS.matcher(" " + n + " ").find() ? SearchPlan.Links.MANY : SearchPlan.Links.ONE;
        else if (kind == SearchPlan.Kind.PROCEDURE && !noLink && !readOnly) links = SearchPlan.Links.ONE;
        if (noLink || readOnly) links = SearchPlan.Links.NONE;
        b.links = links;

        if (news) { b.topic = "news"; b.timeRange = hasAny(" " + n + " ", " aaj ", " today ", " abhi ", " breaking ", " live ") ? "day" : "week"; }
        else if (weather || sport) b.timeRange = "day";
        else if (price && timeWord) b.timeRange = "month";

        b.query = buildQuery(raw, n, ctx, b, price, latest);
        if (b.query.isEmpty()) return SearchPlan.none("nothing to look up");
        return b.build();
    }

    // ------------------------------------------------------------------ query

    static String buildQuery(String raw, String n, PlanContext ctx, SearchPlan.Builder b, boolean price, boolean latest) {
        String noUrls = URL.matcher(raw).replaceAll(" ");
        String nn = norm(noUrls);
        List<String> tokens = new ArrayList<String>();
        boolean pronounRef = false;
        for (String t : nn.split(" ")) {
            if (t.isEmpty()) continue;
            if (PRONOUN.contains(t) && !t.equals("is") && !t.equals("this")) pronounRef = true;
            if (STOP.contains(t)) continue;
            if (t.length() == 1 && !Character.isDigit(t.charAt(0))) continue;
            tokens.add(t);
        }
        boolean needsBase = tokens.isEmpty() || (pronounRef && tokens.size() <= 2) || (tokens.size() <= 1 && !b.steps);
        String base = "";
        if (needsBase) {
            base = !ctx.prevQuery.isEmpty() ? ctx.prevQuery : cleanOnly(ctx.prevUserText);
        }
        String core;
        if (b.compare) core = compareCore(tokens);
        else core = join(tokens);
        if (!base.isEmpty() && needsBase) core = (base + " " + core).trim();
        if (b.urls.size() > 0 && core.isEmpty()) core = hostWords(b.urls.get(0));
        if (core.isEmpty()) return "";

        StringBuilder q = new StringBuilder(core);
        String pc = " " + core + " ";
        if (b.steps && !hasAny(pc, " how to ", " steps ")) q.insert(0, "how to ");
        if (b.steps && !hasAny(pc, " step by step ")) q.append(" step by step");
        if (b.links == SearchPlan.Links.ONE && hasAny(" " + n + " ", " official ", " website ", " site ", " portal ") && !hasAny(pc, " official ")) q.append(" official website");
        if (price && !hasAny(pc, " price ", " rate ", " cost ", " fees ", " fee ", " emi ", " salary ") && !hasAny(pc, " gst ")) q.append(" price");
        if ((price || hasAny(pc, " petrol ", " diesel ", " gold ", " silver ", " gst ", " launch ")) && isHinglish(n) && !hasAny(pc, " india ", " usa ", " uk ")) q.append(" India");
        if (latest && !YEAR.matcher(pc).find() && !b.steps && !b.compare) q.append(' ').append(ctx.year);
        String out = q.toString().trim().replaceAll("\\s+", " ");
        if (out.length() > 180) out = out.substring(0, 180).trim();
        return out;
    }

    private static String compareCore(List<String> tokens) {
        // "iphone 16 vs samsung s25" -> keeps both sides around the separator; other compare words are dropped
        List<String> kept = new ArrayList<String>();
        for (String t : tokens) {
            if (t.equals("ya") || t.equals("phir") || t.equals("versus") || t.equals("v/s")) { kept.add("vs"); continue; }
            if (t.equals("compare") || t.equals("comparison") || t.equals("comparing") || t.equals("farq") || t.equals("fark") || t.equals("difference")
                    || t.equals("differences") || t.equals("tulna") || t.equals("behtar") || t.equals("better") || t.equals("konsa") || t.equals("accha") || t.equals("achha") || t.equals("best") || t.equals("lena") || t.equals("sa")) continue;
            kept.add(t);
        }
        String core = join(kept);
        if (!core.contains(" vs ")) core = core + " comparison";
        else core = core + " comparison";
        return core.trim();
    }

    private static String cleanOnly(String text) {
        if (text == null || text.isEmpty()) return "";
        List<String> toks = new ArrayList<String>();
        for (String t : norm(URL.matcher(text).replaceAll(" ")).split(" ")) {
            if (t.isEmpty() || STOP.contains(t) || (t.length() == 1 && !Character.isDigit(t.charAt(0)))) continue;
            toks.add(t);
        }
        return join(toks);
    }

    // ------------------------------------------------------------------ helpers

    static String norm(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '\'' || c == '\u2019') continue;
            boolean numberDot = c == '.' && i > 0 && i + 1 < lower.length() && Character.isDigit(lower.charAt(i - 1)) && Character.isDigit(lower.charAt(i + 1));
            if (Character.isLetterOrDigit(c) || c == '₹' || c == '+' || c == '*' || c == '/' || c == '^' || c == '%' || c == '×' || c == '÷' || numberDot) sb.append(c);
            else sb.append(' ');
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static boolean hasAny(String padded, String... needles) {
        for (String s : needles) if (padded.contains(s)) return true;
        return false;
    }

    private static String join(List<String> t) {
        StringBuilder sb = new StringBuilder();
        for (String s : t) { if (sb.length() > 0) sb.append(' '); sb.append(s); }
        return sb.toString();
    }

    private static boolean isSmallTalk(String n) {
        String[] t = n.split(" ");
        if (t.length > 4) return false;
        for (String w : t) if (!SMALLTALK.contains(w)) return false;
        return true;
    }

    /** A capitalised word that is not the first word: "Narendra Modi", "Bhopal", "iPhone" (lower+upper) count. */
    static boolean hasProperNoun(String raw) {
        String[] w = raw.split("[\\s,.;:!?()\"]+");
        for (int i = 0; i < w.length; i++) {
            String s = w[i];
            if (s.length() < 3) continue;
            char c = s.charAt(0);
            if (i > 0 && Character.isUpperCase(c) && !s.equals(s.toUpperCase(Locale.ROOT))) return true;
            if (i > 0 && s.equals(s.toUpperCase(Locale.ROOT)) && s.length() >= 3 && Character.isLetter(c)) return true;   // IRCTC, ISRO
            if (i == 0 && s.length() >= 4 && Character.isUpperCase(c) && w.length > 2 && !STOP.contains(s.toLowerCase(Locale.ROOT)) && !Arrays.asList("who", "when", "where", "what", "how", "which", "tell", "show", "give", "kya", "kaun", "kab", "kahan").contains(s.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean hasDigitModel(String p) { return Pattern.compile("\\b[a-z]*\\d+[a-z]*\\b").matcher(p).find(); }

    private static boolean isHinglish(String n) {
        for (String t : n.split(" ")) if (Arrays.asList("hai", "ka", "ki", "ke", "kya", "kitna", "kitne", "kitni", "batao", "bata", "kaise", "mein", "me", "aaj", "abhi", "kab", "kahan", "rupaye", "daam", "kimat", "hain", "chahiye", "wala", "mera", "mujhe").contains(t)) return true;
        return false;
    }

    private static String trimUrl(String u) {
        String s = u;
        while (!s.isEmpty() && ".,;:!?)]}'\"".indexOf(s.charAt(s.length() - 1)) >= 0) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String hostWords(String url) {
        String h = UrlTools.host(url);
        return h.replaceFirst("^www\\.", "").replaceAll("[.\\-]", " ").trim();
    }
}
