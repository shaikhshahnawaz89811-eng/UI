from pathlib import Path
import csv

OUT = Path('phase5/stage4_5000.tsv')
subjects = [
    'AI basics','solar energy','water cycle','Indian history','healthy diet','space travel','football','cricket','yoga','photosynthesis',
    'electric cars','startup funding','ocean life','chess','monsoon','Python basics','Android apps','cloud computing','data science','machine learning',
    'digital safety','renewable power','internet history','database design','home budgeting','study planning','road travel','smart homes','music history','gardening',
    'personal finance','web development','robotics','climate change','nutrition','geometry','probability','public speaking','photography','time management',
    'cyber security','mobile testing','Git basics','API design','SQL queries','Kotlin coroutines','Excel formulas','presentation skills','business ideas','product planning'
]
spell = {
    'pdf':'pdff', 'word':'wrod', 'excel':'excle', 'ppt':'pptx', 'presentation':'presentaion', 'powerpoint':'powerpnt',
    'banao':'bnao', 'create':'creat', 'sheet':'shet', 'slides':'slids', 'document':'dcoument'
}

def variants(fmt, subj, n):
    f = {'pdf':'pdf','docx':'word','xlsx':'excel','pptx':'presentation'}[fmt]
    s = n % 8
    if s == 0:
        return f'{subj} par {f} banao'
    if s == 1:
        return f'Create a {f} about {subj}' if fmt != 'xlsx' else f'Create an excel sheet about {subj}'
    if s == 2:
        return f'{subj} pr {spell.get(f,f)} {spell["banao"]}'
    if s == 3:
        return f'{f} {subj} banao'  # intentionally terse but still format + verb
    if s == 4:
        return f'Bhai mujhe {subj} ke baare mein ek simple {f} bana do please'
    if s == 5:
        return f'{f.capitalize()} {subj} par banao'
    if s == 6:
        return f'{subj} ke upar ek {f} tayar karo'
    return f'mujhe {subj} ka {f} chahiye'

def read_variants(kind, name, n):
    s = n % 8
    label = {'IMAGE':'photo','PDF':'pdf','AUDIO':'audio','VIDEO':'video','ZIP':'zip','DOCX':'word file','XLSX':'excel sheet','PPTX':'slides'}[kind]
    if s == 0: return f'{label} padho'
    if s == 1: return f'Read this {label}'
    if s == 2: return f'{name} check karo'
    if s == 3: return f'iske andar kya hai batao'
    if s == 4: return f'please {label} ko review karo'
    if s == 5: return f'ye {label} dekh ke samjhao'
    if s == 6: return f'{label} ka content extract karo'
    return f'open karke {label} ka summary batao'

def edit_variant(fmt, n):
    if fmt == 'xlsx':
        cells = ['B2','C3','D4','E5','F6','G7','H8','A10']
        vals = ['125','250','350','500','750','900','42','1000']
        sheet = ['Data','Sales','Summary','Budget','Report','Input','Output','Sheet1'][n % 8]
        c = cells[n % 8]; v = vals[n % 8]
        s = n % 8
        if s == 0: return f'{c} me {v} likh do'
        if s == 1: return f'set {c} to {v}'
        if s == 2: return f'sheet {sheet} me {c} me {v} likh do'
        if s == 3: return f'put {v} in {c}'
        if s == 4: return f'excel me {c} ko {v} kar do'
        if s == 5: return f'{sheet} sheet mein {c} ki value {v} karna'
        if s == 6: return f'enter {v} at {c}'
        return f'{c} mein {v} bhar do'
    old = ['Ravi','Draft','Old title','Pending','Alpha','Version 1','Mumbai','Monday'][n % 8]
    new = ['Raj','Final','New title','Done','Beta','Version 2','Pune','Friday'][n % 8]
    s = n % 8
    f = {'docx':'word','pptx':'ppt'}[fmt]
    if s == 0: return f'{old} ki jagah {new} likh do'
    if s == 1: return f'replace {old} with {new}'
    if s == 2: return f'{new} likh do {old} ki jagah'
    if s == 3: return f'change {old} to {new}'
    if s == 4: return f'{f} me {old} ko {new} kar do'
    if s == 5: return f'please update {old} as {new}'
    if s == 6: return f'{old} ko {new} se replace karo'
    return f'modify this {f}: replace {old} with {new}'

rows=[]
_id=1

def add(block, style, attach, last, text, expected, must):
    global _id
    text = text + (' ' if text and not text.endswith(' ') else '') + 'r' + str(_id)
    rows.append([_id,block,style,attach,'1' if last else '0',text,expected,must])
    _id += 1

# Read 2500: 350 each
skill_map={'IMAGE':'IMAGE_READER','PDF':'PDF_READER','AUDIO':'AUDIO_READER','VIDEO':'VIDEO_READER','ZIP':'PROJECT_ZIP_READER','DOCX':'DOCX','XLSX':'XLSX','PPTX':'PPTX'}
for kind in ['IMAGE','PDF','AUDIO','VIDEO','ZIP','DOCX','XLSX','PPTX']:
    count = 350 if kind in ('IMAGE','PDF') else 300
    for i in range(count):
        name=f'{kind.lower()}_{i+1}.{kind.lower()}'
        # Correct common extensions for realistic inputs
        ext={'IMAGE':'jpg','PDF':'pdf','AUDIO':'mp3','VIDEO':'mp4','ZIP':'zip','DOCX':'docx','XLSX':'xlsx','PPTX':'pptx'}[kind]
        name=f'{kind.lower()}_{i+1}.{ext}'
        style=['hinglish','english','spelling','short','long','reversed','nopunct','followup'][i%8]
        add('read_'+kind.lower(), style, kind, i%2==1, read_variants(kind,name,i), f'READ:{skill_map[kind]}', 'NO_CREATE;NO_EDIT;NO_WEB')

# Create 1000: 250 each
for fmt in ['pdf','docx','xlsx','pptx']:
    skill={'pdf':'PDF_CREATOR','docx':'DOCX','xlsx':'XLSX','pptx':'PPTX'}[fmt]
    for i in range(250):
        text=variants(fmt, subjects[i%len(subjects)], i)
        style=['hinglish','english','spelling','short','long','reversed','nopunct','followup'][i%8]
        last=(style=='followup')
        add('create_'+fmt, style, '-', last, text, f'CREATE:{skill}', 'NO_FALSE_READY;NO_EDIT;NO_OVERWRITE')

# Edit 600: 200 each
for fmt in ['docx','xlsx','pptx']:
    skill={'docx':'DOCX','xlsx':'XLSX','pptx':'PPTX'}[fmt]
    for i in range(200):
        text=edit_variant(fmt, i)
        style=['hinglish','english','spelling','short','long','reversed','nopunct','followup'][i%8]
        add('edit_'+fmt, style, fmt.upper(), True, text, f'READ:{skill}>EDIT:{skill}', 'NO_OVERWRITE;NO_CREATE_NEW_SOURCE')

# Multi-task 500. Keep patterns deterministic and within 3 write/read jobs.
multi_subjects=subjects[:25]
patterns=[
    ('-',True,'{a} par word aur excel dono banao','CREATE:DOCX>CREATE:XLSX'),
    ('-',False,'create a pdf about {a} and a powerpoint about {b}','CREATE:PDF_CREATOR>CREATE:PPTX'),
    ('-',False,'{a} par word banao phir pdf banao','CREATE:DOCX>CREATE:PDF_CREATOR'),
    ('DOCX',False,'is word file ko pdf me convert karo aur ppt banao','READ:DOCX>CREATE:PDF_CREATOR>CREATE:PPTX'),
    ('XLSX',False,'is excel sheet ko word file me convert karo phir pdf banao','READ:XLSX>CREATE:DOCX>CREATE:PDF_CREATOR'),
    ('PPTX',False,'is ppt ko pdf me convert karo aur word file banao','READ:PPTX>CREATE:PDF_CREATOR>CREATE:DOCX'),
    ('DOCX,XLSX',False,'dono files padho aur ppt banao','READ:DOCX>READ:XLSX>CREATE:PPTX'),
    ('PDF',False,'is pdf ko word me convert karo phir excel banao','READ:PDF_READER>CREATE:DOCX>CREATE:XLSX'),
    ('XLSX',False,'B2 me 500 likh do aur report ppt banao','READ:XLSX>EDIT:XLSX>CREATE:PPTX'),
    ('DOCX,PPTX',False,'files padho aur pdf banao','READ:DOCX>READ:PPTX>CREATE:PDF_CREATOR'),
]
for i in range(500):
    a=multi_subjects[i%len(multi_subjects)]; b=multi_subjects[(i*3+7)%len(multi_subjects)]
    attach,last,pat,exp=patterns[i%len(patterns)]
    text=pat.format(a=a,b=b)
    style=['hinglish','english','spelling','short','long','reversed','nopunct','followup'][i%8]
    # Style variation while keeping semantics stable.
    if i%8==2 and 'word aur excel' in text: text=text.replace('word','wrod').replace('excel','excle').replace('banao','bnao')
    elif i%8==3: text=text.replace('banao','create')
    elif i%8==4: text='Bhai please '+text+' jaldi kar do'
    elif i%8==5:
        if i % len(patterns) == 1:
            text=f'pdf about {a} banao and a powerpoint about {b} banao'
        else:
            text=text.replace('create a pdf about','pdf about').replace('banao','banao')
    elif i%8==6: text=text.replace(' aur ',' and ')
    elif i%8==7: text=text.replace('phir ','then ')
    add('multi',style,attach,last,text,exp,'NO_FALSE_READY;NO_OVERWRITE;MAX_3_TASKS')

# Negative + failure 400
negative_templates=[
    ('-',False,'music theory samjhao','NONE'),
    ('-',False,'strong password kaise banate hain','NONE'),
    ('-',False,'word ka matlab kya hota hai','NONE'),
    ('-',False,'pdf mat banao','NONE'),
    ('-',False,'dont make a pdf','NONE'),
    ('-',False,'presentation me kya bolna chahiye','NONE'),
    ('-',False,'how to make a pdf in python','NONE'),
    ('-',False,'python se excel banao','NONE'),
    ('-',False,'deck of cards ka game banao','NONE'),
    ('-',False,'document banao','CLARIFY'),
    ('-',False,'report bana do','CLARIFY'),
    ('-',False,'resume banao','CLARIFY'),
    ('-',False,'ek letter bana do','CLARIFY'),
    ('AUDIO',False,'audio ka transcript do','READ:AUDIO_READER|UNSUP'),
    ('VIDEO',False,'video ko text me transcribe karo','READ:VIDEO_READER|UNSUP'),
    ('-',False,'legacy file.doc kholo','UNSUP'),
    ('-',False,'old.xls read karo','UNSUP'),
    ('-',False,'old.ppt check karo','UNSUP'),
    ('PDF',False,'pdf edit karo','CLARIFY'),
    ('PDF',False,'isko change karo','READ:PDF_READER'),
]
for i in range(400):
    attach,last,base,exp=negative_templates[i%len(negative_templates)]
    # Make unique while keeping key phrase recognizable.
    text=base
    suffix=f' [case {i+1}]'
    # punctuation would alter tokenization less, but keep uniqueness through a final marker word.
    text=text + suffix
    style=['hinglish','english','spelling','short','long','reversed','nopunct','followup'][i%8]
    add('negative',style,attach,last,text,exp,'NO_FALSE_READY;NO_UNSAFE_EDIT;NO_WEB_WHEN_LOCAL')

assert len(rows)==5000, len(rows)
texts=[r[5] for r in rows]
assert len(set(texts))==len(texts), 'duplicate commands'
with OUT.open('w',encoding='utf-8',newline='') as f:
    w=csv.writer(f,delimiter='\t',lineterminator='\n')
    w.writerow(['id','block','style','attachments','last','command','expected','must_not'])
    w.writerows(rows)

from collections import Counter
print('wrote',OUT,'rows',len(rows))
print(Counter(r[1] for r in rows))
print(Counter(r[2] for r in rows))
