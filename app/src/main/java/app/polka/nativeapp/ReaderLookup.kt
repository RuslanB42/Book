package app.polka.nativeapp

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
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
        if(kind=="colordot"){p.style=Paint.Style.FILL;p.color=dot?:ink;c.drawCircle(12f,12f,8f,p);p.style=Paint.Style.STROKE;p.color=0x40ffffff;p.strokeWidth=1f;c.drawCircle(12f,12f,8f,p);c.restore();return}
        if(!IosIcons.draw(c,kind,ink))PATHS[kind]?.forEach{c.drawPath(it,p)};c.restore()}
    companion object{
        private fun parse(vararg d:String)=d.map{androidx.core.graphics.PathParser.createPathFromPathData(it)!!}
        val PATHS:Map<String,List<Path>> by lazy{mapOf(
            "quote" to parse("M10 9.5a2.5 2.5 0 1 0-2.5 2.5H10V9.5c0 3.2-1.4 5.4-4 6.8","M18.5 9.5a2.5 2.5 0 1 0-2.5 2.5h2.5V9.5c0 3.2-1.4 5.4-4 6.8"),
            "note" to parse("M4 20h16","M14.5 5.5l3 3L9 17H6v-3z","M12.5 7.5l3 3"),
            "translate" to parse("M13 9V5.5A2.5 2.5 0 0 0 10.5 3h-5A2.5 2.5 0 0 0 3 5.5v5A2.5 2.5 0 0 0 5.5 13H9","M6 10.5L8 5.5l2 5M6.7 8.8h2.6",
                "M13.5 11h5a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1-2.5 2.5h-5a2.5 2.5 0 0 1-2.5-2.5v-5a2.5 2.5 0 0 1 2.5-2.5z","M17.6 18.5v-5h-1.7a1.5 1.5 0 0 0 0 3h1.7M16.1 16.5l-1.9 2"),
            "dictionary" to parse("M5 19V5.5A2.5 2.5 0 0 1 7.5 3H19v14H7.5A2.5 2.5 0 0 0 5 19.5 2.5 2.5 0 0 0 7.5 22H19v-5","M9.5 13l2.5-6 2.5 6M10.4 11h3.2"),
            "copy" to parse("M10.5 8h7A2.5 2.5 0 0 1 20 10.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 8 17.5v-7A2.5 2.5 0 0 1 10.5 8z","M16 8V6.5A2.5 2.5 0 0 0 13.5 4h-7A2.5 2.5 0 0 0 4 6.5v7A2.5 2.5 0 0 0 6.5 16H8"),
            "trash" to parse("M4 7h16","M9.5 7V4.5h5V7","M6.5 7l1 12.5A1.5 1.5 0 0 0 9 21h6a1.5 1.5 0 0 0 1.5-1.5L17.5 7","M10 11v6M14 11v6"),
            "speaker" to parse("M4 9.5v5h3.5L12 18V6L7.5 9.5z","M15.5 9.5a3.5 3.5 0 0 1 0 5M18 7a7 7 0 0 1 0 10"),
            "swap" to parse("M7 8h11l-3-3","M17 16H6l3 3"))}
    }
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
 *  and two equal buttons «Копировать» and «В заметку». Pulled down or tapped outside, it leaves. */
internal class LookupSheet(private val a:MainActivity,private val readerBg:Int,private val selected:String,private val source:WordSource?=null,private val toNote:(String)->Unit){
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private val quiet get()=if(a.light)0x0f000000 else 0x0fffffff
    private var dialog:android.app.Dialog?=null
    private lateinit var holder:LinearLayout;private lateinit var body:LinearLayout
    private var copyText="";private var tts:TextToSpeech?=null
    private val io=Executors.newSingleThreadExecutor()
    private val serif get()=PolkaFonts.literata(a)
    private fun d(v:Float)=v*a.resources.displayMetrics.density

    fun dictionary(){show();if(Dictionary.ready(a))lookupWord() else offerDownload()}
    fun translation(){show();translate(null,null)}

    private fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=DragRoot().apply{isClickable=true;setOnClickListener{close()}}
        holder=a.column().apply{isClickable=true;setPadding(a.d(20),a.d(12),a.d(20),a.d(20));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(28).toFloat()}}
        holder.addView(View(a).apply{background=a.shape(if(a.light)0x30000000 else 0x30ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(16)})
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
        if(!reduced){holder.translationY=a.d(900).toFloat();holder.post{val h=holder.height+a.d(24).toFloat();LiquidSpring{v,_->holder.translationY=h*v}.apply{snap(1f);to(0f)}}}
    }
    private fun close(velocity:Float=0f){val dl=dialog?:return;dialog=null
        if(reduced){dl.dismiss();return}
        val left=holder.height+a.d(24)-holder.translationY
        holder.animate().translationY(holder.height+a.d(24).toFloat()).setDuration((if(velocity>0f)(left/velocity*1000f).toLong() else 240L).coerceIn(140L,260L)).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{dl.dismiss()}.start()}
    /** Replaces the content; the card eases to its new height. */
    private fun content(build:LinearLayout.()->Unit){
        val h0=holder.height;body.removeAllViews();body.build()
        if(h0>0&&!reduced){holder.post{val h1=holder.height;if(h1!=h0){holder.translationY=(h1-h0).toFloat();holder.animate().translationY(0f).setDuration(280).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}}
        body.alpha=0f;body.animate().alpha(1f).setDuration(if(reduced)0 else 180).start()}
    private fun loading()=content{addView(ProgressBar(a).apply{isIndeterminate=true;indeterminateTintList=android.content.res.ColorStateList.valueOf(a.muted)},LinearLayout.LayoutParams(a.d(28),a.d(28)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=a.d(18);bottomMargin=a.d(26)})}
    private fun hairline(p:LinearLayout)=p.addView(View(a).apply{setBackgroundColor(if(a.light)0x14000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14);bottomMargin=a.d(14)})
    private fun round(kind:String,desc:String,fn:()->Unit)=FrameLayout(a).apply{background=a.shape(quiet,20);isClickable=true;contentDescription=desc;a.press(this);setOnClickListener{fn()}
        addView(LineIcon(a,kind,a.fg).apply{size=20f},FrameLayout.LayoutParams(-1,-1))}
    private fun actions(p:LinearLayout,learn:JSONObject?=null){
        val row=a.line().apply{gravity=Gravity.CENTER}
        // icons only (approved): equal buttons, the label goes to accessibility
        fun btn(kind:String,label:String,fn:()->Unit)=a.line().apply{gravity=Gravity.CENTER;background=a.shape(quiet,22);isClickable=true;contentDescription=label;a.press(this);setOnClickListener{fn()}
            addView(LineIcon(a,kind,a.fg).apply{size=21f;tag="icon"},LinearLayout.LayoutParams(a.d(24),a.d(24)))}
        row.addView(btn("copy","Копировать"){(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Листай",copyText));Notice.short(a,Notice.Kind.COPIED,"Скопировано");close()},LinearLayout.LayoutParams(0,a.d(44),1f))
        row.addView(View(a),LinearLayout.LayoutParams(a.d(10),1))
        row.addView(btn("note","В заметку"){close();toNote(copyText)},LinearLayout.LayoutParams(0,a.d(44),1f))
        if(learn!=null){
            val lemma=learn.optString("w");val lang=learn.optString("lang","ru")
            fun saved()=Words.find(a,Dictionary.key(lemma),lang)
            lateinit var b:LinearLayout
            fun paint(){val on=saved()!=null;(b.findViewWithTag<LineIcon>("icon")).set(if(on)"check" else "learn",a.fg);b.contentDescription=if(on)"Убрать из изучения" else "Учить слово"}
            b=btn("learn","Учить"){val have=saved()
                if(have!=null){Words.remove(a,have.str("id"));paint();a.reader?.refreshWordMarks()}
                else{val surface=selected.trim().split(Regex("\\s+")).firstOrNull()?.trim{!it.isLetter()&&it!='-'&&it!='\''&&it!='’'}?:lemma
                    // saved at once; the other forms of the word are looked up in the background (a full scan of the keys)
                    val w=Words.add(a,learn,surface,source,emptyList());paint();a.reader?.refreshWordMarks()
                    Thread{val forms=try{Dictionary.forms(a,lemma,lang)}catch(_:Exception){emptyList()}
                        if(forms.isNotEmpty())a.runOnUiThread{Words.setForms(a,w.str("id"),forms);a.reader?.refreshWordMarks()}}.start()}}
            paint()
            row.addView(View(a),LinearLayout.LayoutParams(a.d(10),1))
            row.addView(b,LinearLayout.LayoutParams(0,a.d(44),1f))}
        p.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})}
    private fun message(title:String,sub:String?,action:String?=null,fn:(()->Unit)?=null)=content{
        addView(a.text(title,16f,a.fg,true));if(sub!=null)addView(a.text(sub,13.5f,a.muted).apply{cssLeading(1.4f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})
        if(action!=null)addView(a.text(action,14f,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(quiet,22);isClickable=true;a.press(this);setOnClickListener{fn?.invoke()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}

    // ── dictionary ──
    private fun offerDownload(){
        content{val head=a.line()
            head.addView(FrameLayout(a).apply{background=a.shape(quiet,16);addView(LineIcon(a,"dictionary",a.fg),FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(48),a.d(48)))
            val t=a.column();t.addView(a.text("Словарь без интернета",16f,a.fg,true));t.addView(a.text("Русский и английский, из Викисловаря. Скачивается один раз, около 35 МБ",13f,a.muted).apply{cssLeading(1.35f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(2)})
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(14)});addView(head)
            addView(a.text("Скачать",14f,a.bg,true).apply{gravity=Gravity.CENTER;background=a.shape(a.fg,22);isClickable=true;a.press(this);setOnClickListener{download()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}}
    private fun download(){
        lateinit var bar:ThinBar;lateinit var left:TextView;lateinit var right:TextView
        content{val head=a.line()
            head.addView(FrameLayout(a).apply{background=a.shape(quiet,16);addView(LineIcon(a,"dictionary",a.fg),FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(48),a.d(48)))
            val t=a.column();t.addView(a.text("Словарь без интернета",16f,a.fg,true));t.addView(a.text("Русский и английский, из Викисловаря",13f,a.muted))
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(14)});addView(head)
            bar=ThinBar();addView(bar,LinearLayout.LayoutParams(-1,a.d(6)).apply{topMargin=a.d(20)})
            val row=a.line();left=a.text("Подключаюсь…",12.5f,a.muted);right=a.text("",12.5f,a.muted)
            row.addView(left,LinearLayout.LayoutParams(0,-2,1f));row.addView(right);addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
            addView(a.text("Отмена",14f,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(quiet,22);isClickable=true;a.press(this);setOnClickListener{Dictionary.cancel=true;close()}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(18)})}
        io.execute{try{Dictionary.download(a){n,total->holder.post{if(total>0){bar.set(n.toFloat()/total);left.text="${n/1_000_000} из ${total/1_000_000} МБ";right.text="${n*100/total}%"}else left.text="${n/1_000_000} МБ"}}
                holder.post{if(dialog!=null)lookupWord()}}
            catch(_:InterruptedException){}
            catch(e:Exception){holder.post{if(dialog!=null)message("Не удалось скачать словарь","Проверьте интернет и попробуйте ещё раз","Повторить"){download()}}}}}
    private fun lookupWord(){val word=selected.trim().split(Regex("\\s+")).firstOrNull()?:""
        io.execute{val found=try{Dictionary.lookup(a,word)}catch(_:Exception){emptyList()}
            holder.post{if(dialog==null)return@post;if(found.isEmpty())message("Слова «${word.take(40)}» нет в словаре",null,"Перевести"){translate(null,null)} else showEntries(found)}}}
    private fun showEntries(list:List<JSONObject>){
        val first=list[0];val lang=first.optString("lang","ru")
        val sb=StringBuilder()
        content{
            val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(44)}
            val t=a.column()
            val w=TextView(a).apply{text=first.optString("w");textSize=24f;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true)}
            val ipa=first.optString("i")
            if(ipa.isNotBlank()){val sp=android.text.SpannableStringBuilder(first.optString("w")).append("  ").append(ipa)
                val st=first.optString("w").length+2;sp.setSpan(android.text.style.AbsoluteSizeSpan(15,true),st,sp.length,0);sp.setSpan(android.text.style.ForegroundColorSpan(a.muted),st,sp.length,0);sp.setSpan(android.text.style.TypefaceSpan(PolkaFonts.sans(400)),st,sp.length,0);w.text=sp}
            t.addView(w);if(first.optString("p").isNotBlank())t.addView(a.text(first.optString("p"),12.5f,a.muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(4)})
            head.addView(t,LinearLayout.LayoutParams(0,-2,1f))
            head.addView(round("speaker","Произнести"){speak(first.optString("w").replace("́",""),lang)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
            addView(head);hairline(this)
            sb.append(first.optString("w").replace("́",""))
            first.optJSONArray("t")?.let{tr->val s=(0 until tr.length()).joinToString(", "){tr.getString(it)}
                addView(TextView(a).apply{text=s;textSize=17f;setTextColor(a.fg);typeface=serif},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(12)});sb.append(" — ").append(s)}
            list.filter{it.optString("w")==first.optString("w")&&it.optString("lang")==lang}.take(3).forEachIndexed{ei,e->
                if(ei>0){addView(a.text(e.optString("p"),12.5f,a.muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16);bottomMargin=a.d(8)})}
                val senses=e.optJSONArray("s")?:JSONArray()
                for(i in 0 until senses.length()){val s=senses.getJSONArray(i)
                    val row=a.line().apply{gravity=Gravity.TOP}
                    row.addView(a.text("${i+1}",12f,a.muted,true).apply{setPadding(0,a.d(4),0,0)},LinearLayout.LayoutParams(a.d(22),-2))
                    val col=a.column()
                    col.addView(TextView(a).apply{text=s.optString(0);textSize=15.5f;setTextColor(a.fg);typeface=serif;cssLeading(1.5f)})
                    if(s.optString(1).isNotBlank())col.addView(TextView(a).apply{text=s.optString(1);textSize=14.5f;setTextColor(a.muted);typeface=PolkaFonts.literata(a,false,true);cssLeading(1.45f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(3)})
                    row.addView(col,LinearLayout.LayoutParams(0,-2,1f));addView(row,LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=a.d(12)})
                    sb.append("\n${i+1}. ").append(s.optString(0))}}
            first.optJSONArray("y")?.let{y->val s=(0 until y.length()).joinToString(", "){y.getString(it)}
                addView(a.text("СИНОНИМЫ",11f,a.muted,true).apply{letterSpacing=.06f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})
                addView(TextView(a).apply{text=s;textSize=14.5f;setTextColor(a.fg);typeface=serif},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(3)})}
            copyText=sb.toString();actions(this,first)}}
    private fun speak(text:String,lang:String){
        val go={tts?.language=Locale(lang);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"w")}
        if(tts==null)tts=TextToSpeech(a){if(it==TextToSpeech.SUCCESS)go()} else go()}

    // ── translation ──
    private var from:String?=null;private var to:String?=null;private var lastResult=""
    private fun translate(src:String?,dst:String?){loading()
        val text=selected.trim().take(4000)
        fun run(f:String){val t=dst?:if(f=="ru")"en" else "ru";from=f;to=t
            if(f==t){showTranslation(text);return}
            Translation.translate(text,f,t,onModel={holder.post{if(dialog!=null)message("Загружаю язык","${Translation.name(f)} — один раз, около 30 МБ. Дальше перевод работает без интернета")}}){r,err->
                holder.post{if(dialog==null)return@post;if(r!=null)showTranslation(r) else message(err?:"Не удалось перевести",null,"Повторить"){translate(src,dst)}}}}
        if(src!=null)run(src) else Translation.identify(text){l->holder.post{if(dialog!=null)run(l?:if(text.any{it in 'а'..'я'||it in 'А'..'Я'})"ru" else "en")}}}
    private fun showTranslation(result:String){lastResult=result;copyText=result
        content{
            val seg=a.line().apply{gravity=Gravity.CENTER_VERTICAL;background=a.shape(if(a.light)0x0c000000 else 0x0dffffff,20);setPadding(a.d(4),0,a.d(4),0)}
            seg.addView(a.text(Translation.name(from?:"en"),13.5f,a.fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,1f))
            seg.addView(FrameLayout(a).apply{isClickable=true;contentDescription="Поменять языки";a.press(this);setOnClickListener{val f=to;val t=from;if(f!=null&&t!=null)translateSwapped(f,t)}
                addView(LineIcon(a,"swap",a.muted).apply{size=18f},FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(a.d(40),-1))
            seg.addView(a.text(Translation.name(to?:"ru"),13.5f,a.fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,1f))
            addView(seg,LinearLayout.LayoutParams(-1,a.d(40)));hairline(this)
            addView(TextView(a).apply{text=result;textSize=17f;setTextColor(a.fg);typeface=serif;cssLeading(1.55f);setTextIsSelectable(true)})
            actions(this)}}
    /** Swapping translates the same selection the other way round. */
    private fun translateSwapped(f:String,t:String)=translate(f,t)

    private inner class ThinBar:View(a){private var v=0f;private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(x:Float){v=x.coerceIn(0f,1f);invalidate()}
        override fun onDraw(c:Canvas){val r=height/2f;p.color=if(a.light)0x14000000 else 0x14ffffff;c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,p)
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
                    else holder.animate().translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(.8f)).start()}};return true}
    }
}
