"""Find freely licensed ambience recordings on Wikimedia Commons (CC0 / public domain only, no attribution needed
for a store app), long enough to loop, and download the best candidates for each sound."""
import json,requests,time,os,re
S=requests.Session();S.headers['User-Agent']='PolkaSounds/0.1 (https://github.com/RuslanB42/Book)'
API='https://commons.wikimedia.org/w/api.php'
Q={'fire':['fireplace crackling','crackling fire','campfire sound','wood fire burning','fireplace'],
   'rain':['rain sound','rain on roof','rain ambience','rainfall recording','heavy rain'],
   'sea':['sea waves','ocean waves beach','surf sound','waves crashing shore','sea shore'],
   'forest':['forest ambience birds','forest birdsong','birds singing forest','woodland ambience','dawn chorus']}
FREE=re.compile(r'^(cc0|cc-zero|public domain|pd|pdm)',re.I)
def search(q):
    out=[]
    for off in (0,50):
        r=S.get(API,params={'action':'query','format':'json','generator':'search','gsrsearch':q+' filetype:audio','gsrnamespace':6,'gsrlimit':50,'gsroffset':off,
            'prop':'imageinfo','iiprop':'url|size|mime|mediatype|extmetadata'},timeout=60).json()
        for p in r.get('query',{}).get('pages',{}).values():
            ii=(p.get('imageinfo') or [{}])[0];m=ii.get('extmetadata',{})
            lic=m.get('LicenseShortName',{}).get('value','');
            out.append({'title':p['title'],'url':ii.get('url'),'size':ii.get('size'),'mime':ii.get('mime'),'license':lic,
              'desc':re.sub('<[^>]+>','',m.get('ImageDescription',{}).get('value',''))[:300],'artist':re.sub('<[^>]+>','',m.get('Artist',{}).get('value',''))[:80],
              'duration':None})
        time.sleep(1)
    return out
res={}
for k,qs in Q.items():
    seen={};
    for q in qs:
        for c in search(q):
            if FREE.match(c['license'].strip()) and c['url']:seen[c['title']]=c
    # durations
    titles=list(seen)
    for i in range(0,len(titles),40):
        r=S.get(API,params={'action':'query','format':'json','titles':'|'.join(titles[i:i+40]),'prop':'videoinfo','viprop':'size|mime|derivatives','videoinfo':1},timeout=60).json()
        for p in r.get('query',{}).get('pages',{}).values():
            pass
    res[k]=list(seen.values());print(k,len(res[k]),flush=True)
json.dump(res,open('candidates.json','w'),ensure_ascii=False,indent=1)
# download all candidates under 40 MB, convert to wav to measure, keep metadata
os.makedirs('raw',exist_ok=True)
for k,lst in res.items():
    for n,c in enumerate(lst):
        if (c['size'] or 0)>40e6:continue
        ext=c['url'].rsplit('.',1)[-1].lower();fn=f'raw/{k}-{n:02d}.{ext}'
        try:
            b=S.get(c['url'],timeout=120).content;open(fn,'wb').write(b);c['file']=fn
        except Exception as e:print('dl',e)
        time.sleep(.5)
json.dump(res,open('candidates.json','w'),ensure_ascii=False,indent=1)
