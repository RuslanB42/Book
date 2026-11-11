"""Seamless loops from the chosen recordings: a stretch without stray events, the end blended into the start
(equal-power crossfade), the same loudness for all four, OGG Vorbis for the app."""
import numpy as np,subprocess,json,os
SR=44100
def load(fn,start,length):
    raw=subprocess.run(['ffmpeg','-v','error','-ss',str(start),'-t',str(length),'-i',fn,'-ac','2','-ar',str(SR),'-f','f32le','-'],capture_output=True,check=True).stdout
    return np.frombuffer(raw,np.float32).reshape(-1,2).copy()
def loop(x,fade):
    n=int(fade*SR);body=x[:-n].copy();tail=x[-n:]
    t=np.linspace(0,np.pi/2,n)[:,None]
    body[:n]=body[:n]*np.sin(t)+tail*np.cos(t)
    return body
J=json.load(open('src/meta.json'))
def src(prefix):return next(k for k in J if os.path.basename(k).startswith(prefix+'.') or os.path.basename(k)==prefix)
plan={'rain':(src('rain-3'),12,92,4),'sea':(src('sea-0'),60,124,6),'forest':(src('forest-2'),22,124,5),'fire':(src('fire-oga4'),0,29.26,0)}
os.makedirs('out',exist_ok=True);credits={}
for k,(fn,s,l,f) in plan.items():
    x=load(fn,s,l);y=loop(x,f) if f>0 else x
    # ease the very ends by a few ms so the wrap never clicks
    e=int(.004*SR);w=np.linspace(0,1,e)[:,None]
    if f==0:y[:e]=y[:e]*w+y[-e:][::-1]*0;y[-e:]=y[-e:]*w[::-1]
    open('tmp.raw','wb').write(y.astype(np.float32).tobytes())
    subprocess.run(['ffmpeg','-v','error','-y','-f','f32le','-ar',str(SR),'-ac','2','-i','tmp.raw','-af','loudnorm=I=-24:TP=-2:LRA=11','-ar',str(SR),'-c:a','libvorbis','-q:a','3',f'out/{k}.ogg'],check=True)
    credits[k]={**J[fn],'segment':[s,l],'crossfade':f}
    print(k,os.path.getsize(f'out/{k}.ogg'),flush=True)
json.dump(credits,open('out/credits.json','w'),ensure_ascii=False,indent=1)
