#!/usr/bin/env python3
"""Stage 1 seed corpus for SkillRouter. Expected labels come from the template that made the row
(what the sentence MEANS), not from running the router. Output: router_corpus.tsv
columns: id  block  style  attach  lastreply  text  expected
attach = comma list of IMAGE,PDF,ZIP,AUDIO,VIDEO,DOCX,XLSX,PPTX or '-'; expected = SkillPlan.signature()"""
import random, itertools
random.seed(11)
READER = {"IMAGE":"IMAGE_READER","PDF":"PDF_READER","ZIP":"PROJECT_ZIP_READER","AUDIO":"AUDIO_READER","VIDEO":"VIDEO_READER","DOCX":"DOCX","XLSX":"XLSX","PPTX":"PPTX"}
SK = {"pdf":"PDF_CREATOR","docx":"DOCX","xlsx":"XLSX","pptx":"PPTX"}
FMT = {"pdf":["pdf"],"docx":["word file","word document","docx","word doc"],
       "xlsx":["excel sheet","excel","xlsx","spreadsheet"],"pptx":["ppt","presentation","powerpoint","slides"]}
MISS = {"pdf":"pdff","docx":"wrod file","xlsx":"excle","pptx":"presentaion"}
TOPICS = ["AI","solar energy","india ka itihas","cricket","healthy diet","space travel","water cycle","football","electric cars","yoga",
          "photosynthesis","monsoon","startup funding","chess","ocean life"]
rows, seen = [], set()
def add(block, style, attach, last, text, exp):
    key = (text.strip(), attach, last)
    if key in seen: return
    seen.add(key)
    rows.append((block, style, attach, last, text, exp))
def reads(attach):
    return [("READ:"+READER[a]) for a in attach.split(",")] if attach != "-" else []
def sig(attach, *tasks):
    return ">".join(reads(attach) + list(tasks))

# ---- A. create with a topic (no attachment) ----
for f in FMT:
    for i, t in enumerate(TOPICS):
        n = FMT[f][i % len(FMT[f])]
        c = "CREATE:"+SK[f]
        styles = [
          ("hinglish", f"{t} par {n} banao"),
          ("english", f"create a {n} about {t}"),
          ("spelling", f"{t} pr {MISS[f]} bnao"),
          ("reversed", f"banao {t} par {n}"),
          ("voice", f"mujhe {t} ke upar ek {n} bana do"),
          ("long", f"yaar mujhe kal subah tak {t} ke upar ek achhi si {n} chahiye please jaldi bana do"),
        ]
        st = styles[i % len(styles)]
        add("create-topic", st[0], "-", 0, st[1], c)

# ---- B. create from last reply / bare ----
for f in FMT:
    for n in FMT[f]:
        add("create-bare", "short", "-", 1, f"{n} banao", "CREATE:"+SK[f])
        add("create-bare", "short", "-", 0, f"{n} banao", "CLARIFY")
    add("create-followup", "followup", "-", 1, f"isko {FMT[f][0]} me daal do", "CREATE:"+SK[f])
    add("create-followup", "followup", "-", 1, f"pehle wala {FMT[f][-1]} bana do", "CREATE:"+SK[f])
    add("create-followup", "followup", "-", 0, f"pehle wala {FMT[f][-1]} bana do", "CLARIFY")
    add("create-bare", "english", "-", 1, f"make me a {FMT[f][0]}", "CREATE:"+SK[f])
    add("create-bare", "hinglish", "-", 1, f"ek {FMT[f][0]} chahiye", "CREATE:"+SK[f])
    add("create-text", "typed", "-", 0, f"{FMT[f][0]} banao: Meeting kal 5 baje hai", "CREATE:"+SK[f])
    add("create-chat", "whole-chat", "-", 1, f"poori chat ka {FMT[f][0]} banao", "CREATE:"+SK[f])

# ---- C. convert / create from an attached file ----
SRC = {"DOCX":"word file","XLSX":"excel sheet","PPTX":"ppt","PDF":"pdf"}
for src, sname in SRC.items():
    for f in FMT:
        if SK[f] == READER[src] or (src=="PDF" and f=="pdf"): continue
        n = FMT[f][0]
        add("convert", "hinglish", src, 0, f"is {sname} ko {n} me convert karo", sig(src,"CREATE:"+SK[f]))
        add("convert", "english", src, 0, f"convert this to {n}", sig(src,"CREATE:"+SK[f]))
        add("convert", "followup", src, 0, f"isko {n} me daal do", sig(src,"CREATE:"+SK[f]))
for kind in ("IMAGE","VIDEO","ZIP"):
    add("create-from-file", "hinglish", kind, 0, "isko pdf me daal do", sig(kind,"CREATE:PDF_CREATOR"))

# ---- D. edit ----
NAMES = [("Ravi","Raj"),("Amit","Sumit"),("Delhi","Mumbai"),("2024","2025"),("Sharma","Verma"),("draft","final"),("Monday","Friday"),("Pune","Nagpur")]
for kind in ("DOCX","PPTX"):
    for i,(a,b) in enumerate(NAMES):
        forms = [
          ("hinglish", f"{a} ki jagah {b} likh do"),
          ("english", f"replace {a} with {b}"),
          ("english", f"change \"{a}\" to \"{b}\""),
          ("hinglish", f"{a} ko {b} kar do"),
          ("reversed", f"{b} likh do {a} ki jagah"),
          ("spelling", f"isme {a} ki jagah {b} likh do"),
          ("hinglish", f"{a} ko {b} se badlo"),
        ]
        st, tx = forms[i % len(forms)]
        add("edit", st, kind, 0, tx, sig(kind, "EDIT:"+READER[kind]))
CELLS = [("B2","500"),("C3","250"),("A1","Name"),("D4","Total"),("E10","1200"),("B7","Done")]
for i,(c,v) in enumerate(CELLS):
    forms = [("hinglish", f"{c} me {v} likh do"),("english", f"set {c} to {v}"),("hinglish", f"{c} ko {v} kar do"),
             ("hinglish", f"cell {c} mein {v} daal do"),("english", f"put {v} in {c}"),("spelling", f"{c.lower()} me {v} likhdo")]
    st, tx = forms[i % len(forms)]
    exp = sig("XLSX","EDIT:XLSX")
    add("edit-cell", st, "XLSX", 0, tx, exp)
add("edit-cell", "hinglish", "XLSX", 0, "B2 me 500 aur C3 me 250 likh do", sig("XLSX","EDIT:XLSX","EDIT:XLSX"))
add("edit-clarify", "hinglish", "-", 0, "Ravi ki jagah Raj likh do", "CLARIFY")
add("edit-clarify", "english", "-", 0, "replace Ravi with Raj", "CLARIFY")
add("edit-clarify", "hinglish", "-", 0, "B2 me 500 likh do", "CLARIFY")
add("edit-clarify", "hinglish", "DOCX", 0, "B2 me 500 likh do", "CLARIFY")
add("edit-clarify", "hinglish", "DOCX", 0, "isme badlav karo", "CLARIFY")
add("edit-clarify", "english", "PPTX", 0, "please edit this", "CLARIFY")
add("edit-clarify", "hinglish", "XLSX", 0, "excel me Ravi ki jagah Raj likh do", "CLARIFY")
add("edit-clarify", "hinglish", "DOCX,DOCX", 0, "Ravi ki jagah Raj likh do", "CLARIFY")
add("edit-pdf", "hinglish", "PDF", 0, "Ravi ki jagah Raj likh do", "READ:PDF_READER|UNSUP")
add("edit-pdf", "english", "PDF", 0, "replace Ravi with Raj", "READ:PDF_READER|UNSUP")

# ---- E. multi-task ----
for i,t in enumerate(TOPICS[:10]):
    add("multi", "hinglish", "-", 0, f"{t} par word aur excel dono banao", "CREATE:DOCX>CREATE:XLSX")
    add("multi", "english", "-", 0, f"make a pdf and a ppt about {t}", "CREATE:PDF_CREATOR>CREATE:PPTX")
    add("multi", "hinglish", "-", 0, f"pehle {t} par word banao phir uska pdf bhi bana do", "CREATE:DOCX>CREATE:PDF_CREATOR")
    add("multi", "voice", "-", 0, f"{t} par word excel ppt aur pdf sab banao", "CREATE:DOCX>CREATE:XLSX>CREATE:PPTX|LIMIT")
add("multi", "hinglish", "PDF,DOCX", 0, "dono files padho", "READ:PDF_READER>READ:DOCX")
add("multi", "hinglish", "IMAGE,PDF,XLSX", 0, "ye sab dekho aur batao", "READ:IMAGE_READER>READ:PDF_READER>READ:XLSX")
add("multi", "hinglish", "DOCX", 0, "isko excel me daal do phir uska pdf bhi banao", "READ:DOCX>CREATE:XLSX>CREATE:PDF_CREATOR")

# ---- F. read only ----
for k in READER:
    for tx, st in (("isme kya hai", "hinglish"), ("what is in this file", "english"), ("ye padho", "short")):
        add("read", st, k, 0, tx, sig(k))

# ---- F2. extra styles (upper case, no punctuation, long, other order) ----
EXTRA = [
 ("PDF BANAO AI PAR","-",0,"CREATE:PDF_CREATOR","uppercase"),
 ("Make a PowerPoint on solar energy","-",0,"CREATE:PPTX","english"),
 ("kya aap word file bana sakte ho cricket par","-",0,"CREATE:DOCX","english"),
 ("excel sheet bana do marks ki","-",0,"CREATE:XLSX","hinglish"),
 ("yoga ke liye slides bana do","-",0,"CREATE:PPTX","hinglish"),
 ("export as pdf","-",1,"CREATE:PDF_CREATOR","english"),
 ("is chat ko word me save kar do","-",1,"CREATE:DOCX","hinglish"),
 ("uska excel bhi bana do","-",1,"CREATE:XLSX","followup"),
 ("pdf aur word dono me chahiye","-",1,"CREATE:PDF_CREATOR>CREATE:DOCX","hinglish"),
 ("hello pdf banao","-",1,"CREATE:PDF_CREATOR","short"),
 ("isko word me de do","PDF",0,"READ:PDF_READER>CREATE:DOCX","followup"),
 ("pdf ko word kar do","PDF",0,"READ:PDF_READER>CREATE:DOCX","hinglish"),
 ("is ppt ka pdf bana do","PPTX",0,"READ:PPTX>CREATE:PDF_CREATOR","hinglish"),
 ("sheet ka pdf nikal do","XLSX",0,"READ:XLSX>CREATE:PDF_CREATOR","hinglish"),
 ("2025 ki jagah 2026 likh do","PPTX",0,"READ:PPTX>EDIT:PPTX","hinglish"),
 ("slide me Ravi ko Raj kar do","PPTX",0,"READ:PPTX>EDIT:PPTX","hinglish"),
 ("A1 me Name likh do","XLSX",0,"READ:XLSX>EDIT:XLSX","hinglish"),
 ("sheet2 me B5 me 99 likh do","XLSX",0,"READ:XLSX>EDIT:XLSX","hinglish"),
 ("is image me kya likha hai","IMAGE",0,"READ:IMAGE_READER","hinglish"),
 ("video ka summary batao","VIDEO",0,"READ:VIDEO_READER","hinglish"),
 ("is zip me kya hai","ZIP",0,"READ:PROJECT_ZIP_READER","hinglish"),
 ("audio ki duration kitni hai","AUDIO",0,"READ:AUDIO_READER","hinglish"),
 ("excel me data daal do","-",1,"CREATE:XLSX","hinglish"),
 ("slide banane ka tarika kaise hai","-",0,"NONE","hinglish"),
 ("excel kaise seekhu","-",0,"NONE","hinglish"),
 ("word file kya hoti hai","-",0,"NONE","hinglish"),
 ("pdf banao nahi chahiye","-",0,"NONE","hinglish"),
]
for tx, att, last, exp, st in EXTRA:
    add("extra", st, att, last, tx, exp)

# ---- G. negatives / honesty ----
NEG = [
 ("password banao","-","NONE"),("strong password kaise banate hain","-","NONE"),("music theory samjhao","-","NONE"),
 ("song ke lyrics ka matlab batao","-","NONE"),("screen recording kaise karte hain","-","NONE"),("ogg vorbis kya hai","-","NONE"),
 ("word count kya hota hai","-","NONE"),("ek word batao jo H se shuru ho","-","NONE"),("bed sheet banane ka tarika","-","NONE"),
 ("slide karna hai kal","-","NONE"),("deck of cards ka game banao","-","NONE"),("pdf mat banao","-","NONE"),
 ("dont make a pdf","-","NONE"),("excel me formula kaise lagate hain","-","NONE"),("how to make a pdf in python","-","NONE"),
 ("python se excel banao","-","NONE"),("namaste","-","NONE"),("tum kaun ho","-","NONE"),("2+2 kya hai","-","NONE"),
 ("word ka matlab kya hai","-","NONE"),("kal presentation hai tips do","-","NONE"),("presentation me kya bolna chahiye","-","NONE"),
 ("pdf kya hota hai","-","NONE"),("excel seekhna hai","-","NONE"),("powerpoint kab aaya tha","-","NONE"),
 ("document banao","-","CLARIFY"),("resume bana do","-","CLARIFY"),("ek letter bana do","-","CLARIFY"),("report banao","-","CLARIFY"),
 ("csv banao","-","UNSUP"),("html file banao","-","UNSUP"),
 ("audio ka transcript chahiye","-","UNSUP"),("is purani report.doc ko edit karo","-","UNSUP"),(".xls file padho","-","UNSUP"),
 ("word nahi excel banao","-","CREATE:XLSX"),("pdf nahi ppt banao","-","CREATE:PPTX"),
 ("transcript do","AUDIO","READ:AUDIO_READER|UNSUP"),("video ka transcript likh do","VIDEO","READ:VIDEO_READER|UNSUP"),
 ("speech to text karo","AUDIO","READ:AUDIO_READER|UNSUP"),
 ("music theory samjhao","AUDIO","READ:AUDIO_READER"),
]
for tx, att, exp in NEG:
    last = 1 if exp == "CREATE:XLSX" or exp == "CREATE:PPTX" else 0
    block = "negative" if exp in ("NONE","UNSUP","CLARIFY") else "negation"
    add(block, "mixed", att, last, tx, exp)

with open("router_corpus.tsv","w",encoding="utf-8") as fh:
    fh.write("id\tblock\tstyle\tattach\tlastreply\ttext\texpected\n")
    for i,r in enumerate(rows,1):
        fh.write(f"{i}\t{r[0]}\t{r[1]}\t{r[2]}\t{r[3]}\t{r[4]}\t{r[5]}\n")
print(len(rows), "rows")
from collections import Counter
print(Counter(r[0] for r in rows))
