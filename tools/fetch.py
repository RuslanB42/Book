"""Download the chosen freely licensed recordings (with licence checks) and report on each: length, loudness over
time (thunder, voices or cars show up as spikes) and a spectrogram."""
import requests,re,time,json,os,subprocess
S=requests.Session();S.headers['User-Agent']='PolkaSounds/0.2 (https://github.com/RuslanB42/Book; contact via GitHub)'
API='https://commons.wikimedia.org/w/api.php'
C={'rain':['_','_','_','Light Rain Distant Thunder July 5th 2016.wav'],'sea':['Ocean Waves on a Tropical Beach.ogg'],'forest':['_','_','Waidachswald Oberschefflenz Abend 20250616 2005.ogg']}
os.makedirs('src',exist_ok=True);meta={}
def get(url,**kw):
    for k in range(6):
        r=S.get(url,timeout=180,**kw)
        if r.status_code==200 and 'text/html' not in r.headers.get('content-type',''):return r
        if r.status_code==404:return None
        print('retry',r.status_code,url[:90],flush=True);time.sleep(5)
    return None
for kind,titles in C.items():
    for n,t in enumerate(titles):
        if t=='_':continue
        q=S.get(API,params={'action':'query','format':'json','titles':'File:'+t,'prop':'imageinfo','iiprop':'url|size|extmetadata'},timeout=60).json()
        p=list(q['query']['pages'].values())[0];ii=(p.get('imageinfo') or [{}])[0];m=ii.get('extmetadata',{})
        lic=m.get('LicenseShortName',{}).get('value','');url=ii.get('url')
        if not url:print('missing',t);continue
        ext=url.rsplit('.',1)[-1].lower();fn=f'src/{kind}-{n}.{ext}'
        r=get(url)
        if not r:continue
        open(fn,'wb').write(r.content)
        meta[fn]={'title':t,'license':lic,'artist':re.sub('<[^>]+>','',m.get('Artist',{}).get('value',''))[:120],'url':'https://commons.wikimedia.org/wiki/File:'+t.replace(' ','_')}
        print(fn,lic,len(r.content),flush=True);time.sleep(1)
# OpenGameArt: «fireplace sound loop» (CC0)
for kind,page in (('fire','https://opengameart.org/content/fireplace-sound-loop'),):
    h=S.get(page,timeout=60).text
    for i,u in enumerate(dict.fromkeys([u for u in re.findall(r'https://opengameart\.org/sites/default/files/[^"\']+\.wav',h) if '/styles/' not in u])):
        r=get(u)
        if r:fn=f'src/{kind}-oga4.'+u.rsplit('.',1)[-1];open(fn,'wb').write(r.content);meta[fn]={'title':u,'license':'CC0 (OpenGameArt)','url':page};print(fn,len(r.content),flush=True)
json.dump(meta,open('src/meta.json','w'),ensure_ascii=False,indent=1)
