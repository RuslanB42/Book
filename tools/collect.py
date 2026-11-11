"""Stage 1 of the Polka catalogue: collect raw facts about well-known books from open sources (no keys, no paid APIs).
Wikidata (the list, authors, genres, years, ids) -> Russian Wikipedia (intro text, page picture, page views) ->
Open Library (covers, subjects) -> Wikimedia Commons (picture sizes). Writes raw.json."""
import json,time,sys,requests,urllib.parse,collections
S=requests.Session();S.headers['User-Agent']='PolkaCatalog/0.2 (https://github.com/RuslanB42/Book)'
TOP=int(sys.argv[1]) if len(sys.argv)>1 else 6000
def get(url,params=None,tries=4,timeout=60):
    for k in range(tries):
        try:
            r=S.get(url,params=params,timeout=timeout)
            if r.status_code==200:return r.json()
            if r.status_code==404:return None
            print('http',r.status_code,url[:80],flush=True)
        except Exception as e:print('err',str(e)[:120],flush=True)
        time.sleep(3+k*8)
    return None
def sparql(q):
    r=get('https://query.wikidata.org/sparql',{'query':q,'format':'json'},timeout=100);return r['results']['bindings'] if r else []
# 1) the list: literary works with an article in the Russian Wikipedia, by how many Wikipedias know them
books={}
for T in('Q7725634','Q47461344','Q8261'):
    for off in range(0,60000,5000):
        rows=sparql('SELECT ?i ?a ?l WHERE{?i wdt:P31 wd:%s. ?a schema:about ?i; schema:isPartOf <https://ru.wikipedia.org/>. ?i wikibase:sitelinks ?l} ORDER BY ?i LIMIT 5000 OFFSET %d'%(T,off))
        for r in rows:books.setdefault(r['i']['value'].rsplit('/',1)[1],{'wiki':urllib.parse.unquote(r['a']['value'].split('/wiki/')[1]).replace('_',' '),'links':int(r['l']['value'])})
        print(T,off,len(rows),len(books),flush=True)
        if len(rows)<5000:break
ids=sorted(books,key=lambda q:-books[q]['links'])[:TOP];books={q:books[q] for q in ids};print('kept',len(books),'min links',books[ids[-1]]['links'],flush=True)
# 2) Wikidata facts
P={'P50':'author','P136':'genre','P7937':'form','P407':'lang','P495':'country','P179':'series','P166':'award','P921':'subject','P840':'place','P2408':'period'}
refs=set()
def vals(c,p):
    out=[]
    for s in c.get(p,[]):
        v=s.get('mainsnak',{}).get('datavalue',{}).get('value')
        if v is not None:out.append((v,s))
    return out
for n in range(0,len(ids),50):
    r=get('https://www.wikidata.org/w/api.php',{'action':'wbgetentities','ids':'|'.join(ids[n:n+50]),'props':'claims|labels','languages':'ru|en','format':'json'}) or {}
    for q,e in r.get('entities',{}).items():
        b=books[q];c=e.get('claims',{});b['ru']=e.get('labels',{}).get('ru',{}).get('value');b['en']=e.get('labels',{}).get('en',{}).get('value')
        for p,k in P.items():
            xs=[v['id'] for v,_ in vals(c,p) if isinstance(v,dict) and 'id' in v]
            if xs:b[k]=xs[:8];refs.update(xs[:8])
        for v,s in vals(c,'P179'):
            o=s.get('qualifiers',{}).get('P1545');
            if o:b['seriesNo']=o[0].get('datavalue',{}).get('value')
        for p,k in(('P648','ol'),('P8383','grWork'),('P2969','grBook'),('P18','img'),('P1476','orig')):
            xs=[v for v,_ in vals(c,p)]
            if xs:b[k]=xs[0]['text'] if isinstance(xs[0],dict) else xs[0]
        ys=[v['time'] for v,_ in vals(c,'P577') if isinstance(v,dict) and 'time' in v]
        if ys:
            try:b['year']=min(int(y[:5] if y[0]=='-' else y[1:5])*(1 if y[0]=='+' else 1) for y in ys)
            except Exception:pass
    if n%1000==0:print('wikidata',n,flush=True)
refs=sorted(refs);names={}
for n in range(0,len(refs),50):
    r=get('https://www.wikidata.org/w/api.php',{'action':'wbgetentities','ids':'|'.join(refs[n:n+50]),'props':'labels','languages':'ru|en','format':'json'}) or {}
    for q,e in r.get('entities',{}).items():
        l=e.get('labels',{});names[q]={'ru':l.get('ru',{}).get('value'),'en':l.get('en',{}).get('value')}
print('labels',len(names),flush=True)
# 3) Russian Wikipedia: intro, picture (any licence: covers are usually fair use there), views
byTitle={b['wiki']:q for q,b in books.items()};titles=list(byTitle)
for n in range(0,len(titles),20):
    chunk=titles[n:n+20];params={'action':'query','format':'json','prop':'extracts|pageimages|pageviews','exintro':1,'explaintext':1,'exlimit':20,'piprop':'thumbnail|name|original','pithumbsize':600,'pilicense':'any','pilimit':20,'pvipdays':30,'titles':'|'.join(chunk),'redirects':1}
    cont={}
    for _ in range(6):
        r=get('https://ru.wikipedia.org/w/api.php',{**params,**cont}) or {}
        back={x['to']:x['from'] for x in r.get('query',{}).get('redirects',[])};back.update({x['to']:back.get(x['from'],x['from']) for x in r.get('query',{}).get('normalized',[])})
        for p in r.get('query',{}).get('pages',{}).values():
            q=byTitle.get(p.get('title')) or byTitle.get(back.get(p.get('title'),''))
            if not q:continue
            b=books[q]
            if p.get('extract'):b['intro']=p['extract']
            if p.get('thumbnail'):b['wimg']={'u':p['thumbnail']['source'].split('?')[0],'w':p['thumbnail'].get('width'),'h':p['thumbnail'].get('height'),'name':p.get('pageimage')}
            if p.get('pageviews'):b['views']=sum(v or 0 for v in p['pageviews'].values())
        if 'continue' not in r:break
        cont=r['continue']
    if n%1000==0:print('ruwiki',n,flush=True)
# 4) Commons: sizes of the Wikidata pictures (a cover is upright; portraits of authors and title pages are filtered later)
withImg=[q for q,b in books.items() if b.get('img')]
for n in range(0,len(withImg),40):
    chunk=withImg[n:n+40];r=get('https://commons.wikimedia.org/w/api.php',{'action':'query','format':'json','prop':'imageinfo','iiprop':'size|url|mime','iiurlwidth':600,'titles':'|'.join('File:'+books[q]['img'] for q in chunk)}) or {}
    norm={x['to']:x['from'] for x in r.get('query',{}).get('normalized',[])}
    for p in r.get('query',{}).get('pages',{}).values():
        ii=(p.get('imageinfo') or [None])[0]
        if not ii:continue
        t=norm.get(p['title'],p['title'])[5:]
        for q in chunk:
            if books[q]['img']==t or books[q]['img'].replace('_',' ')==p['title'][5:]:books[q]['cimg']={'u':ii.get('thumburl') or ii.get('url'),'w':ii.get('width'),'h':ii.get('height'),'mime':ii.get('mime')}
print('commons',len(withImg),flush=True)
# 5) Open Library: covers, subjects, first sentence of the work
withOl=[q for q,b in books.items() if b.get('ol')]
for n,q in enumerate(withOl):
    w=get('https://openlibrary.org/works/%s.json'%books[q]['ol'],tries=2,timeout=30)
    if w:
        books[q]['olCovers']=[c for c in (w.get('covers') or []) if isinstance(c,int) and c>0][:3];books[q]['olSubjects']=(w.get('subjects') or [])[:25]
    if n%500==0:print('openlibrary',n,len(withOl),flush=True)
    time.sleep(.25)
# books Open Library knows only by name: one search each for the most popular ones without a cover so far
need=[q for q,b in books.items() if not b.get('olCovers')][:2500]
for n,q in enumerate(need):
    b=books[q];title=b.get('orig') or b.get('en');auth=(names.get((b.get('author') or [''])[0]) or {}).get('en')
    if not title:continue
    r=get('https://openlibrary.org/search.json',{'title':title,**({'author':auth} if auth else {}),'limit':3,'fields':'key,title,cover_i,author_name,edition_count'},tries=2,timeout=30)
    d=next((d for d in (r or {}).get('docs',[]) if d.get('cover_i')),None)
    if d:b['olCovers']=[d['cover_i']];b['olFound']=d['key'].rsplit('/',1)[1]
    if n%500==0:print('ol search',n,len(need),flush=True)
    time.sleep(.3)
st=collections.Counter()
for b in books.values():
    up=lambda i:i and i.get('w') and i.get('h') and 1.2<=i['h']/i['w']<=1.85
    cover=bool(b.get('olCovers')) or up(b.get('cimg')) or up(b.get('wimg'));st['cover']+=cover;st['ol']+=bool(b.get('olCovers'));st['commons']+=bool(up(b.get('cimg')));st['ruwiki']+=bool(up(b.get('wimg')))
    st['intro300']+=len(b.get('intro',''))>=300;st['intro']+=bool(b.get('intro'));st['author']+=bool(b.get('author'));st['genre']+=bool(b.get('genre'));st['year']+='year' in b;st['gr']+=bool(b.get('grWork') or b.get('grBook'));st['cover+intro300']+=cover and len(b.get('intro',''))>=300;st['cover+author']+=bool(cover and b.get('author'))
print(dict(st),flush=True)
json.dump({'stats':dict(st),'names':names,'books':books},open('raw.json','w'),ensure_ascii=False,separators=(',',':'))
