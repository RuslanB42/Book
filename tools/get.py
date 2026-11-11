import requests,json,os,io,time
from PIL import Image,ImageDraw
os.makedirs("out/full",exist_ok=True)
Q={"fire":["fireplace","fire logs","campfire night","bonfire","flames","fire wood burning"],
   "rain":["rain window","raindrops","rain drops glass","rainy window","rain","wet window"],
   "forest":["forest sunset","pine forest","forest dusk","woods evening","forest sunlight trees"]}
H={"User-Agent":"PolkaReader/1.0 (photo picker)"}
meta={}
def wiki(q):
    r=requests.get("https://commons.wikimedia.org/w/api.php",params={"action":"query","generator":"search","gsrsearch":"filetype:bitmap "+q,"gsrnamespace":6,"gsrlimit":30,"prop":"imageinfo","iiprop":"url|size|extmetadata","iiurlwidth":1000,"format":"json"},headers=H,timeout=30).json()
    out=[]
    for p in (r.get("query",{}).get("pages",{}) or {}).values():
        ii=p["imageinfo"][0];em=ii.get("extmetadata",{});lic=em.get("LicenseShortName",{}).get("value","")
        if not any(k in lic.lower() for k in ["cc0","public domain","pd"]):continue
        if ii["width"]<1200 or ii["width"]<ii["height"]:continue
        out.append({"src":"wikimedia","url":ii["thumburl"],"page":ii["descriptionurl"],"license":lic,"creator":em.get("Artist",{}).get("value","")[:120]})
    return out
def ov(q):
    try:
        r=requests.get("https://api.openverse.org/v1/images/",params={"q":q,"license":"cc0,pdm","page_size":20,"size":"large"},headers=H,timeout=30).json()
    except Exception as e:print("ov",e);return []
    print(q,r.get("result_count"),r.get("detail"))
    return [{"src":"openverse/"+x.get("source",""),"url":x["url"],"page":x.get("foreign_landing_url"),"license":x.get("license"),"creator":x.get("creator")} for x in r.get("results",[])]
for k,qs in Q.items():
    cands=[];seen=set()
    for q in qs:
        for c in ov(q)+wiki(q):
            if c["url"] in seen:continue
            seen.add(c["url"]);cands.append(c)
        time.sleep(5)
    got=[]
    for c in cands:
        if len(got)>=30:break
        try:
            b=requests.get(c["url"],headers=H,timeout=30).content;im=Image.open(io.BytesIO(b)).convert("RGB")
            if im.width<900 or im.width<im.height:continue
            im.thumbnail((1000,1000));n=f"{k}{len(got):02d}.jpg";im.save("out/full/"+n,quality=88);c["file"]=n;got.append(c)
        except Exception as e:print("dl",e)
    meta[k]=got
    # contact sheet
    tw,th=300,196;cols=6;rows=(len(got)+cols-1)//cols or 1
    sh=Image.new("RGB",(cols*tw,rows*(th+24)),(20,20,22));d=ImageDraw.Draw(sh)
    for i,c in enumerate(got):
        im=Image.open("out/full/"+c["file"]);r=max(tw/im.width,th/im.height);im=im.resize((int(im.width*r)+1,int(im.height*r)+1))
        l=(im.width-tw)//2;t=(im.height-th)//2;sh.paste(im.crop((l,t,l+tw,t+th)),((i%cols)*tw,(i//cols)*(th+24)))
        d.text(((i%cols)*tw+6,(i//cols)*(th+24)+th+4),f"{i} {c['src'][:22]} {c['license']}",fill=(220,220,220))
    sh.save(f"out/{k}.jpg",quality=85)
json.dump(meta,open("out/meta2.json","w"),ensure_ascii=False,indent=1)
print({k:len(v) for k,v in meta.items()})
