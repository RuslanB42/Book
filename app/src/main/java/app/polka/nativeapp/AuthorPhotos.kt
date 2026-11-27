package app.polka.nativeapp

import android.graphics.*
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Real portraits of authors (approved plan): found through Wikidata — a human whose occupation is writing; the
 * portrait is the one on Wikimedia Commons or, when Commons has none (many living writers), the author's photo on
 * Open Library, linked from the same Wikidata entry. When several writers share the name, the one who wrote a book
 * from the library is taken, and when that still leaves doubt there is no photo (initials are better than a
 * stranger's face). A 256-px
 * square copy is kept on the phone once per author together with its credit line (the photographer and the licence).
 * Searched in the background for books added from now on and for the favourite author; a miss is retried at most
 * once a month. The reader's own photo, or a removed one, is never overwritten.
 */
internal object AuthorPhotos {
    private const val UA="GlyphReader/1.0 (Android ebook reader; https://github.com/RuslanB42/Book)"
    private const val MONTH=30L*24*3600*1000
    /** The way photos are looked for; a miss of an older way is looked for again. */
    private const val SEARCH=2
    private val pool=Executors.newSingleThreadExecutor()
    private val memory=LruCache<String,Bitmap>(24)
    private val busy=HashSet<String>()
    private var index:JSONObject?=null
    // writer, novelist, poet, playwright, author, children's writer, prosaist, essayist, science-fiction writer,
    // screenwriter, short-story writer, literary critic, journalist-writer
    private val writing=setOf("Q36180","Q6625963","Q49757","Q214917","Q482980","Q4853732","Q12144794","Q11774202","Q18844224","Q28389","Q15949613","Q4263842","Q1930187")

    private fun dir(a:MainActivity)=File(a.filesDir,"authors").apply{mkdirs()}
    private fun key(name:String)=name.lowercase().replace('ё','е').split(Regex("[\\s.,]+")).filter{it.isNotBlank()}.sorted().joinToString(" ").let{n->
        java.security.MessageDigest.getInstance("SHA-1").digest(n.toByteArray()).joinToString(""){"%02x".format(it)}.take(16)}
    @Synchronized private fun idx(a:MainActivity):JSONObject{index?.let{return it}
        val f=File(dir(a),"index.json");val j=try{if(f.exists())JSONObject(f.readText()) else JSONObject()}catch(e:Exception){JSONObject()};index=j;return j}
    @Synchronized private fun save(a:MainActivity){val f=File(dir(a),"index.json");val tmp=File(dir(a),"index.tmp");tmp.writeText(idx(a).toString());tmp.renameTo(f)}
    @Synchronized fun info(a:MainActivity,name:String):JSONObject?=idx(a).optJSONObject(key(name))

    /** A single author (not «Стругацкие», not «Ильф и Петров»). */
    private fun single(name:String)=name.isNotBlank()&&!Regex("(?i)(,|&| и | and |;)").containsMatchIn(name)&&!name.trim().endsWith("ие")&&name.trim().split(Regex("\\s+")).size in 2..4

    /** The kept photo, if there is one (from memory, or read from the phone). */
    fun bitmap(a:MainActivity,name:String):Bitmap?{val k=key(name);memory.get(k)?.let{return it}
        val f=File(dir(a),"$k.jpg");if(!f.exists())return null
        return BitmapFactory.decodeFile(f.path)?.also{memory.put(k,it)}}

    /** Makes sure the author's photo is looked for; [done] runs on the main thread when a new one arrives. */
    fun ensure(a:MainActivity,name:String,title:String?,done:((Bitmap)->Unit)?=null){
        val n=name.trim();if(!single(n))return
        val k=key(n);val e=info(a,n)
        if(e!=null&&(e.optBoolean("manual")||e.optBoolean("removed")||File(dir(a),"$k.jpg").exists()))return
        // a miss is tried again after a month, or at once when it was missed before the photos came from Open Library too
        if(e!=null&&e.optBoolean("miss")&&e.optInt("v")>=SEARCH&&System.currentTimeMillis()-e.optLong("at")<MONTH)return
        synchronized(busy){if(!busy.add(k))return}
        pool.execute{try{val got=find(a,n,title);if(got!=null){memory.put(k,got);done?.let{d->a.runOnUiThread{d(got)}}}}catch(_:Exception){}finally{synchronized(busy){busy.remove(k)}}}
    }

    /** For books added from now on: one quiet pass in the background. */
    fun prefetch(a:MainActivity,books:List<JSONObject>){
        val prefs=a.getSharedPreferences("authors",0);var since=prefs.getLong("since",0L)
        if(since==0L){since=System.currentTimeMillis();prefs.edit().putLong("since",since).apply()}
        books.filter{it.optLong("addedAt")>=since}.forEach{b->b.optJSONArray("authors")?.let{arr->if(arr.length()==1)ensure(a,arr.optString(0),b.str("title"))}}
    }

    // ───── network ─────
    private fun get(url:String):String{val c=URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent",UA);c.connectTimeout=12000;c.readTimeout=15000
        try{if(c.responseCode!=200)error("HTTP ${c.responseCode}");return c.inputStream.bufferedReader().readText()}finally{c.disconnect()}}
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    // no network is not a miss: the error goes up and nothing is written down, so the next look tries again
    private fun search(q:String,lang:String):List<String> =
        JSONObject(get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=7&language=$lang&uselang=$lang&search=${enc(q)}"))
            .optJSONArray("search")?.let{arr->(0 until arr.length()).map{arr.getJSONObject(it).optString("id")}}.orEmpty()
    private fun entities(ids:List<String>):JSONObject=if(ids.isEmpty())JSONObject() else
        JSONObject(get("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=claims&ids=${ids.joinToString("|")}")).optJSONObject("entities")?:JSONObject()
    private fun claimIds(e:JSONObject,p:String):List<String> = e.optJSONObject("claims")?.optJSONArray(p)?.let{arr->(0 until arr.length()).mapNotNull{arr.getJSONObject(it).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")?.let{v->if(v is JSONObject)v.optString("id") else v.toString()}}}.orEmpty()

    private fun find(a:MainActivity,name:String,title:String?):Bitmap?{
        val parts=name.split(Regex("\\s+"));val swapped=if(parts.size>=2)(parts.drop(1)+parts.first()).joinToString(" ") else name
        val ids=(listOf(name,swapped).distinct().flatMap{q->search(q,"ru")+search(q,"en")}).distinct().take(12)
        val ents=entities(ids)
        val writers=ids.filter{id->val e=ents.optJSONObject(id)?:return@filter false
            "Q5" in claimIds(e,"P31")&&claimIds(e,"P106").any{it in writing}}
        val chosen=when{
            writers.size==1->writers[0]
            writers.size>1&&!title.isNullOrBlank()->{
                // the one who wrote a book from the library
                val works=(search(title,"ru")+search(title,"en")).distinct().take(10);val we=entities(works)
                val authors=works.flatMap{claimIds(we.optJSONObject(it)?:JSONObject(),"P50")}.toSet()
                writers.filter{it in authors}.singleOrNull()}
            else->null}
        val k=key(name);val entry=JSONObject().put("name",name).put("at",System.currentTimeMillis()).put("v",SEARCH)
        fun miss():Bitmap?{synchronized(this){idx(a).put(k,entry.put("miss",true));save(a)};return null}
        if(chosen==null)return miss()
        val e=ents.getJSONObject(chosen)
        val file=claimIds(e,"P18").firstOrNull()
        // Commons first; without a portrait there, the author's photo on Open Library (none there: a 404, so a miss)
        val ol=if(file==null)claimIds(e,"P648").firstOrNull{it.matches(Regex("OL\\d+A"))} else null
        val url=when{file!=null->"https://commons.wikimedia.org/wiki/Special:FilePath/${enc(file).replace("+","%20")}?width=320"
            ol!=null->"https://covers.openlibrary.org/a/olid/$ol-L.jpg?default=false"
            else->return miss()}
        val bytes=download(url)?:return miss()
        val src=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.takeIf{it.width>=48&&it.height>=48}?:return miss()
        val square=crop(src,256);src.recycle()
        File(dir(a),"$k.jpg").outputStream().use{square.compress(Bitmap.CompressFormat.JPEG,88,it)}
        if(file==null){synchronized(this){idx(a).put(k,entry.put("qid",chosen).put("file","openlibrary:$ol").put("artist","Open Library"));save(a)};return square}
        // the credit line: who took the photo and under what licence
        var artist="";var licence=""
        try{val meta=JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=extmetadata&titles=${enc("File:$file")}"))
            .optJSONObject("query")?.optJSONObject("pages")?.let{p->p.optJSONObject(p.keys().next())}?.optJSONArray("imageinfo")?.optJSONObject(0)?.optJSONObject("extmetadata")
            artist=android.text.Html.fromHtml(meta?.optJSONObject("Artist")?.optString("value").orEmpty(),0).toString().trim().replace(Regex("\\s+")," ")
            licence=meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()}catch(_:Exception){}
        synchronized(this){idx(a).put(k,entry.put("qid",chosen).put("file",file).put("artist",artist).put("licence",licence));save(a)}
        return square
    }
    /** The picture at [url]; null when there is none (404). */
    private fun download(url:String):ByteArray?{var u=url
        // Open Library answers with a redirect to its archive host
        repeat(4){val c=URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent",UA);c.connectTimeout=12000;c.readTimeout=20000;c.instanceFollowRedirects=true
            try{val code=c.responseCode
                if(code in 300..399){u=URL(URL(u),c.getHeaderField("Location")?:error("no location")).toString();return@repeat}
                if(code==404)return null
                if(code!=200)error("HTTP $code");return c.inputStream.readBytes()}finally{c.disconnect()}}
        error("redirects")}
    /** The face is usually in the upper middle of a portrait: a square from there. */
    private fun crop(src:Bitmap,size:Int):Bitmap{val s=minOf(src.width,src.height);val x=(src.width-s)/2;val y=if(src.height>src.width)((src.height-s)*.18f).toInt() else 0
        val out=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Canvas(out).drawBitmap(src,Rect(x,y,x+s,y+s),Rect(0,0,size,size),Paint(Paint.FILTER_BITMAP_FLAG));return out}

    // ───── the reader's own choice ─────
    fun setOwn(a:MainActivity,name:String,bytes:ByteArray):Boolean{val src=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:return false
        val k=key(name);val sq=crop(src,256);src.recycle();File(dir(a),"$k.jpg").outputStream().use{sq.compress(Bitmap.CompressFormat.JPEG,90,it)};memory.put(k,sq)
        synchronized(this){idx(a).put(k,JSONObject().put("name",name).put("manual",true).put("at",System.currentTimeMillis()));save(a)};return true}
    fun remove(a:MainActivity,name:String){val k=key(name);File(dir(a),"$k.jpg").delete();memory.remove(k)
        synchronized(this){idx(a).put(k,JSONObject().put("name",name).put("removed",true).put("at",System.currentTimeMillis()));save(a)}}
    /** Back to the found photo: forgets the reader's choice and looks again. */
    fun reset(a:MainActivity,name:String,title:String?,done:((Bitmap)->Unit)?){val k=key(name);File(dir(a),"$k.jpg").delete();memory.remove(k)
        synchronized(this){idx(a).remove(k);save(a)};ensure(a,name,title,done)}
    fun credit(a:MainActivity,name:String):String?{val e=info(a,name)?:return null;if(e.optBoolean("manual"))return null
        val who=e.optString("artist").take(80);val lic=e.optString("licence");if(e.optString("file").isBlank())return null
        return listOf(if(who.isNotBlank())"Фото: $who" else "Фото: Wikimedia Commons",lic).filter{it.isNotBlank()}.joinToString(", ")}
}

/** The author's round portrait: the kept photo, or the initials on the author's own colour until (and unless) there
 *  is one; a new photo fades in. A tap shows the photo's credit, a long press lets the reader choose or remove it. */
internal class AuthorPortrait(private val a:MainActivity,private val name:String,private val title:String?):android.view.View(a){
    private val letters=name.split(' ','-').filter{it.isNotBlank()}.let{w->((w.firstOrNull()?.take(1)?:"")+(if(w.size>1)w.last().take(1) else "")).uppercase()}
    private val hue=(kotlin.math.abs(name.hashCode())%360).toFloat()
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=PolkaFonts.serif(a,true)}
    private val bp=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var photo:Bitmap?=AuthorPhotos.bitmap(a,name);private var fade=1f
    private val clip=Path();private val dst=RectF()
    init{contentDescription="Портрет: $name";isClickable=true;isLongClickable=true
        if(photo==null)AuthorPhotos.ensure(a,name,title){b->photo=b;if(isAttachedToWindow){fade=0f;android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.EMPHASIS;addUpdateListener{fade=it.animatedValue as Float;invalidate()};start()}} else invalidate()}
        setOnClickListener{AuthorPhotos.credit(a,name)?.let{Notice.short(a,Notice.Kind.OK,it)}}
        setOnLongClickListener{menu();true}}
    private fun menu(){val own=AuthorPhotos.info(a,name)?.let{it.optBoolean("manual")||it.optBoolean("removed")}==true
        val items=mutableListOf("Выбрать своё фото");val icons=mutableListOf("pen")
        if(photo!=null){items+="Убрать фото";icons+="trash"}
        if(own){items+="Найти фото снова";icons+="undo"}
        a.dialog().setTitle(name).setItemIcons(*icons.toTypedArray()).setItems(items.toTypedArray()){_,i->
            when(items[i]){
                "Выбрать своё фото"->a.pickAuthorPhoto{bytes->var ok=false;a.work({ok=AuthorPhotos.setOwn(a,name,bytes)}){if(ok){photo=AuthorPhotos.bitmap(a,name);fade=1f;invalidate()} else Notice.short(a,Notice.Kind.ERROR,"Не удалось открыть изображение")}}
                "Убрать фото"->{AuthorPhotos.remove(a,name);photo=null;invalidate()}
                else->{photo=null;invalidate();AuthorPhotos.reset(a,name,title){b->photo=b;fade=1f;invalidate()}}}}.show()}
    override fun onDraw(c:Canvas){val s=kotlin.math.min(width,height).toFloat();val cx=width/2f;val cy=height/2f
        val top=Color.HSVToColor(floatArrayOf(hue,.45f,if(a.light).78f else .72f));val bottom=Color.HSVToColor(floatArrayOf((hue+20)%360,.6f,if(a.light).6f else .42f))
        p.shader=LinearGradient(cx-s/2,cy-s/2,cx+s/2,cy+s/2,top,bottom,Shader.TileMode.CLAMP);c.drawCircle(cx,cy,s/2,p);p.shader=null
        val b=photo
        if(b==null||fade<1f){t.textSize=s*.36f;t.color=Color.HSVToColor(floatArrayOf(hue,.08f,1f));t.alpha=((1f-(if(b==null)0f else fade))*255).toInt();c.drawText(letters,cx,cy-(t.descent()+t.ascent())/2,t)}
        if(b!=null){clip.rewind();clip.addCircle(cx,cy,s/2,Path.Direction.CW);c.save();c.clipPath(clip);dst.set(cx-s/2,cy-s/2,cx+s/2,cy+s/2);bp.alpha=(fade*255).toInt();c.drawBitmap(b,null,dst,bp);c.restore()}}
}
