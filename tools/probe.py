import json,random,time,requests,urllib.parse
S=requests.Session();S.headers['User-Agent']='PolkaCatalogProbe/0.1 (https://github.com/RuslanB42/Book)'
def sparql(q):
    for k in range(4):
        try:
            r=S.get('https://query.wikidata.org/sparql',params={'query':q,'format':'json'},timeout=90)
            if r.status_code==200:return r.json()['results']['bindings']
            print('sparql',r.status_code,r.text[:200])
        except Exception as e:print('sparql err',e)
        time.sleep(5+k*10)
    return None
out={'counts':{}}
TYPES={'novel':'Q8261','literary work':'Q7725634','novella':'Q149537','short story':'Q49084','play':'Q25379','book':'Q571','story collection':'Q1279564','written work':'Q47461344','poem':'Q5185279','book series':'Q277759','novel series':'Q1667921','non-fiction':'Q213051','fairy tale':'Q699','essay':'Q35760','memoir':'Q112983','graphic novel':'Q725377'}
for name,q in TYPES.items():
    r=sparql('SELECT (COUNT(DISTINCT ?i) AS ?n) (COUNT(DISTINCT ?ol) AS ?o) (COUNT(DISTINCT ?gr) AS ?g) WHERE{?i wdt:P31 wd:%s. ?a schema:about ?i; schema:isPartOf <https://ru.wikipedia.org/>. OPTIONAL{?i wdt:P648 ?ol} OPTIONAL{?i wdt:P8383 ?gr}}'%q)
    out['counts'][name]={k:int(v['value']) for k,v in r[0].items()} if r else None;print(name,out['counts'][name])
books={}
for T in('Q7725634','Q47461344','Q8261'):
    for off in range(0,40000,5000):
        rows=sparql('SELECT ?i ?a ?l WHERE{?i wdt:P31 wd:%s. ?a schema:about ?i; schema:isPartOf <https://ru.wikipedia.org/>. ?i wikibase:sitelinks ?l} ORDER BY ?i LIMIT 5000 OFFSET %d'%(T,off)) or []
        for r in rows:books.setdefault(r['i']['value'],{'title':urllib.parse.unquote(r['a']['value'].split('/wiki/')[1]),'links':int(r['l']['value'])})
        print(T,off,len(rows),len(books))
        if len(rows)<5000:break
for prop,k in(('P648','ol'),('P8383','gr'),('P18','img')):
    for T in('Q7725634','Q47461344','Q8261'):
        rows=sparql('SELECT ?i ?v WHERE{?i wdt:P31 wd:%s; wdt:%s ?v. ?a schema:about ?i; schema:isPartOf <https://ru.wikipedia.org/>}'%(T,prop)) or []
        for r in rows:
            if r['i']['value'] in books:books[r['i']['value']][k]=r['v']['value']
        print(prop,T,len(rows))
out['novels']=len(books);print('novels',len(books))
random.seed(7);items=list(books.values())
top=sorted(items,key=lambda b:-b['links'])[:3000]
sample=random.sample(top,min(150,len(top)))+random.sample(items,min(150,len(items)))
stats={k:0 for k in['extract','extract300','pageimage','ol_id','ol_cover','ol_search_cover','gb_found','gb_cover','gb_desc_ru','p18','any_cover','any_free_cover']}
ex=[]
for n,b in enumerate(sample):
    t=b['title'];e={'t':t,'links':b['links']}
    try:
        r=S.get('https://ru.wikipedia.org/w/api.php',params={'action':'query','format':'json','prop':'extracts|pageimages|pageprops','exintro':1,'explaintext':1,'piprop':'thumbnail|name','pithumbsize':400,'titles':t,'redirects':1},timeout=30).json()
        p=list(r['query']['pages'].values())[0];x=p.get('extract','');e['extract']=x[:400];e['len']=len(x);e['pimg']=p.get('thumbnail',{}).get('source')
    except Exception as er:e['err']=str(er)
    if b.get('ol'):
        try:
            w=S.get('https://openlibrary.org/works/%s.json'%b['ol'],timeout=30).json();e['olc']=(w.get('covers') or [None])[0]
        except Exception as er:e['olerr']=str(er)
    name=t.replace('_',' ').split(' (')[0]
    try:
        s=S.get('https://openlibrary.org/search.json',params={'title':name,'limit':3,'fields':'key,title,cover_i,language,author_name'},timeout=30).json()
        e['ols']=next((d.get('cover_i') for d in s.get('docs',[]) if d.get('cover_i')),None)
    except Exception as er:e['olserr']=str(er)
    try:
        g=S.get('https://www.googleapis.com/books/v1/volumes',params={'q':'intitle:"%s"'%name,'langRestrict':'ru','maxResults':3,'country':'US'},timeout=30).json()
        its=g.get('items',[]);e['gb']=len(its);e['gbc']=next((i['volumeInfo'].get('imageLinks',{}).get('thumbnail') for i in its if i['volumeInfo'].get('imageLinks')),None)
        e['gbd']=next((i['volumeInfo'].get('description','')[:300] for i in its if i['volumeInfo'].get('description')),None)
        if 'error' in g:e['gberr']=g['error'].get('message')
    except Exception as er:e['gberr']=str(er)
    e['p18']=b.get('img')
    stats['extract']+=e.get('len',0)>0;stats['extract300']+=e.get('len',0)>=300;stats['pageimage']+=bool(e.get('pimg'));stats['ol_id']+=bool(b.get('ol'));stats['ol_cover']+=bool(e.get('olc') and e['olc']>0)
    stats['ol_search_cover']+=bool(e.get('ols'));stats['gb_found']+=bool(e.get('gb'));stats['gb_cover']+=bool(e.get('gbc'));stats['gb_desc_ru']+=bool(e.get('gbd'));stats['p18']+=bool(e.get('p18'))
    free=bool((e.get('olc') and e['olc']>0) or e.get('ols') or e.get('gbc') or e.get('p18'));stats['any_free_cover']+=free;stats['any_cover']+=free or bool(e.get('pimg'))
    e['group']='top' if n<150 else 'all';ex.append(e)
    if n%25==0:print(n,stats)
    time.sleep(.4)
def part(g):
    xs=[e for e in ex if e['group']==g];f=lambda fn:sum(1 for e in xs if fn(e))
    return {'n':len(xs),'extract300':f(lambda e:e.get('len',0)>=300),'pageimage':f(lambda e:e.get('pimg')),'ol_cover':f(lambda e:(e.get('olc') or 0)>0 or e.get('ols')),'gb_cover':f(lambda e:e.get('gbc')),'gb_desc':f(lambda e:e.get('gbd')),'p18':f(lambda e:e.get('p18')),
      'cover_no_wiki':f(lambda e:(e.get('olc') or 0)>0 or e.get('ols') or e.get('gbc') or e.get('p18')),'cover_any':f(lambda e:(e.get('olc') or 0)>0 or e.get('ols') or e.get('gbc') or e.get('p18') or e.get('pimg'))}
out['stats']=stats;out['top']=part('top');out['all']=part('all');out['examples']=ex
json.dump(out,open('probe.json','w'),ensure_ascii=False,indent=1);print(out['top'],out['all'])
