package tests;

import com.neonhud.app.core.memory.*;

import java.util.*;

/**
 * 1000+ message conversation with ground truth: stays on topic, follow-ups, topic switches, explicit returns,
 * exact repeats and paraphrased repeats, coding questions. Reports accuracy per behaviour and lists the misses
 * (= the weaknesses of the rule-based detector).
 */
final class StressTest {

    static final class Topic {
        final String label, hint;
        final List<String[]> standalone = new ArrayList<String[]>();   // {question, paraphrase1, paraphrase2}
        final List<String> followups = new ArrayList<String>();
        Topic(String label, String hint) { this.label = label; this.hint = hint; }
        Topic q(String... v) { standalone.add(v); return this; }
        Topic f(String... v) { followups.addAll(Arrays.asList(v)); return this; }
        /** t1 = base question, t2 = same-language paraphrase, t3 = English; each contains %s. */
        Topic gen(String t1, String t2, String t3, String... slots) {
            for (String x : slots) standalone.add(new String[]{String.format(t1, x), String.format(t2, x), String.format(t3, x)});
            return this;
        }
    }

    static List<Topic> topics() {
        List<Topic> t = new ArrayList<Topic>();
        t.add(new Topic("electrical", "AC wiring")
            .q("AC ke liye 20A MCB kaise lagta hai?", "AC ke liye 20A MCB ki wiring kaise karte hain?", "How to install a 20A MCB for an AC?")
            .q("Geyser ke liye kitne amp ka MCB chahiye?", "Geyser ka MCB kitne amp ka hona chahiye?", "What amp MCB do I need for a geyser?")
            .q("Earthing wire kitni moti honi chahiye?", "Earthing wire ki thickness kitni honi chahiye?", "How thick should the earthing wire be?")
            .q("Inverter ka connection kaise karte hain ghar mein?", "Ghar mein inverter connection kaise karein?", "How do I connect an inverter at home?")
            .q("Switchboard mein socket aur switch ki wiring kaise hoti hai?", "Switchboard mein socket switch wiring kaise karein?", "How is wiring done for a socket and switch on a switchboard?")
            .q("MCB baar baar trip kyun ho raha hai?", "MCB bar bar trip kyu hota hai?", "Why does my MCB keep tripping?")
            .q("Fan regulator ka taar kaise jodte hain?", "Fan regulator ki wiring kaise karein?", "How to wire a fan regulator?")
            .q("Phase aur neutral wire ki pehchan kaise karein?", "Phase neutral wire kaise pehchane?", "How to identify phase and neutral wires?")
            .q("3 phase motor ka starter kaise lagta hai?", "3 phase motor starter connection kaise karein?", "How is a 3 phase motor starter connected?")
            .q("Ghar ke liye 1.5mm aur 2.5mm wire mein kya farq hai?", "1.5mm vs 2.5mm wire ka farq batao", "Difference between 1.5mm and 2.5mm wire?")
            .gen("%s ke liye kitne amp ka MCB chahiye?", "%s ke liye MCB kitne amp ka hona chahiye?", "What amp MCB does a %s need?",
                 "Washing machine", "Microwave", "Water pump", "Refrigerator", "Induction cooktop", "Air cooler", "Dishwasher", "Heater", "Tube well", "Lift motor")
            .f("Isme 2.5mm wire chalega?", "Isme ELCB bhi lagana padega kya?", "Iska connection diagram bata do", "Isme copper wire better hai ya aluminium?", "Isme kitna load aayega?")
            .f("Aur earthing kaise karein?", "Is wiring mein kaunsa cable use karein?"));
        t.add(new Topic("train", "train")
            .q("Mumbai mein train ka time kya hai?", "Mumbai train ka timetable kya hai?", "What is the train timing in Mumbai?")
            .q("Tatkal ticket kab khulta hai IRCTC par?", "IRCTC par tatkal booking kab start hoti hai?", "When does tatkal booking open on IRCTC?")
            .q("PNR status kaise check karein?", "PNR status check karne ka tarika batao", "How do I check PNR status?")
            .q("Rajdhani express mein kitna kiraya lagta hai Delhi se Mumbai?", "Delhi se Mumbai Rajdhani express ka kiraya kitna hai?", "What is the fare of Rajdhani express from Delhi to Mumbai?")
            .q("Waitlist ticket confirm hone ke chance kaise dekhein?", "Waitlist ticket confirm hoga ya nahi kaise pata karein?", "How to check chances of waitlist ticket confirmation?")
            .q("Sleeper coach aur 3AC mein kya difference hai?", "Sleeper vs 3AC train coach ka farq", "Difference between sleeper and 3AC coach?")
            .q("Platform ticket kaise book karte hain railway station ke liye?", "Railway station platform ticket kaise lein?", "How to buy a platform ticket at a railway station?")
            .q("Vande Bharat train ka route aur time kya hai?", "Vande Bharat train ka timetable batao", "What is the route and timing of Vande Bharat train?")
            .q("Train ticket cancel karne par refund kitna milta hai?", "Train ticket cancellation refund kitna hota hai?", "How much refund do I get on train ticket cancellation?")
            .q("Local train ka monthly pass kaise banta hai?", "Local train monthly pass kaise banayein?", "How to get a monthly pass for local train?")
            .gen("%s train ka time kya hai?", "%s train ka timetable kya hai?", "What is the %s train timing?",
                 "Delhi Mumbai", "Mumbai Pune", "Chennai Bangalore", "Kolkata Patna", "Jaipur Delhi", "Lucknow Varanasi", "Hyderabad Vizag", "Bhopal Indore", "Surat Ahmedabad", "Nagpur Raipur")
            .f("Isme berth kaise choose karein?", "Aur waitlist ka kya hoga?", "Iska time kitna hai?", "Isme lower berth milegi kya?", "Isme kitna time lagta hai pahunchne mein?")
            .f("Aur kiraya kitna hai?", "Uski booking kahan se karein?"));
        t.add(new Topic("android", "Android app")
            .q("Android app mein Gradle build error aa raha hai", "Android Gradle build fail ho raha hai, kya karein?", "My Android Gradle build is failing")
            .q("AndroidManifest mein permission kaise add karte hain?", "Android manifest permission add karne ka tarika", "How do I add a permission in AndroidManifest?")
            .q("Android mein ListView scroll smooth kaise karein?", "ListView ka scroll Android mein smooth kaise banayein?", "How to make ListView scrolling smooth on Android?")
            .q("Android keyboard khulne par layout upar kaise push karein?", "Android mein keyboard open hone par layout resize kaise karein?", "How to resize layout when the Android keyboard opens?")
            .q("Foreground service notification Android 14 mein kaise banate hain?", "Android 14 foreground service notification banane ka tarika", "How to create a foreground service notification on Android 14?")
            .q("APK sign karke release build kaise banayein?", "Release APK sign kaise karte hain Android mein?", "How to sign a release APK on Android?")
            .q("Android Activity ka lifecycle kya hota hai?", "Android Activity lifecycle samjhao", "Explain the Android Activity lifecycle")
            .q("SQLite database Android app mein kaise use karein?", "Android app mein SQLite kaise use karte hain?", "How do I use SQLite in an Android app?")
            .q("Status bar ke neeche content kaise rakhein Android mein?", "Android mein status bar ke niche layout kaise rakhein?", "How to keep content below the status bar in Android?")
            .q("Android app background mein band kyun ho jati hai?", "Android app background mein kill kyun hoti hai?", "Why does my Android app get killed in the background?")
            .gen("Android mein %s kaise implement karein?", "Android app mein %s kaise lagayein?", "How do I implement %s in Android?",
                 "dark mode", "push notification", "camera permission", "file picker", "swipe refresh", "bottom navigation", "splash screen", "biometric login", "deep links", "WorkManager")
            .f("Isme Kotlin use karein ya Java?", "Isme kaunsa SDK version chahiye?", "Iska code example do", "Isme emulator mein test kaise karein?", "Aur ye error logcat mein kaise dikhe?")
            .f("Aur Gradle sync kaise karein?", "Is app mein permission kaise maangein?"));
        t.add(new Topic("coding", "Python code")
            .q("Python mein list kaise banate hain?", "Python list banane ka tarika batao", "How do I create a list in Python?")
            .q("Python mein dictionary kaise banate hain?", "Python dictionary kaise banayein?", "How to create a dictionary in Python?")
            .q("Recursion kya hota hai programming mein?", "Programming mein recursion kya hai?", "What is recursion in programming?")
            .q("Bubble sort algorithm ka code likho Python mein", "Python mein bubble sort ka code do", "Write bubble sort in Python")
            .q("Java mein class aur object ka difference kya hai?", "Java class vs object ka farq batao", "What is the difference between a class and an object in Java?")
            .q("JavaScript mein async await kaise kaam karta hai?", "JavaScript async await samjhao", "How does async await work in JavaScript?")
            .q("SQL mein JOIN kaise use karte hain?", "SQL JOIN ka use kaise karein?", "How do I use JOIN in SQL?")
            .q("Git mein branch kaise banate hain aur merge kaise karte hain?", "Git branch banane aur merge karne ka tarika", "How do I create and merge a branch in Git?")
            .q("C++ mein pointer kya hota hai?", "C++ pointer kya hai samjhao", "What is a pointer in C++?")
            .q("Python mein exception handling kaise karte hain try except se?", "Python try except exception handling kaise karein?", "How to handle exceptions with try except in Python?")
            .q("Binary search ka algorithm kya hai?", "Binary search algorithm samjhao", "Explain the binary search algorithm")
            .gen("Python mein %s kaise banate hain?", "Python mein %s kaise banayein?", "How do I create a %s in Python?",
                 "set", "tuple", "class", "decorator", "generator", "lambda function", "module", "virtual environment", "queue", "linked list")
            .gen("%s algorithm ka code Java mein likho", "Java mein %s algorithm ka code do", "Write %s algorithm code in Java",
                 "merge sort", "quick sort", "insertion sort", "selection sort", "heap sort", "DFS", "BFS", "dijkstra")
            .f("Isme time complexity kya hogi?", "Iska code example do", "Isme bug kahan hai?", "Isko optimize kaise karein?", "Isme error aa raha hai, kya karein?")
            .f("Aur ek example do", "Is function ko test kaise karein?"));
        t.add(new Topic("cooking", "recipe")
            .q("Paneer butter masala ki recipe batao", "Paneer butter masala kaise banate hain?", "How to make paneer butter masala?")
            .q("Roti fuli fuli kaise banti hai?", "Roti phulane ka tarika kya hai?", "How to make puffed rotis?")
            .q("Biryani mein kitna chawal aur masala dalein?", "Biryani ke liye chawal aur masala kitna lagta hai?", "How much rice and masala for biryani?")
            .q("Dosa ka batter kaise banayein?", "Dosa batter banane ki recipe batao", "How to prepare dosa batter?")
            .q("Dal tadka mein kaunse masale dalte hain?", "Dal tadka ke liye kaunse masale chahiye?", "Which spices go into dal tadka?")
            .q("Aloo paratha ka atta kaise gundhein?", "Aloo paratha ke liye atta kaise goondhte hain?", "How to knead dough for aloo paratha?")
            .q("Cooker mein chawal kitni seeti mein pakte hain?", "Cooker mein rice kitni seeti mein banta hai?", "How many whistles for rice in a cooker?")
            .q("Samosa ka filling aur dough kaise banate hain?", "Samosa banane ki recipe batao", "How to make samosa filling and dough?")
            .q("Chai mein adrak aur elaichi kab dalein?", "Chai banate waqt adrak elaichi kab daalni chahiye?", "When to add ginger and cardamom in tea?")
            .q("Pizza base ghar par oven mein kaise banayein?", "Ghar par pizza base kaise banate hain oven mein?", "How to bake a pizza base at home in the oven?")
            .gen("%s ki recipe batao", "%s kaise banate hain?", "How to make %s?",
                 "Rajma chawal", "Chole bhature", "Poha", "Upma", "Kheer", "Gulab jamun", "Pav bhaji", "Dhokla", "Idli sambar", "Kadhi")
            .f("Isme namak kitna dalein?", "Isme kitna time lagega pakne mein?", "Isme ghee ya tel kaunsa better hai?", "Isme kaunsa masala skip kar sakte hain?", "Isko kitne logon ke liye banayein?")
            .f("Aur tadka kaise lagayein?", "Is recipe mein kya replace kar sakte hain?"));
        t.add(new Topic("cricket", "cricket match")
            .q("IPL mein sabse zyada runs kiske hain?", "IPL ke top run scorer kaun hain?", "Who has the most runs in IPL?")
            .q("Cricket mein LBW rule kya hota hai?", "LBW ka rule cricket mein kya hai?", "What is the LBW rule in cricket?")
            .q("T20 world cup ka format kya hai?", "T20 world cup ka format samjhao", "What is the format of the T20 world cup?")
            .q("Virat Kohli ke kitne centuries hain ODI mein?", "Kohli ke ODI centuries kitne hain?", "How many ODI centuries does Virat Kohli have?")
            .q("Duckworth Lewis method kaise kaam karta hai?", "Duckworth Lewis method samjhao cricket mein", "How does the Duckworth Lewis method work?")
            .q("Cricket pitch ki length kitni hoti hai?", "Cricket pitch kitni lambi hoti hai?", "How long is a cricket pitch?")
            .q("Bumrah ki bowling speed kitni hai?", "Bumrah kitni speed se bowling karte hain?", "How fast does Bumrah bowl?")
            .q("Super over ka rule kya hai cricket match mein?", "Cricket match mein super over ka rule batao", "What is the super over rule in a cricket match?")
            .q("Dhoni ne kitne IPL titles jeete hain captain ke taur par?", "Captain Dhoni ke IPL titles kitne hain?", "How many IPL titles did Dhoni win as captain?")
            .q("Test cricket mein kitne over ek din mein hote hain?", "Test match mein ek din mein kitne over hote hain?", "How many overs are bowled per day in a Test match?")
            .gen("%s ke kitne wickets hain IPL mein?", "IPL mein %s ke wickets kitne hain?", "How many IPL wickets does %s have?",
                 "Chahal", "Bumrah", "Rashid Khan", "Bhuvneshwar", "Ashwin", "Jadeja", "Shami", "Narine")
            .f("Isme kaunsa team favourite hai?", "Iska rule kya hai exactly?", "Isme kitne wicket chahiye?", "Isme kaun jeeta tha pichli baar?", "Isme umpire ka decision final hota hai kya?")
            .f("Aur batting order kaisa hota hai?", "Is match ka score kya tha?"));
        t.add(new Topic("health", "bukhar")
            .q("Bukhar mein kya khana chahiye?", "Bukhar hone par kya khayein?", "What should I eat during a fever?")
            .q("Sardi khansi ke liye gharelu ilaaj batao", "Sardi khansi ka gharelu ilaaj kya hai?", "Home remedies for cold and cough?")
            .q("Sir dard ke liye kaunsi dawai leni chahiye?", "Headache mein kaunsi dawai lein?", "Which medicine should I take for a headache?")
            .q("Vitamin D ki kami kaise poori karein?", "Vitamin D deficiency kaise door karein?", "How to fix vitamin D deficiency?")
            .q("Neend na aane par kya karein?", "Neend nahi aati to kya karna chahiye?", "What to do when I can't sleep?")
            .q("Weight loss ke liye diet plan kaisa hona chahiye?", "Weight loss diet plan kaisa hona chahiye?", "What should a weight loss diet plan look like?")
            .q("BP high hone par kya karein?", "High BP mein kya karna chahiye?", "What to do when blood pressure is high?")
            .q("Pet dard aur gas ka ilaaj kya hai?", "Pet mein dard aur gas ka ilaj batao", "How to treat stomach pain and gas?")
            .q("Roz kitna protein lena chahiye gym karne wale ko?", "Gym karne wale ko daily kitna protein chahiye?", "How much protein daily for someone who goes to the gym?")
            .q("Yoga se thakan kaise door hoti hai?", "Yoga thakan kaise kam karta hai?", "How does yoga reduce fatigue?")
            .f("Isme paracetamol le sakte hain kya?", "Isme kitne din tak aaram karein?", "Isse kab tak theek hote hain?", "Isme doctor ko kab dikhayein?", "Isme kya parhez karna chahiye?")
            .f("Aur exercise kab shuru karein?", "Is dawai ka side effect kya hai?"));
        t.add(new Topic("finance", "loan")
            .q("Home loan ka EMI kaise calculate karte hain?", "Home loan EMI calculate karne ka formula kya hai?", "How is home loan EMI calculated?")
            .q("SIP mein kitna invest karna chahiye har mahine?", "Har mahine SIP mein kitna paisa lagayein?", "How much should I invest in SIP every month?")
            .q("ITR file karne ki last date kya hai?", "ITR filing ki last date kab hai?", "What is the last date to file ITR?")
            .q("Fixed deposit aur recurring deposit mein kya farq hai?", "FD vs RD ka farq batao", "Difference between fixed deposit and recurring deposit?")
            .q("Credit card ka interest kitna lagta hai?", "Credit card par byaj kitna hota hai?", "How much interest does a credit card charge?")
            .q("Mutual fund aur share market mein kya difference hai?", "Mutual fund vs share market ka farq", "Difference between mutual funds and the stock market?")
            .q("GST kitne percent lagta hai mobile phone par?", "Mobile phone par GST kitna hota hai?", "What GST is charged on mobile phones?")
            .q("UPI se paisa galat jaye to kya karein?", "UPI galat transfer hone par kya karna chahiye?", "What to do if UPI money goes to a wrong account?")
            .q("PPF account mein kitna byaj milta hai?", "PPF par interest rate kitna hai?", "What interest does a PPF account give?")
            .q("Personal loan lene se pehle kya dekhna chahiye?", "Personal loan lene se pehle kya check karein?", "What to check before taking a personal loan?")
            .gen("%s par GST kitna lagta hai?", "%s par kitna GST hota hai?", "How much GST is charged on %s?",
                 "AC", "Laptop", "Car", "Gold", "Restaurant bill", "Insurance premium", "Bicycle", "Books")
            .f("Isme kitna byaj lagega?", "Isme tax bachega kya?", "Isme kitne saal ka tenure best hai?", "Isme risk kitna hai?", "Isme kitna paisa lagega shuru karne ke liye?")
            .f("Aur EMI kam kaise karein?", "Is scheme ka return kitna hai?"));
        t.add(new Topic("weather", "mausam")
            .q("Aaj mausam kaisa rahega Delhi mein?", "Delhi mein aaj ka mausam kaisa hoga?", "What will the weather be like in Delhi today?")
            .q("Mumbai mein barish kab tak chalegi?", "Mumbai mein monsoon kab tak rahega?", "How long will it rain in Mumbai?")
            .q("Kal shimla ka temperature kitna hoga?", "Shimla mein kal kitni thand hogi?", "What will be Shimla's temperature tomorrow?")
            .q("Kya kal toofan aane wala hai Chennai mein?", "Chennai mein kal cyclone ka khatra hai kya?", "Is a cyclone expected in Chennai tomorrow?")
            .q("Is hafte garmi kitni badhegi Jaipur mein?", "Jaipur mein is hafte kitni garmi padegi?", "How hot will Jaipur get this week?")
            .q("Kohra kab tak rahega North India mein?", "North India mein kohra kab tak chalega?", "How long will the fog last in North India?")
            .q("Kal kolkata mein humidity kitni hogi?", "Kolkata mein kal humidity kitni rahegi?", "How humid will Kolkata be tomorrow?")
            .q("Weekend par Pune ka forecast kya hai?", "Pune ka weekend forecast batao", "What is Pune's weekend forecast?")
            .gen("%s mein aaj mausam kaisa rahega?", "Aaj %s ka mausam kaisa hoga?", "What will the weather be like in %s today?",
                 "Lucknow", "Patna", "Bhopal", "Nagpur", "Surat", "Goa", "Ranchi", "Dehradun", "Chandigarh", "Amritsar")
            .f("Isme umbrella le jana padega kya?", "Isme kal bhi barish hogi?", "Isme kitne baje tak barish rahegi?", "Aur kal ka mausam kaisa rahega?", "Isme thand zyada padegi kya?"));
        t.add(new Topic("ai", "Gemma model")
            .q("Gemma 4 E2B kya hai?", "Gemma 4 E2B ke baare mein batao", "What is Gemma 4 E2B?")
            .q("LLM mein token kya hota hai?", "LLM token ka matlab kya hai?", "What is a token in an LLM?")
            .q("Quantization se model ka size kaise kam hota hai?", "Model quantization size kaise ghatata hai?", "How does quantization reduce model size?")
            .q("Offline AI model phone par kaise chalta hai?", "Phone par offline AI model kaise run hota hai?", "How does an offline AI model run on a phone?")
            .q("Context window kya hoti hai AI model mein?", "AI model ki context window ka matlab batao", "What is a context window in an AI model?")
            .q("Hallucination kya hota hai chatbot mein?", "Chatbot hallucination kya hai?", "What is hallucination in a chatbot?")
            .q("Gemma 4 E4B aur E2B mein kya difference hai?", "Gemma E4B vs E2B ka farq batao", "What is the difference between Gemma 4 E4B and E2B?")
            .q("Fine tuning aur prompt engineering mein kya farq hai?", "Fine tuning vs prompt engineering ka farq", "Difference between fine tuning and prompt engineering?")
            .f("Isme kitni RAM lagti hai?", "Isme GPU use hota hai kya?", "Iska speed kitna hai phone par?", "Isme accuracy kaisi hoti hai?", "Aur isko train kaise karte hain?"));
        t.add(new Topic("education", "exam")
            .q("NEET ka syllabus kya hai physics mein?", "NEET physics ka syllabus batao", "What is the NEET physics syllabus?")
            .q("Newton ke teen laws samjhao", "Newton ke three laws of motion batao", "Explain Newton's three laws")
            .q("JEE Main ka exam pattern kya hai?", "JEE Main exam pattern batao", "What is the JEE Main exam pattern?")
            .q("Photosynthesis kya hota hai biology mein?", "Biology mein photosynthesis kya hai?", "What is photosynthesis in biology?")
            .q("Quadratic equation ka formula kya hai maths mein?", "Maths mein quadratic equation ka formula batao", "What is the quadratic formula?")
            .q("UPSC prelims ke liye kaunsi books padhein?", "UPSC prelims ki books kaunsi best hain?", "Which books for UPSC prelims?")
            .q("Periodic table ke pehle 20 elements batao chemistry mein", "Chemistry mein periodic table ke first 20 elements", "First 20 elements of the periodic table?")
            .q("Board exam ki taiyari kaise karein 30 din mein?", "30 din mein board exam ki preparation kaise karein?", "How to prepare for board exams in 30 days?")
            .f("Isme kitne marks ke questions aate hain?", "Isme kaunsa chapter sabse important hai?", "Iska formula yaad kaise rakhein?", "Isme kitna time lagega padhne mein?", "Aur ek example do")
            .f("Isme mock test kahan se dein?"));
        t.add(new Topic("travel", "hotel")
            .q("Goa mein ghumne ke liye best time kya hai?", "Goa trip ke liye best season kaunsa hai?", "What is the best time to visit Goa?")
            .q("Manali ka 4 din ka itinerary banao", "Manali 4 din ki trip ka plan batao", "Plan a 4 day itinerary for Manali")
            .q("Ladakh jaane ke liye permit kaise lein?", "Ladakh ka permit kaise banta hai?", "How do I get a permit for Ladakh?")
            .q("Kashmir mein hotel kaunse best hain Dal Lake ke paas?", "Dal Lake ke paas best hotel kaunse hain Kashmir mein?", "Best hotels near Dal Lake in Kashmir?")
            .q("Passport banwane ke liye kaunse documents chahiye?", "Passport ke liye documents kaunse lagte hain?", "Which documents are needed for a passport?")
            .q("Thailand ke liye visa kaise milta hai Indians ko?", "Indians ke liye Thailand visa kaise banta hai?", "How do Indians get a Thailand visa?")
            .q("Shimla mein homestay kahan book karein?", "Shimla mein homestay booking kahan se karein?", "Where to book a homestay in Shimla?")
            .f("Isme kitna kharcha aayega?", "Isme hotel kaunsa lein?", "Isme kitne din kaafi hain?", "Aur wahan khane ke liye kya best hai?", "Isme sightseeing kya kya hai?"));
        return t;
    }

    // ---------------------------------------------------------------- run
    enum Kind { FIRST, STANDALONE, FOLLOWUP, SWITCH, RETURN, REPEAT_EXACT, REPEAT_PARA, REPEAT_XLANG }

    static final class Miss { final int idx; final String text; final String why; Miss(int i, String t, String w) { idx = i; text = t; why = w; } }

    static void run() {
        T.section("STRESS: 1,300 messages, 12 topics, switching back and forth, repeats, coding");
        List<Topic> topics = topics();
        Random rnd = new Random(20260930L);
        MemoryTests.Sim sim = new MemoryTests.Sim();

        final int N = 1300;
        String[] labels = new String[N]; Kind[] kinds = new Kind[N]; String[] texts = new String[N];
        QuestionAnalysis[] qas = new QuestionAnalysis[N];
        long[] topicIds = new long[N];
        Map<String, List<String[]>> asked = new HashMap<String, List<String[]>>();   // label -> standalone entries already asked
        Map<String, Set<Integer>> usedStandalone = new HashMap<String, Set<Integer>>();
        Set<String> visited = new LinkedHashSet<String>();
        Map<String, String[]> lastAskedEntry = new HashMap<String, String[]>();

        Topic cur = topics.get(0);
        long tAll = 0, worst = 0;
        for (int i = 0; i < N; i++) {
            Kind kind; String text; Topic target = cur;
            double r = rnd.nextDouble();
            if (i == 0) { kind = Kind.FIRST; text = pickStandalone(cur, usedStandalone, rnd, asked); }
            else if (r < 0.40) { kind = Kind.STANDALONE; text = pickStandalone(cur, usedStandalone, rnd, asked); if (lastPickWasRepeat) kind = Kind.REPEAT_EXACT; }
            else if (r < 0.62) { kind = Kind.FOLLOWUP; text = cur.followups.get(rnd.nextInt(cur.followups.size())); }
            else if (r < 0.78) {
                kind = Kind.SWITCH;
                Topic nt; do { nt = topics.get(rnd.nextInt(topics.size())); } while (nt == cur);
                target = nt;
                text = pickStandalone(nt, usedStandalone, rnd, asked);
                if (lastPickWasRepeat) kind = Kind.REPEAT_EXACT;
            }
            else if (r < 0.88 && visited.size() > 2) {
                kind = Kind.RETURN;
                List<String> v = new ArrayList<String>(visited); v.remove(cur.label);
                Topic nt = byLabel(topics, v.get(rnd.nextInt(v.size())));
                target = nt;
                String[] forms = {"%s wale question par wapas aao.", "Wapas %s wale mein batao.", "%s wale topic pe wapas chalte hain", "Let's go back to the %s question"};
                text = String.format(forms[rnd.nextInt(forms.length)], nt.hint);
            }
            else if (r < 0.94 && !asked.getOrDefault(cur.label, Collections.<String[]>emptyList()).isEmpty()) {
                kind = Kind.REPEAT_EXACT;
                List<String[]> a = asked.get(cur.label);
                text = a.get(rnd.nextInt(a.size()))[0];
            }
            else if (!asked.getOrDefault(cur.label, Collections.<String[]>emptyList()).isEmpty()) {
                kind = Kind.REPEAT_PARA;
                List<String[]> a = asked.get(cur.label);
                String[] e = a.get(rnd.nextInt(a.size()));
                int which = 1 + rnd.nextInt(2);
                kind = which == 1 ? Kind.REPEAT_PARA : Kind.REPEAT_XLANG;
                text = e[which];
            }
            else { kind = Kind.STANDALONE; text = pickStandalone(cur, usedStandalone, rnd, asked); if (lastPickWasRepeat) kind = Kind.REPEAT_EXACT; }

            long t0 = System.nanoTime();
            ConversationBrain.Turn turn = sim.ask(text, "Yeh jawab hai " + i + ". Isme " + target.hint + " ke baare mein detail hai.");
            long dt = System.nanoTime() - t0; tAll += dt; worst = Math.max(worst, dt);
            labels[i] = target.label; kinds[i] = kind; texts[i] = text; qas[i] = turn.analysis; topicIds[i] = turn.topicId;
            cur = target; visited.add(cur.label);
            if (System.getProperty("dump") != null)
                System.out.println("  #" + i + " [" + kind + "/" + target.label + "] topic#" + turn.topicId + " '" + turn.analysis.currentTopic.name + "' same=" + turn.analysis.sameQuestion + "  \"" + text + "\"");
        }

        // map ground-truth label -> most common brain topic id
        Map<String, Map<Long, Integer>> votes = new HashMap<String, Map<Long, Integer>>();
        for (int i = 0; i < N; i++) {
            Map<Long, Integer> m = votes.get(labels[i]); if (m == null) { m = new HashMap<Long, Integer>(); votes.put(labels[i], m); }
            Integer c = m.get(topicIds[i]); m.put(topicIds[i], c == null ? 1 : c + 1);
        }
        Map<String, Long> canon = new HashMap<String, Long>();
        for (Map.Entry<String, Map<Long, Integer>> e : votes.entrySet()) {
            long best = -1; int bc = -1;
            for (Map.Entry<Long, Integer> v : e.getValue().entrySet()) if (v.getValue() > bc) { bc = v.getValue(); best = v.getKey(); }
            canon.put(e.getKey(), best);
        }
        int[] tot = new int[Kind.values().length], ok = new int[Kind.values().length];
        List<Miss> misses = new ArrayList<Miss>();
        int changedTP = 0, changedFN = 0, changedFP = 0, changedTN = 0;
        int sameTP = 0, sameFN = 0, sameFP = 0, sameTN = 0;
        int ctxNeedTP = 0, ctxNeedFN = 0;
        for (int i = 0; i < N; i++) {
            boolean topicOk = topicIds[i] == canon.get(labels[i]);
            int k = kinds[i].ordinal();
            tot[k]++;
            boolean good = topicOk;
            if (kinds[i] == Kind.REPEAT_EXACT || kinds[i] == Kind.REPEAT_PARA || kinds[i] == Kind.REPEAT_XLANG) good = good && qas[i].sameQuestion;
            if (kinds[i] == Kind.FOLLOWUP) good = good && qas[i].requiresPreviousContext && !qas[i].topicChanged;
            if (kinds[i] == Kind.RETURN) good = good && qas[i].returningToOldTopic && qas[i].requiresPreviousContext;
            if (good) ok[k]++; else misses.add(new Miss(i, texts[i], kinds[i] + ": expected " + labels[i] + (topicOk ? "" : " got topic#" + topicIds[i]) + " " + qas[i]));

            if (i > 0) {
                boolean expChanged = !labels[i].equals(labels[i - 1]);
                boolean gotChanged = qas[i].topicChanged;
                if (expChanged && gotChanged) changedTP++; else if (expChanged) changedFN++; else if (gotChanged) changedFP++; else changedTN++;
            }
            boolean expSame = kinds[i] == Kind.REPEAT_EXACT || kinds[i] == Kind.REPEAT_PARA || kinds[i] == Kind.REPEAT_XLANG;
            boolean gotSame = qas[i].sameQuestion;
            if (expSame && gotSame) sameTP++; else if (expSame) sameFN++; else if (gotSame) sameFP++; else sameTN++;
            if (kinds[i] == Kind.FOLLOWUP) { if (qas[i].requiresPreviousContext) ctxNeedTP++; else ctxNeedFN++; }
        }
        int overallOk = 0; for (int v : ok) overallOk += v;
        System.out.println("\n  behaviour               ok / total     accuracy");
        for (Kind k : Kind.values()) if (tot[k.ordinal()] > 0)
            System.out.printf("  %-20s %5d / %-5d   %5.1f%%%n", k, ok[k.ordinal()], tot[k.ordinal()], 100.0 * ok[k.ordinal()] / tot[k.ordinal()]);
        System.out.printf("  %-20s %5d / %-5d   %5.1f%%%n", "ALL", overallOk, N, 100.0 * overallOk / N);
        System.out.printf("%n  topic-change detection : TP=%d FN=%d FP=%d TN=%d  (recall %.1f%%, precision %.1f%%)%n",
                changedTP, changedFN, changedFP, changedTN, 100.0 * changedTP / Math.max(1, changedTP + changedFN), 100.0 * changedTP / Math.max(1, changedTP + changedFP));
        System.out.printf("  same-question detection: TP=%d FN=%d FP=%d TN=%d  (recall %.1f%%, false-positive rate %.2f%%)%n",
                sameTP, sameFN, sameFP, sameTN, 100.0 * sameTP / Math.max(1, sameTP + sameFN), 100.0 * sameFP / Math.max(1, sameFP + sameTN));
        System.out.printf("  follow-up needs-context: %d/%d%n", ctxNeedTP, ctxNeedTP + ctxNeedFN);
        System.out.printf("  brain topics created   : %d (ground truth: %d)%n", sim.store.topics().size(), topics.size());
        System.out.printf("  avg beginTurn+finish   : %.3f ms, worst %.3f ms%n", tAll / 1e6 / N, worst / 1e6);
        if (System.getProperty("fp") != null) {
            int n = 0;
            System.out.println("\n  same-question FALSE POSITIVES:");
            for (int i = 0; i < N && n < 30; i++) {
                boolean expSame = kinds[i] == Kind.REPEAT_EXACT || kinds[i] == Kind.REPEAT_PARA || kinds[i] == Kind.REPEAT_XLANG;
                if (!expSame && qas[i].sameQuestion) {
                    n++;
                    ConversationMessage m = sim.store.getMessage(qas[i].sameQuestionMessageId);
                    System.out.println("   #" + i + " [" + kinds[i] + "] \"" + texts[i] + "\"  ~  \"" + (m == null ? "?" : m.content) + "\"");
                }
            }
            n = 0;
            System.out.println("\n  RETURN misses:");
            for (int i = 0; i < N && n < 25; i++) if (kinds[i] == Kind.RETURN && !(qas[i].returningToOldTopic && topicIds[i] == canon.get(labels[i]))) {
                n++; System.out.println("   #" + i + " \"" + texts[i] + "\" -> " + qas[i] + " wantTopic#" + canon.get(labels[i]) + " got#" + topicIds[i]);
            }
        }
        int shown = 0;
        System.out.println("\n  first misses:");
        for (Miss m : misses) { if (shown++ >= 40) break; System.out.println("   #" + m.idx + " \"" + m.text + "\"  -> " + m.why); }
        System.out.println("  total misses: " + misses.size());

        // pass/fail thresholds
        T.check(1.0 * overallOk / N >= 0.93, "overall accuracy >= 93% (was " + (100.0 * overallOk / N) + "%)");
        T.check(sameFP <= 3, "same-question false positives <= 3 (was " + sameFP + ")");
        T.check(1.0 * ok[Kind.REPEAT_EXACT.ordinal()] / Math.max(1, tot[Kind.REPEAT_EXACT.ordinal()]) >= 0.97, "exact-repeat detection >= 97%");
        T.check(1.0 * ok[Kind.REPEAT_PARA.ordinal()] / Math.max(1, tot[Kind.REPEAT_PARA.ordinal()]) >= 0.40,
                "same-language paraphrase regression guard >= 40% (known weak spot, see report)");
        // REPEAT_XLANG (English <-> Hinglish translation of the same question) is reported but NOT asserted: a word-level
        // detector cannot do it; it would need an embedding model.
        T.check(1.0 * ok[Kind.RETURN.ordinal()] / Math.max(1, tot[Kind.RETURN.ordinal()]) >= 0.93, "return-to-topic accuracy >= 93%");
        T.check(sim.store.topics().size() <= topics.size() + 6, "topic explosion under control (" + sim.store.topics().size() + ")");
        T.check(tAll / 1e6 / N < 25, "memory layer fast enough for a phone-class budget (host-CPU average < 25 ms)");

        prompts(sim);
    }

    static void prompts(MemoryTests.Sim sim) {
        T.section("prompt size after 1,300 messages");
        ConversationBrain.Turn t = sim.ask("AC ke liye 20A MCB kaise lagta hai?");
        int len = t.prompt.flatten().length();
        System.out.println("  prompt chars: " + len + ", history turns: " + t.prompt.recentConversation.size() + ", memory chars: " + t.prompt.relevantMemory.length());
        T.check(len < 7000, "prompt never grows with database size");
    }

    static boolean lastPickWasRepeat;

    static String pickStandalone(Topic t, Map<String, Set<Integer>> used, Random rnd, Map<String, List<String[]>> asked) {
        Set<Integer> u = used.get(t.label); if (u == null) { u = new HashSet<Integer>(); used.put(t.label, u); }
        int idx = -1;
        for (int tries = 0; tries < 30; tries++) { int c = rnd.nextInt(t.standalone.size()); if (!u.contains(c)) { idx = c; break; } }
        lastPickWasRepeat = idx < 0;
        if (idx < 0) idx = rnd.nextInt(t.standalone.size());
        u.add(idx);
        String[] e = t.standalone.get(idx);
        List<String[]> a = asked.get(t.label); if (a == null) { a = new ArrayList<String[]>(); asked.put(t.label, a); }
        boolean dup = false; for (String[] x : a) if (x == e) dup = true;
        if (!dup) a.add(e);
        return e[0];
    }

    static Topic byLabel(List<Topic> l, String label) { for (Topic t : l) if (t.label.equals(label)) return t; throw new IllegalStateException(label); }
}
