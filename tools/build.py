import gzip, json, sqlite3, re, sys, os, collections
from wordfreq import zipf_frequency as zf
try:
    import orjson; loads=orjson.loads
except Exception: loads=json.loads
ACC=re.compile('[́̀]')
def key(w): return ACC.sub('',w).lower().replace('ё','е').strip()
def clip(s,n): s=re.sub(r'\s+',' ',s or '').strip(); return s if len(s)<=n else s[:n-1].rstrip()+'…'
db=sqlite3.connect('out/dict.db'); db.execute('PRAGMA journal_mode=OFF'); db.execute('PRAGMA synchronous=OFF')
db.execute('CREATE TABLE e(id INTEGER PRIMARY KEY, lang TEXT, word TEXT, data TEXT)')
db.execute('CREATE TABLE k(key TEXT, id INTEGER)')
stats=collections.Counter()
POSRU={'noun':'существительное','verb':'глагол','adj':'прилагательное','adv':'наречие','pron':'местоимение','prep':'предлог','conj':'союз','particle':'частица','intj':'междометие','num':'числительное','phrase':'фраза','name':'имя собственное','det':'определитель','participle':'причастие','predicative':'предикатив','prefix':'приставка','suffix':'суффикс','abbrev':'сокращение','proverb':'пословица','idiom':'идиома','article':'артикль','contraction':'сокращение','postp':'послелог'}
OLD=re.compile('[ſꝛ]|\b(thee|thou|hath|doth)\b',re.I)
def senses_of(o,nlimit=3,glen=150,elen=100):
    out=[]
    for s in o.get('senses',[]):
        if s.get('form_of') or s.get('alt_of'): continue
        g=s.get('glosses') or []
        if not g: continue
        t=clip(g[-1],glen)
        if not t or t.startswith('Отсутствует пример'): continue
        ex=''
        if len(out)<2:
          for e in s.get('examples',[]) or []:
            tx=e.get('text') or ''
            if e.get('type')=='quotation' or e.get('ref') or OLD.search(tx): continue
            if 8<len(tx)<=elen: ex=clip(tx,elen); break
        tags=[x for x in (s.get('raw_tags') or []) if len(x)<24][:2]
        out.append([t,ex,', '.join(tags)])
        if len(out)>=nlimit: break
    return out
# 1. Russian from enwiktionary: stressed headwords and inflected forms → lemma
stress={}; ruforms=collections.defaultdict(set)
with gzip.open('src/ru-en.jsonl.gz','rb') as f:
    for line in f:
        o=loads(line)
        if o.get('lang_code')!='ru': continue
        w=o.get('word','')
        for fm in o.get('forms',[]) or []:
            tg=fm.get('tags') or []; ff=fm.get('form','')
            if 'canonical' in tg and ff: stress.setdefault(key(w),ff.split(' ')[0] if ' ' not in w else ff)
            elif ff and 'table-tags' not in tg and 'inflection-template' not in tg and len(ff)<40 and not ff.startswith('-'):
                for part in ff.split(','): 
                    p=part.strip()
                    if p: ruforms[key(p)].add(key(w))
        for s in o.get('senses',[]) or []:
            for fo in s.get('form_of',[]) or []:
                if fo.get('word'): ruforms[key(w)].add(key(fo['word']))
stats['ru_stress']=len(stress); stats['ru_forms']=len(ruforms)
# 2. Russian definitions from ruwiktionary
rukeys={}
with gzip.open('src/ru-ru.jsonl.gz','rb') as f:
    for line in f:
        o=loads(line)
        if o.get('lang_code')!='ru': continue
        w=o.get('word','')
        if not w or len(w)>40: continue
        if ' ' in w or zf(key(w),'ru')<1.6: continue
        ss=senses_of(o)
        if not ss: continue
        syn=[s.get('word') for s in (o.get('synonyms') or []) if s.get('word')][:5]
        k=key(w)
        d={'w':stress.get(k,w),'p':POSRU.get(o.get('pos',''),o.get('pos_title') or ''),'s':ss,'f':round(zf(k,'ru'),1)}
        if syn: d['y']=syn
        cur=db.execute('INSERT INTO e(lang,word,data) VALUES(?,?,?)',('ru',k,json.dumps(d,ensure_ascii=False,separators=(',',':'))))
        rukeys.setdefault(k,[]).append(cur.lastrowid); stats['ru_entries']+=1
for k,ids in rukeys.items():
    for i in ids: db.execute('INSERT INTO k VALUES(?,?)',(k,i))
for form,lemmas in ruforms.items():
    if zf(form,'ru')<1.0: continue
    for l in lemmas:
        if form==l: continue
        for i in rukeys.get(l,[]): db.execute('INSERT INTO k VALUES(?,?)',(form,i)); stats['ru_formkeys']+=1
# 3. English
enkeys={}; enforms=collections.defaultdict(set)
POSEN=POSRU
with gzip.open('src/en.jsonl.gz','rb') as f:
    for line in f:
        o=loads(line)
        if o.get('lang_code')!='en': continue
        w=o.get('word','')
        if not w or len(w)>40: continue
        k=key(w)
        for s in o.get('senses',[]) or []:
            for fo in s.get('form_of',[]) or []:
                if fo.get('word'): enforms[k].add(key(fo['word']))
        for fm in o.get('forms',[]) or []:
            ff=fm.get('form',''); tg=fm.get('tags') or []
            if ff and 'table-tags' not in tg and len(ff)<40: enforms[key(ff)].add(k)
        if o.get('pos')=='name' and not w[:1].islower(): continue
        if ' ' in w or zf(w.lower(),'en')<2.4: continue
        ss=senses_of(o,3,150,100)
        if not ss: continue
        tr=[]
        pool=list(o.get('translations',[]) or [])
        for s_ in o.get('senses',[]) or []: pool+=s_.get('translations',[]) or []
        for t in pool:
            if t.get('lang_code')=='ru' or t.get('code')=='ru' or t.get('lang')=='Russian':
                ww=t.get('word') or ''
                kk=key(ww)
                if ww and len(ww)<40 and not any(key(x)[:max(3,len(kk)-2)]==kk[:max(3,len(kk)-2)] for x in tr): tr.append(ww)
            if len(tr)>=4: break
        ipa=''
        for s in o.get('sounds',[]) or []:
            if s.get('ipa'): ipa=s['ipa']; break
        d={'w':w,'p':POSEN.get(o.get('pos',''),o.get('pos','')),'s':ss,'f':round(zf(w.lower(),'en'),1)}
        if tr: d['t']=tr
        if ipa: d['i']=ipa
        cur=db.execute('INSERT INTO e(lang,word,data) VALUES(?,?,?)',('en',k,json.dumps(d,ensure_ascii=False,separators=(',',':'))))
        enkeys.setdefault(k,[]).append(cur.lastrowid); stats['en_entries']+=1
for k,ids in enkeys.items():
    for i in ids: db.execute('INSERT INTO k VALUES(?,?)',(k,i))
for form,lemmas in enforms.items():
    if zf(form,'en')<1.5: continue
    for l in lemmas:
        if form==l: continue
        for i in enkeys.get(l,[]): db.execute('INSERT INTO k VALUES(?,?)',(form,i)); stats['en_formkeys']+=1
db.execute('CREATE TABLE k2 AS SELECT DISTINCT key,id FROM k');db.execute('DROP TABLE k');db.execute('ALTER TABLE k2 RENAME TO k')
db.execute('CREATE INDEX ki ON k(key)'); db.commit(); db.execute('VACUUM'); db.close()
print(dict(stats))
# samples
db=sqlite3.connect('out/dict.db')
for w in ['ran','went','better','предостерегая','предостерегать','посмотрела','ёлка','nuzzled','striking','glass','ran','книга']:
    r=db.execute('SELECT e.data FROM k JOIN e ON e.id=k.id WHERE k.key=? LIMIT 2',(key(w),)).fetchall()
    print(w,'→',[x[0][:220] for x in r])
