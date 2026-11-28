package app.polka.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** Line icons of the selection menu and the lookup cards, drawn on a 24 grid with one stroke weight (approved set). */
internal class LineIcon(c:Context,private var kind:String,var ink:Int):View(c){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    var dot:Int?=null; set(v){field=v;invalidate()}
    var size=24f
    fun set(k:String,color:Int){kind=k;ink=color;invalidate()}
    override fun onDraw(c:Canvas){val px=size*resources.displayMetrics.density;val s=px/24f
        c.save();c.translate((width-px)/2f,(height-px)/2f);c.scale(s,s);p.color=ink;p.strokeWidth=1.8f;p.style=Paint.Style.STROKE
        if(kind=="colordot"){p.style=Paint.Style.FILL;p.color=dot?:ink;c.drawCircle(12f,12f,8f,p);p.style=Paint.Style.STROKE;p.color=Ui.outline(false);p.strokeWidth=1f;c.drawCircle(12f,12f,8f,p);c.restore();return}
        IosIcons.draw(c,kind,ink);c.restore()}
}

/** The offline dictionary: Russian and English from Wiktionary (CC BY-SA), one SQLite file downloaded once. */
internal object Dictionary{
    const val URL_DB="https://github.com/RuslanB42/Book/releases/download/dict-v1/dict.db.gz"
    private var db:SQLiteDatabase?=null
    fun file(c:Context)=File(File(c.filesDir,"dict").apply{mkdirs()},"dict.db")
    fun ready(c:Context)=file(c).let{it.exists()&&it.length()>1_000_000}
    @Volatile var cancel=false
    /** Downloads and unpacks the base; [progress] gets (bytes so far, total) on the worker thread. */
    fun download(c:Context,progress:(Long,Long)->Unit){cancel=false
        var url=URL(URL_DB);var conn:HttpURLConnection
        var hops=0
        while(true){conn=url.openConnection() as HttpURLConnection;conn.instanceFollowRedirects=false;conn.connectTimeout=15000;conn.readTimeout=30000
            val code=conn.responseCode;if(code in 300..399&&hops<6){url=URL(url,conn.getHeaderField("Location"));conn.disconnect();hops++;continue}
            if(code!=200)throw IllegalStateException("Не удалось скачать словарь (код $code)");break}
        val total=conn.contentLengthLong;val tmp=File(file(c).parentFile,"dict.part")
        val counting=object:java.io.FilterInputStream(conn.inputStream){var n=0L;var last=0L
            override fun read(b:ByteArray,off:Int,len:Int):Int{if(cancel)throw InterruptedException();val r=super.read(b,off,len);if(r>0){n+=r;if(n-last>200_000||n==total){last=n;progress(n,total)}};return r}}
        try{GZIPInputStream(counting,1 shl 16).use{inp->tmp.outputStream().use{inp.copyTo(it,1 shl 16)}}
            synchronized(this){db?.close();db=null;if(!tmp.renameTo(file(c))){tmp.copyTo(file(c),true);tmp.delete()}}}
        finally{conn.disconnect();if(tmp.exists()&&cancel)tmp.delete()}}
    private fun open(c:Context):SQLiteDatabase?=synchronized(this){db?:if(ready(c))SQLiteDatabase.openDatabase(file(c).path,null,SQLiteDatabase.OPEN_READONLY).also{db=it} else null}
    fun key(w:String)=w.replace("́","").replace("̀","").lowercase(Locale.ROOT).replace('ё','е').trim()
    /** Every form the dictionary knows for [word] in [lang] (as lookup keys), for underlining it in books. */
    fun forms(c:Context,word:String,lang:String):List<String>{val d=open(c)?:return emptyList();val out=LinkedHashSet<String>()
        val ids=ArrayList<Long>()
        d.rawQuery("SELECT DISTINCT k.id FROM k JOIN e ON e.id=k.id WHERE k.key=? AND e.lang=? LIMIT 6",arrayOf(key(word),lang)).use{cur->while(cur.moveToNext())ids.add(cur.getLong(0))}
        if(ids.isEmpty())return emptyList()
        // k has no index on id: one pass over the table for all ids at once
        d.rawQuery("SELECT DISTINCT key FROM k WHERE id IN (${ids.joinToString(",")}) LIMIT 80",null).use{cur->while(cur.moveToNext())out.add(cur.getString(0))}
        return out.filter{it.length>1}}
    /** Entries for a word as it stands in the text, in the language of its letters first. */
    fun lookup(c:Context,word:String):List<JSONObject>{val d=open(c)?:return emptyList()
        val clean=word.trim().trim{!it.isLetterOrDigit()&&it!='-'&&it!='\''}
        val keys=linkedSetOf(key(clean),key(clean.replace("’","'")))
        val out=ArrayList<Pair<String,JSONObject>>()
        for(k in keys){d.rawQuery("SELECT e.lang,e.data FROM k JOIN e ON e.id=k.id WHERE k.key=? LIMIT 6",arrayOf(k)).use{cur->while(cur.moveToNext())out.add(cur.getString(0) to JSONObject(cur.getString(1)))};if(out.isNotEmpty())break}
        val cyr=clean.any{it in 'а'..'я'||it in 'А'..'Я'||it=='ё'||it=='Ё'}
        // the language of the letters first, then the more common word (so «ran» finds «run» before the rare noun «ran»)
        return out.sortedWith(compareBy<Pair<String,JSONObject>>{if((it.first=="ru")==cyr)0 else 1}.thenByDescending{it.second.optDouble("f",0.0)}).map{it.second.put("lang",it.first)}}
    /** A random sample of [lang] (word, meaning, part of speech), from which an exercise picks its wrong choices:
     *  plain lowercase words with a short meaning of their own (no «действие по значению…», no references). */
    fun sample(c:Context,lang:String,n:Int):List<Triple<String,String,String>>{val d=open(c)?:return emptyList();val out=ArrayList<Triple<String,String,String>>()
        // each language lies in its own run of rows: a random row is picked inside that run, so every query finds its
        // row at once (picked from the whole table, a row past the run scanned the rest of it — a lesson waited minutes)
        val (low,high)=span(d,lang)?:return out
        val rnd=java.util.Random();val seen=HashSet<String>();var tries=0;val until=android.os.SystemClock.uptimeMillis()+1500
        while(out.size<n&&tries<n*3&&android.os.SystemClock.uptimeMillis()<until){tries++
            d.rawQuery("SELECT data FROM e WHERE rowid>=? AND lang=? LIMIT 1",arrayOf((low+(rnd.nextDouble()*(high-low+1)).toLong()).toString(),lang)).use{cur->if(cur.moveToFirst()){val e=try{JSONObject(cur.getString(0))}catch(_:Exception){null}
                val w=e?.optString("w")?.replace("́","").orEmpty();val tr=e?.optJSONArray("t")
                val m=Words.plain(if(tr!=null&&tr.length()>0)(0 until minOf(tr.length(),3)).joinToString(", "){tr.getString(it)} else e?.optJSONArray("s")?.optJSONArray(0)?.optString(0).orEmpty())
                if(e!=null&&w.length in 3..20&&w.all{it.isLetter()||it=='-'}&&w.none{it.isUpperCase()}&&m.length in 6..120&&!Words.vague(m)&&seen.add(w))out+=Triple(w,m,e.optString("p"))}}}
        return out}
    private val spans=HashMap<String,Pair<Long,Long>>()
    /** The first and last row of [lang] (found once). */
    private fun span(d:SQLiteDatabase,lang:String):Pair<Long,Long>?=synchronized(spans){spans[lang]?:d.rawQuery("SELECT MIN(rowid),MAX(rowid) FROM e WHERE lang=?",arrayOf(lang)).use{
        if(it.moveToFirst()&&!it.isNull(0))(it.getLong(0) to it.getLong(1)).also{p->spans[lang]=p} else null}}
}

/**
 * Names and things a dictionary does not know (gods, places, peoples, objects of a myth): the article of Wikipedia
 * that fits the word in its sentence best, its one-line description and its opening. Needs the internet; what was
 * found is kept, so the second time it opens at once and without it.
 */
internal object Encyclopedia{
    private const val UA="Glyph (Android e-book reader; https://github.com/RuslanB42/Book)"
    private fun get(url:String):String{val c=URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent",UA);c.connectTimeout=8000;c.readTimeout=10000
        try{if(c.responseCode!=200)throw java.io.IOException("код ${c.responseCode}");return c.inputStream.bufferedReader().readText()}finally{c.disconnect()}}
    private fun cacheFile(c:Context)=File(c.filesDir,"encyclopedia.json")
    private fun cache(c:Context)=try{JSONObject(cacheFile(c).readText())}catch(_:Exception){JSONObject()}
    private val wordRx=Regex("[\\p{L}]{5,}")
    /** The article for [word] (as it stands in the text) met in [context]; null when there is none. Off the UI thread. */
    fun lookup(c:Context,word:String,context:String):JSONObject?{
        val clean=word.trim().trim{!it.isLetterOrDigit()&&it!='-'}
        if(clean.length<2)return null
        val lang=if(clean.any{it in 'а'..'я'||it in 'А'..'Я'||it=='ё'||it=='Ё'})"ru" else "en"
        val key="$lang:${Dictionary.key(clean)}"
        synchronized(this){cache(c).optJSONObject(key)?.let{return it.takeIf{a->a.length()>0}}}
        val q=java.net.URLEncoder.encode(clean,"UTF-8")
        val found=JSONObject(get("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=6&srprop=snippet&format=json&utf8=1")).optJSONObject("query")?.optJSONArray("search")
        val ctx=wordRx.findAll(context.lowercase()).map{it.value.take(6)}.toSet()
        val stem=Dictionary.key(clean).let{it.take(maxOf(3,it.length-2))}
        // the rank, a title that is the word itself, and how much of the sentence (and the book) the snippet shares
        val ranked=(0 until (found?.length()?:0)).map{i->val r=found!!.getJSONObject(i);val t=r.optString("title");val snip=r.optString("snippet").replace(Regex("<[^>]+>"),"").lowercase()
            val share=wordRx.findAll(snip+" "+t.lowercase()).map{it.value.take(6)}.toSet().count{it in ctx}
            val score=(6-i)+(if(Dictionary.key(t).startsWith(stem))4 else 0)+share*2-(if(t.contains("значения")||t.contains("disambiguation"))8 else 0)
            t to score}.sortedByDescending{it.second}
        var out:JSONObject?=null
        for((title,_) in ranked.take(3)){val s=JSONObject(get("https://$lang.wikipedia.org/api/rest_v1/page/summary/"+java.net.URLEncoder.encode(title.replace(' ','_'),"UTF-8")))
            if(s.optString("type")=="disambiguation")continue
            val extract=s.optString("extract").trim();if(extract.isBlank())continue
            out=JSONObject().put("title",s.optString("title",title)).put("description",s.optString("description")).put("extract",extract).put("lang",lang);break}
        synchronized(this){val all=cache(c);all.put(key,out?:JSONObject());try{cacheFile(c).writeText(all.toString())}catch(_:Exception){}}
        return out}
    /** What is learned for a name: its one-line description, or the opening sentence after its dash. */
    fun meaning(e:JSONObject):String{val d=e.optString("description").trim();if(d.length in 3..120)return d
        val x=e.optString("extract");val after=Regex("[\\s\\u00a0][—–][\\s\\u00a0]").split(x,2).let{if(it.size>1)it[1] else x}.substringBefore(". ").trim().trimEnd('.')
        return if(after.length<=140)after else after.take(137).substringBeforeLast(' ')+"…"}
}

/** On-device translation (Google ML Kit): the language is recognised, the model is fetched once per language. */
internal object Translation{
    fun identify(text:String,done:(String?)->Unit){
        com.google.mlkit.nl.languageid.LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener{done(it.takeIf{l->l!="und"})}.addOnFailureListener{done(null)}}
    fun translate(text:String,from:String,to:String,onModel:()->Unit,done:(String?,String?)->Unit){
        val src=com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(from);val dst=com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(to)
        if(src==null||dst==null){done(null,"Этот язык пока не переводится");return}
        val t=com.google.mlkit.nl.translate.Translation.getClient(com.google.mlkit.nl.translate.TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        val models=com.google.mlkit.common.model.RemoteModelManager.getInstance()
        val need=listOf(src,dst).filter{it!=com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH}.map{com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(it).build()}
        models.getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel::class.java).addOnCompleteListener{have->
            val got=have.result?.map{it.language}?.toSet()?:emptySet()
            if(need.any{it.language !in got})onModel()
            t.downloadModelIfNeeded(com.google.mlkit.common.model.DownloadConditions.Builder().build())
                .addOnSuccessListener{t.translate(text).addOnSuccessListener{r->done(r,null);t.close()}.addOnFailureListener{e->done(null,"Не удалось перевести");t.close()}}
                .addOnFailureListener{done(null,"Не удалось загрузить язык — нужен интернет один раз");t.close()}}}
    fun name(tag:String)=Locale(tag).getDisplayLanguage(Locale("ru")).replaceFirstChar{it.titlecase(Locale("ru"))}
}

/** The card under the page for «Словарь» and «Перевод» (approved): word or languages on top, a hairline, the content,
 *  and the buttons: «Копировать» and «Учить слово» for a word or a name, «Копировать» and «В заметку» for a
 *  translation. Pulled down or tapped outside, it leaves. */
internal class LookupSheet(private val a:MainActivity,private val readerBg:Int,private val selected:String,private val source:WordSource?=null,private val toNote:(String)->Unit){
    private val reduced get()=a.reducedMotion
    private val sheetColor get()=a.sheetColor
    private val quiet get()=Ui.fill(a.light)
    private var dialog:android.app.Dialog?=null
    private lateinit var holder:LinearLayout;private lateinit var body:LinearLayout
    private var copyText="";private var tts:TextToSpeech?=null
    private val io=Executors.newSingleThreadExecutor()
    private val serif get()=PolkaFonts.literata(a)
    private fun d(v:Float)=v*a.resources.displayMetrics.density

    /** A word with a capital letter is most likely a name: it is looked up in the encyclopedia even without the dictionary. */
    private val isName get()=selected.trim().trimStart{!it.isLetter()}.firstOrNull()?.isUpperCase()==true
    fun dictionary(){show("Словарь");if(Dictionary.ready(a)||isName)lookupWord() else offerDownload()}
    fun translation(){show("Перевод");translate(null,null)}

    private fun show(title:String){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=DragRoot().apply{isClickable=true;setOnClickListener{close()}}
        holder=a.column().apply{isClickable=true;setPadding(a.d(20),0,a.d(20),a.d(20));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(Radius.R28).toFloat()}}
        holder.addView(FrameLayout(a).apply{addView(View(a).apply{background=a.shape(Ui.handle(a.light),2)},FrameLayout.LayoutParams(a.d(36),a.d(4),Gravity.CENTER))},LinearLayout.LayoutParams(-1,a.d(16)))
        // the one window head, as on every sheet
        holder.addView(a.windowHead(title,true,a.fg,quiet,{close()}),LinearLayout.LayoutParams(-1,a.d(Size.HEADER)).apply{leftMargin=-a.d(8);rightMargin=-a.d(8);bottomMargin=a.d(4)})
        body=a.column();val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(body)}
        holder.addView(scroll,LinearLayout.LayoutParams(-1,-2,1f))
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),a.d(80),a.d(8),a.d(8))}
        root.addView(holder,lp);dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,readerBg);if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.topMargin=a.d(80)+s.top;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;holder.layoutParams=lp}
        dlg.setOnKeyListener{_,c,e->if(c==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.setOnDismissListener{tts?.shutdown();Dictionary.cancel=true;io.shutdownNow()}
        loading()
        dlg.show()
        if(!reduced){holder.translationY=a.d(900).toFloat();holder.post{val h=holder.height+a.d(24).toFloat();SheetMotion{v->holder.translationY=h*v}.apply{snap(1f);to(0f)}}}
    }
    private fun close(velocity:Float=0f){val dl=dialog?:return;dialog=null
        if(reduced){dl.dismiss();return}
        val left=holder.height+a.d(24)-holder.translationY
        holder.animate().translationY(holder.height+a.d(24).toFloat()).setDuration((if(velocity>0f)(left/velocity*1000f).toLong() else MotionSpec.SHEET_OUT).coerceIn(140L,MotionSpec.SHEET_OUT)).setInterpolator(MotionSpec.exit).withEndAction{dl.dismiss()}.start()}
    /** Replaces the content; the card eases to its new height. */
    private fun content(build:LinearLayout.()->Unit){
        val h0=holder.height;body.removeAllViews();body.build()
        if(h0>0&&!reduced){holder.post{val h1=holder.height;if(h1!=h0){holder.translationY=(h1-h0).toFloat();holder.animate().translationY(0f).setDuration(Motion.STANDARD).setInterpolator(MotionSpec.enter).start()}}}
        body.alpha=0f;body.animate().alpha(1f).setDuration(if(reduced)0 else Motion.FAST).start()}
    private fun loading()=content{addView(ProgressBar(a).apply{isIndeterminate=true;indeterminateTintList=android.content.res.ColorStateList.valueOf(a.muted)},LinearLayout.LayoutParams(a.d(28),a.d(28)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=a.d(18);bottomMargin=a.d(26)})}
    private fun hairline(p:LinearLayout)=p.addView(View(a).apply{setBackgroundColor(Ui.line(a.light))},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14);bottomMargin=a.d(14)})
    private fun round(kind:String,desc:String,fn:()->Unit)=FrameLayout(a).apply{background=a.shape(quiet,Radius.R20);isClickable=true;contentDescription=desc;a.press(this);setOnClickListener{fn()}
        addView(LineIcon(a,kind,a.fg).apply{size=20f},FrameLayout.LayoutParams(-1,-1))}
    private fun copy(){(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Glyph",copyText));Notice.short(a,Notice.Kind.COPIED,"Скопировано");close()}
    /** A translation: «Копировать» and «В заметку», equal icon buttons (approved). */
    private fun actions(p:LinearLayout){
        val row=a.line().apply{gravity=Gravity.CENTER}
        fun btn(kind:String,label:String,fn:()->Unit)=a.line().apply{gravity=Gravity.CENTER;background=a.shape(quiet,Radius.R24);isClickable=true;contentDescription=label;a.press(this);setOnClickListener{fn()}
            addView(LineIcon(a,kind,a.fg).apply{size=21f},LinearLayout.LayoutParams(a.d(24),a.d(24)))}
        row.addView(btn("copy","Копировать"){copy()},LinearLayout.LayoutParams(0,a.d(44),1f))
        row.addView(View(a),LinearLayout.LayoutParams(a.d(10),1))
        row.addView(btn("note","В заметку"){close();toNote(copyText)},LinearLayout.LayoutParams(0,a.d(44),1f))
        p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})}
    /** A word or a name (approved «Слова»): «Копировать» as a round button and «Учить слово» with its label. Nothing here
     *  makes a quote: notes belong to quotes, a word goes only to the words. [forms] are known at once for a name. */
    private fun learnActions(p:LinearLayout,learn:JSONObject,forms:List<String>?=null){
        val row=a.line().apply{gravity=Gravity.CENTER}
        row.addView(round("copy","Копировать"){copy()},LinearLayout.LayoutParams(a.d(52),a.d(52)))
        row.addView(View(a),LinearLayout.LayoutParams(a.d(10),1))
        val lemma=learn.optString("w");val lang=learn.optString("lang","ru")
        fun saved()=Words.find(a,Dictionary.key(lemma),lang)
        val icon=LineIcon(a,"learn",a.bg).apply{size=20f};val label=a.text("",Type.BODY,a.bg,true)
        val b=a.line().apply{gravity=Gravity.CENTER;isClickable=true;a.press(this);addView(icon,LinearLayout.LayoutParams(a.d(22),a.d(22)));addView(label,LinearLayout.LayoutParams(-2,-2).apply{marginStart=a.d(10)})}
        fun paint(){val on=saved()!=null;b.background=a.shape(if(on)quiet else a.fg,Radius.R28);icon.set(if(on)"check" else "learn",if(on)a.fg else a.bg);label.setTextColor(if(on)a.fg else a.bg)
            label.text=if(on)"Учу" else "Учить слово";b.contentDescription=if(on)"Убрать из изучения" else "Учить слово"}
        b.setOnClickListener{val have=saved()
            if(have!=null){Words.remove(a,have.str("id"));paint();a.reader?.refreshWordMarks();return@setOnClickListener}
            val surface=selected.trim().split(Regex("\\s+")).firstOrNull()?.trim{!it.isLetter()&&it!='-'&&it!='\''&&it!='’'}?:lemma
            val w=Words.add(a,learn,surface,source,forms?:emptyList());paint();a.reader?.refreshWordMarks()
            // a dictionary word: its other forms are looked up in the background (a full scan of the keys)
            if(forms==null)Thread{val f=try{Dictionary.forms(a,lemma,lang)}catch(_:Exception){emptyList()}
                if(f.isNotEmpty())a.runOnUiThread{Words.setForms(a,w.str("id"),f);a.reader?.refreshWordMarks()}}.start()}
        paint()
        row.addView(b,LinearLayout.LayoutParams(0,a.d(52),1f))
        p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})}
    private fun message(title:String,sub:String?,action:String?=null,fn:(()->Unit)?=null)=content{
        addView(a.text(title,Type.BODY,a.fg,true));if(sub!=null)addView(a.text(sub,Type.SECONDARY,a.muted).apply{cssLeading(1.4f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})
        if(action!=null)addView(a.text(action,Type.TEXT,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(quiet,Radius.R24);isClickable=true;a.press(this);setOnClickListener{fn?.invoke()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}

    // ── dictionary ──
    private fun offerDownload(){
        content{val head=a.line()
            head.addView(FrameLayout(a).apply{background=a.shape(quiet,Radius.R16);addView(LineIcon(a,"dictionary",a.fg),FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(48),a.d(48)))
            val t=a.column();t.addView(a.text("Словарь без интернета",Type.BODY,a.fg,true));t.addView(a.text("Русский и английский, из Викисловаря. Скачивается один раз, около 35 МБ",Type.SECONDARY,a.muted).apply{cssLeading(1.35f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(2)})
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(14)});addView(head)
            addView(a.text("Скачать",Type.TEXT,a.bg,true).apply{gravity=Gravity.CENTER;background=a.shape(a.fg,Radius.R24);isClickable=true;a.press(this);setOnClickListener{download()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}}
    private fun download(){
        lateinit var bar:ThinBar;lateinit var left:TextView;lateinit var right:TextView
        content{val head=a.line()
            head.addView(FrameLayout(a).apply{background=a.shape(quiet,Radius.R16);addView(LineIcon(a,"dictionary",a.fg),FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(48),a.d(48)))
            val t=a.column();t.addView(a.text("Словарь без интернета",Type.BODY,a.fg,true));t.addView(a.text("Русский и английский, из Викисловаря",Type.SECONDARY,a.muted))
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(14)});addView(head)
            bar=ThinBar();addView(bar,LinearLayout.LayoutParams(-1,a.d(6)).apply{topMargin=a.d(20)})
            val row=a.line();left=a.text("Подключаюсь…",Type.SECONDARY,a.muted);right=a.text("",Type.SECONDARY,a.muted)
            row.addView(left,LinearLayout.LayoutParams(0,-2,1f));row.addView(right);addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
            addView(a.text("Отмена",Type.TEXT,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(quiet,Radius.R24);isClickable=true;a.press(this);setOnClickListener{Dictionary.cancel=true;close()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}
        io.execute{try{Dictionary.download(a){n,total->holder.post{if(total>0){bar.set(n.toFloat()/total);left.text="${n/1_000_000} из ${total/1_000_000} МБ";right.text="${n*100/total}%"}else left.text="${n/1_000_000} МБ"}}
                holder.post{if(dialog!=null)lookupWord()}}
            catch(_:InterruptedException){}
            catch(e:Exception){holder.post{if(dialog!=null)message("Не удалось скачать словарь","Проверьте интернет и попробуйте ещё раз","Повторить"){download()}}}}}
    private fun lookupWord(){val word=selected.trim().split(Regex("\\s+")).firstOrNull()?:"";val name=isName
        io.execute{val found=try{Dictionary.lookup(a,word)}catch(_:Exception){emptyList()}
            // a name, or a word the dictionary does not have, is looked for in the encyclopedia
            var offline=false
            val article=if(name||found.isEmpty())try{Encyclopedia.lookup(a,word,(source?.sentence?:"")+" "+(source?.title?:""))}catch(_:Exception){offline=true;null} else null
            holder.post{if(dialog==null)return@post
                when{article!=null&&(name||found.isEmpty())->showArticle(article,word)
                    found.isNotEmpty()->showEntries(found)
                    offline->message("Нужен интернет","Имена и названия ищутся в Википедии","Перевести"){translate(null,null)}
                    else->message("«${word.take(40)}» нет ни в словаре, ни в Википедии",null,"Перевести"){translate(null,null)}}}}}
    /** A name: the article's title, its one line, the opening of the article and where it is from. */
    private fun showArticle(e:JSONObject,word:String){
        val title=e.optString("title").substringBefore(" (").trim();val lang=e.optString("lang","ru")
        content{
            val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(44)}
            val t=a.column()
            t.addView(TextView(a).apply{text=title;textSize=Type.H1;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true)})
            e.optString("description").takeIf{it.isNotBlank()}?.let{t.addView(a.text(it,Type.SECONDARY,a.muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(4)})}
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f))
            head.addView(round("speaker","Произнести"){speak(title,lang)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
            addView(head);hairline(this)
            val extract=e.optString("extract").let{x->if(x.length<=520)x else x.take(520).substringBeforeLast(". ")+"."}
            addView(TextView(a).apply{text=extract;textSize=Type.BODY;setTextColor(a.fg);typeface=serif;cssLeading(1.5f)})
            addView(a.text("Википедия",Type.CAPTION,a.muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(10)})
            copyText="$title — $extract"
            val learn=JSONObject().put("w",title).put("lang",lang).put("src","wiki").put("p","имя").put("s",JSONArray().put(JSONArray().put(Encyclopedia.meaning(e))))
            val surface=word.trim{!it.isLetter()&&it!='-'}
            val forms=(Words.nameForms(surface)+(if(!title.contains(' '))Words.nameForms(title) else emptyList())).distinct()
            learnActions(this,learn,forms)}}
    private fun showEntries(list:List<JSONObject>){
        val first=list[0];val lang=first.optString("lang","ru")
        val sb=StringBuilder()
        content{
            val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(44)}
            val t=a.column()
            val w=TextView(a).apply{text=first.optString("w");textSize=Type.H1;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true)}
            val ipa=first.optString("i")
            if(ipa.isNotBlank()){val sp=android.text.SpannableStringBuilder(first.optString("w")).append("  ").append(ipa)
                val st=first.optString("w").length+2;sp.setSpan(android.text.style.AbsoluteSizeSpan(Type.TEXT.toInt(),true),st,sp.length,0);sp.setSpan(android.text.style.ForegroundColorSpan(a.muted),st,sp.length,0);sp.setSpan(android.text.style.TypefaceSpan(PolkaFonts.sans(400)),st,sp.length,0);w.text=sp}
            t.addView(w);if(first.optString("p").isNotBlank())t.addView(a.text(first.optString("p"),Type.SECONDARY,a.muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(4)})
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f))
            head.addView(round("speaker","Произнести"){speak(first.optString("w").replace("́",""),lang)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
            addView(head);hairline(this)
            sb.append(first.optString("w").replace("́",""))
            first.optJSONArray("t")?.let{tr->val s=(0 until tr.length()).joinToString(", "){tr.getString(it)}
                addView(TextView(a).apply{text=s;textSize=Type.TITLE;setTextColor(a.fg);typeface=serif},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(12)});sb.append(" — ").append(s)}
            list.filter{it.optString("w")==first.optString("w")&&it.optString("lang")==lang}.take(3).forEachIndexed{ei,e->
                if(ei>0){addView(a.text(e.optString("p"),Type.SECONDARY,a.muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16);bottomMargin=a.d(8)})}
                val senses=e.optJSONArray("s")?:JSONArray()
                for(i in 0 until senses.length()){val s=senses.getJSONArray(i)
                    val row=a.line().apply{gravity=Gravity.TOP}
                    row.addView(a.text("${i+1}",Type.CAPTION,a.muted,true).apply{setPadding(0,a.d(4),0,0)},LinearLayout.LayoutParams(a.d(22),-2))
                    val col=a.column()
                    col.addView(TextView(a).apply{text=s.optString(0);textSize=Type.BODY;setTextColor(a.fg);typeface=serif;cssLeading(1.5f)})
                    if(s.optString(1).isNotBlank())col.addView(TextView(a).apply{text=s.optString(1);textSize=Type.TEXT;setTextColor(a.muted);typeface=PolkaFonts.literata(a,false,true);cssLeading(1.45f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(3)})
                    row.addView(col,LinearLayout.LayoutParams(0,-2,1f));addView(row,LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=a.d(12)})
                    sb.append("\n${i+1}. ").append(s.optString(0))}}
            first.optJSONArray("y")?.let{y->val s=(0 until y.length()).joinToString(", "){y.getString(it)}
                addView(a.text("СИНОНИМЫ",Type.CAPTION,a.muted,true).apply{letterSpacing=.06f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})
                addView(TextView(a).apply{text=s;textSize=Type.TEXT;setTextColor(a.fg);typeface=serif},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(3)})}
            copyText=sb.toString();learnActions(this,first)}}
    private fun speak(text:String,lang:String){
        val go={tts?.language=Locale(lang);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"w")}
        if(tts==null)tts=TextToSpeech(a){if(it==TextToSpeech.SUCCESS)go()} else go()}

    // ── translation ──
    private var from:String?=null;private var to:String?=null
    private fun translate(src:String?,dst:String?){loading()
        val text=selected.trim().take(4000)
        fun run(f:String){val t=dst?:if(f=="ru")"en" else "ru";from=f;to=t
            if(f==t){showTranslation(text);return}
            Translation.translate(text,f,t,onModel={holder.post{if(dialog!=null)message("Загружаю язык","${Translation.name(f)} — один раз, около 30 МБ. Дальше перевод работает без интернета")}}){r,err->
                holder.post{if(dialog==null)return@post;if(r!=null)showTranslation(r) else message(err?:"Не удалось перевести",null,"Повторить"){translate(src,dst)}}}}
        if(src!=null)run(src) else Translation.identify(text){l->holder.post{if(dialog!=null)run(l?:if(text.any{it in 'а'..'я'||it in 'А'..'Я'})"ru" else "en")}}}
    private fun showTranslation(result:String){copyText=result
        content{
            val seg=a.line().apply{gravity=Gravity.CENTER_VERTICAL;background=a.shape(Ui.fill(a.light),Radius.R20);setPadding(a.d(4),0,a.d(4),0)}
            seg.addView(a.text(Translation.name(from?:"en"),Type.SECONDARY,a.fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,1f))
            seg.addView(FrameLayout(a).apply{isClickable=true;contentDescription="Поменять языки";a.press(this);setOnClickListener{val f=to;val t=from;if(f!=null&&t!=null)translateSwapped(f,t)}
                addView(LineIcon(a,"swap",a.muted).apply{size=18f},FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(40),-1))
            seg.addView(a.text(Translation.name(to?:"ru"),Type.SECONDARY,a.fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,1f))
            addView(seg,LinearLayout.LayoutParams(-1,a.d(40)));hairline(this)
            addView(TextView(a).apply{text=result;textSize=Type.TITLE;setTextColor(a.fg);typeface=serif;cssLeading(1.55f);setTextIsSelectable(true)})
            actions(this)}}
    /** Swapping translates the same selection the other way round. */
    private fun translateSwapped(f:String,t:String)=translate(f,t)

    private inner class ThinBar:View(a){private var v=0f;private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(x:Float){v=x.coerceIn(0f,1f);invalidate()}
        override fun onDraw(c:Canvas){val r=height/2f;p.color=Ui.fill(a.light);c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,p)
            if(v>0f){p.color=a.fg;c.drawRoundRect(0f,0f,maxOf(height.toFloat(),width*v),height.toFloat(),r,r,p)}}}
    private inner class DragRoot:FrameLayout(a){
        private var y0=0f;private var x0=0f;private var dragging=false;private var vt:VelocityTracker?=null
        private fun inSheet(x:Float,y:Float):Boolean{val r=Rect();holder.getGlobalVisibleRect(r);return r.contains(x.toInt(),y.toInt())}
        private fun scrolledTop():Boolean{val s=holder.getChildAt(1) as? ScrollView;return s==null||s.scrollY==0}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{x0=e.rawX;y0=e.rawY;dragging=false;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e)}
            MotionEvent.ACTION_MOVE->{vt?.addMovement(e);val dy=e.rawY-y0;val dx=e.rawX-x0
                if(!dragging&&inSheet(x0,y0)&&scrolledTop()&&dy>a.d(10)&&dy>kotlin.math.abs(dx)*1.4f){dragging=true;y0=e.rawY;return true}}};return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);vt?.addMovement(e)
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->holder.translationY=(e.rawY-y0).coerceAtLeast(0f)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{vt?.computeCurrentVelocity(1000);val v=vt?.yVelocity?:0f;dragging=false
                    if(holder.translationY>holder.height*.25f||v>a.d(900))close(v.coerceAtLeast(0f))
                    else holder.animate().translationY(0f).setDuration(MotionSpec.SETTLE).setInterpolator(MotionSpec.move).start()}};return true}
    }
}
