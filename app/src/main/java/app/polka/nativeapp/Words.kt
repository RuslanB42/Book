package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.*
import android.widget.*
import org.json.*
import java.util.Locale
import kotlin.math.*

/** Where a word was met: the sentence around it and the book. */
internal data class WordSource(val sentence:String,val bookId:String,val title:String)

/** Marks a word the reader is learning; [MarkedTextView] draws a dotted line under it. */
internal class LearnMark(val id:String):android.text.style.CharacterStyle(){override fun updateDrawState(tp:android.text.TextPaint){}}

/** Reading text view that draws a thin dotted underline under words being learned. */
internal open class MarkedTextView(c:Context):TextView(c){
    private val dot=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val l=layout?:return;val t=text as? Spanned?:return
        val marks=t.getSpans(0,t.length,LearnMark::class.java);if(marks.isEmpty())return
        val dp=resources.displayMetrics.density;dot.color=(currentTextColor and 0xffffff) or (0x8c shl 24)
        val step=3.4f*dp;val r=.9f*dp
        canvas.save();canvas.translate(compoundPaddingLeft.toFloat(),extendedPaddingTop.toFloat())
        for(m in marks){val s=t.getSpanStart(m);val e=t.getSpanEnd(m);if(s<0||e<=s)continue
            var line=l.getLineForOffset(s);val last=l.getLineForOffset(e-1)
            while(line<=last){val ls=maxOf(s,l.getLineStart(line));var le=minOf(e,l.getLineEnd(line))
                while(le>ls&&t[le-1].isWhitespace())le--
                if(le>ls){val x0=JustifiedGeometry.x(this,l,line,ls);val x1=JustifiedGeometry.x(this,l,line,le)
                    val y=l.getLineBaseline(line)+paint.descent()*.62f
                    val n=((x1-x0-2*r)/step).toInt().coerceAtLeast(0);val start=x0+((x1-x0)-n*step)/2f
                    for(i in 0..n)canvas.drawCircle(start+i*step,y,r,dot)}
                line++}}
        canvas.restore()
    }
}

/**
 * Words the reader learns (approved «Слова»). A word is saved with the sentence it was met in and climbs five steps:
 * new, recognised, recalled, written, known. Each step has its own exercise, from easy to hard (pick the meaning, pick
 * the word, write the word into the sentence); the app grades the answer itself (right or wrong, quick or slow) and
 * FSRS spaces the repetitions. Sentences from other books where the word turns up are collected on the way.
 */
internal object Words {
    private const val DAY=86_400_000L
    private const val DECAY=-0.5
    private const val FACTOR=19.0/81.0
    /** FSRS-4.5 default weights. */
    private val W=doubleArrayOf(0.4872,1.4003,3.7145,13.8206,5.1618,1.2298,0.8975,0.031,1.6474,0.1367,1.0461,2.1072,0.0793,0.3246,1.587,0.2272,2.8755)
    /** New words a day, so a lesson never drowns in them. */
    const val NEW_PER_DAY=5
    const val KNOWN=4

    fun list(a:MainActivity):JSONArray=a.prefs.optJSONArray("words")?:JSONArray().also{a.prefs.put("words",it)}
    fun all(a:MainActivity)=list(a).objects()
    fun find(a:MainActivity,key:String,lang:String)=all(a).firstOrNull{it.str("k")==key&&it.str("lang")==lang}
    fun byId(a:MainActivity,id:String)=all(a).firstOrNull{it.str("id")==id}
    /** 0 new, 1 recognised, 2 recalled, 3 written, 4 known (older words get their step from their history). */
    fun stage(w:JSONObject):Int{if(w.has("st"))return w.optInt("st").coerceIn(0,KNOWN)
        if(w.optInt("reps")==0)return 0;val s=w.optDouble("s",0.0);return when{s>=21->KNOWN;s>=4->2;else->1}}
    fun known(w:JSONObject)=stage(w)>=KNOWN
    fun meaning(w:JSONObject)=w.str("t").ifBlank{w.str("d")}
    /** Where the word was met: (sentence, book id, book title), the first one first. */
    fun meetings(w:JSONObject):List<Triple<String,String,String>>{
        val m=w.optJSONArray("met")?.objects()?.map{Triple(it.str("s"),it.str("b"),it.str("t"))}?.filter{it.first.isNotBlank()}
        if(!m.isNullOrEmpty())return m
        return if(w.str("ctx").isNotBlank())listOf(Triple(w.str("ctx"),w.str("book"),w.str("title"))) else emptyList()}

    fun add(a:MainActivity,entry:JSONObject,surface:String,source:WordSource?,forms:List<String>):JSONObject{
        val lemma=entry.optString("w").ifBlank{surface};val lang=entry.optString("lang","ru")
        find(a,Dictionary.key(lemma),lang)?.let{return it}
        val tr=entry.optJSONArray("t");val senses=entry.optJSONArray("s")
        val w=JSONObject().put("id",uid()).put("w",lemma).put("k",Dictionary.key(lemma)).put("lang",lang)
            .put("t",if(tr==null)"" else (0 until tr.length()).joinToString(", "){tr.getString(it)})
            .put("d",senses?.optJSONArray(0)?.optString(0)?:"").put("p",entry.optString("p")).put("i",entry.optString("i"))
            .put("surf",surface).put("ctx",source?.sentence?:"").put("book",source?.bookId?:"").put("title",source?.title?:"")
            .put("met",JSONArray().apply{if(source!=null&&source.sentence.isNotBlank())put(JSONObject().put("s",source.sentence).put("b",source.bookId).put("t",source.title))})
            .put("forms",JSONArray((forms+Dictionary.key(lemma)+Dictionary.key(surface)).filter{it.isNotBlank()}.distinct()))
            .put("added",System.currentTimeMillis()).put("due",System.currentTimeMillis()).put("reps",0).put("lapses",0).put("st",0)
        list(a).put(w);a.savePreferences();return w
    }
    fun setForms(a:MainActivity,id:String,forms:List<String>){val w=byId(a,id)?:return
        val have=w.optJSONArray("forms")?.let{f->(0 until f.length()).map{f.getString(it)}}?:emptyList()
        w.put("forms",JSONArray((have+forms).filter{it.isNotBlank()}.distinct()));a.savePreferences()}
    fun remove(a:MainActivity,id:String){a.prefs.put("words",JSONArray(all(a).filterNot{it.str("id")==id}));a.savePreferences()}
    /** The forms of a name the dictionary does not know: the usual Russian case endings (or the English plural). */
    fun nameForms(word:String):List<String>{val k=Dictionary.key(word);if(k.length<3)return listOf(k)
        if(k.none{it in 'а'..'я'})return listOf(k,k+"s",k+"'s",k+"’s")
        val out=mutableListOf(k);val last=k.last();val stem=k.dropLast(1)
        when{last=='а'||last=='я'->listOf("а","ы","и","е","у","ю","ой","ей","ою","ею").forEach{out+=stem+it}
            last=='й'||last=='ь'->listOf("я","ю","ем","ём","е","и","ей").forEach{out+=stem+it}
            last=='о'||last=='е'->listOf("а","у","ом","е").forEach{out+=stem+it}
            last in "бвгджзклмнпрстфхцчшщ"->listOf("а","у","ом","ем","е","ы","и","ов","ам","ами","ах").forEach{out+=k+it}}
        return out.distinct()}

    private fun initD(g:Int)=(W[4]-exp(W[5]*(g-1))+1).coerceIn(1.0,10.0)
    /** One answer, graded by the app: 1 wrong, 2 right but slow, 3 right, 4 right at once. */
    fun grade(a:MainActivity,w:JSONObject,g:Int,now:Long=System.currentTimeMillis()){
        val s:Double;val d:Double
        if(w.optInt("reps")==0||!w.has("s")){s=W[g-1];d=initD(g)}
        else{val s0=w.optDouble("s",W[2]).coerceAtLeast(.1);val d0=w.optDouble("dif",5.0)
            val elapsed=max(0.0,(now-w.optLong("last",now))/DAY.toDouble())
            val r=(1+FACTOR*elapsed/s0).pow(DECAY)
            d=(W[7]*initD(4)+(1-W[7])*(d0-W[6]*(g-3))).coerceIn(1.0,10.0)
            s=if(g>1)s0*(exp(W[8])*(11-d0)*s0.pow(-W[9])*(exp(W[10]*(1-r))-1)*(if(g==2)W[15] else 1.0)*(if(g==4)W[16] else 1.0)+1)
              else min(s0,W[11]*d0.pow(-W[12])*((s0+1).pow(W[13])-1)*exp(W[14]*(1-r)))}
        if(w.optInt("reps")==0)w.put("first",now)
        w.put("s",s).put("dif",d).put("last",now).put("reps",w.optInt("reps")+1)
        if(g==1)w.put("lapses",w.optInt("lapses")+1)
        // a step up for a right answer (the last step only once the word holds for three weeks), a step down for a wrong one
        val st=stage(w);w.put("st",if(g>1)(st+1).coerceAtMost(if(s>=21)KNOWN else KNOWN-1) else (st-1).coerceAtLeast(1))
        w.put("due",if(g>1)now+max(1L,s.roundToLong())*DAY else now+10*60_000L)
        a.savePreferences()
    }

    /** Today's lesson: the words due now and a few new ones, the new ones spread among the others. */
    fun lesson(a:MainActivity,now:Long=System.currentTimeMillis()):List<JSONObject>{val words=all(a)
        val due=words.filter{it.optInt("reps")>0&&it.optLong("due")<=now}.sortedBy{it.optLong("due")}
        val today=java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val left=(NEW_PER_DAY-words.count{it.optLong("first")>=today}).coerceAtLeast(0)
        val fresh=words.filter{it.optInt("reps")==0}.sortedBy{it.optLong("added")}.take(left)
        val out=ArrayList<JSONObject>();val q=ArrayDeque(fresh);due.forEachIndexed{i,w->out+=w;if(i%3==2)q.removeFirstOrNull()?.let{out+=it}};out+=q;return out}

    /** Form key → word id, for underlining in the book text. */
    fun index(a:MainActivity):Map<String,String>{val m=HashMap<String,String>()
        all(a).forEach{w->val id=w.str("id");w.optJSONArray("forms")?.let{f->for(i in 0 until f.length())m[f.getString(i)]=id};m[w.str("k")]=id};return m}
    private val token=Regex("[\\p{L}\\u0301\\u0300'’-]+")
    /** Underlines the words being learned; returns, for each of them, the sentence it first turns up in. */
    fun mark(text:SpannableStringBuilder,index:Map<String,String>):Map<String,String>{
        if(index.isEmpty())return emptyMap();val met=HashMap<String,String>()
        for(m in token.findAll(text)){var s=m.range.first;var e=m.range.last+1
            while(e>s&&(text[e-1]=='-'||text[e-1]=='\''||text[e-1]=='’'))e--;while(s<e&&(text[s]=='-'||text[s]=='\''||text[s]=='’'))s++
            if(e<=s)continue
            val id=index[Dictionary.key(text.substring(s,e))]?:continue
            text.setSpan(LearnMark(id),s,e,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if(id !in met)met[id]=sentence(text,s,e)}
        return met
    }
    /** A word met again in a book: its sentence joins the ones the exercises take turns with (two a book, six in all). */
    fun meet(a:MainActivity,met:Map<String,String>,bookId:String,title:String){var changed=false
        for((id,sentence) in met){val w=byId(a,id)?:continue;if(sentence.length<20)continue
            val have=meetings(w);val norm=sentence.replace(Regex("\\s+")," ").trim()
            if(have.size>=6||have.any{it.first.replace(Regex("\\s+")," ").trim()==norm}||have.count{it.second==bookId}>=2)continue
            val arr=w.optJSONArray("met")?:JSONArray().also{arr->have.forEach{arr.put(JSONObject().put("s",it.first).put("b",it.second).put("t",it.third))};w.put("met",arr)}
            arr.put(JSONObject().put("s",norm).put("b",bookId).put("t",title));changed=true}
        if(changed)a.savePreferences()}
    /** The sentence around a selection, short enough for a card. */
    fun sentence(full:CharSequence,start:Int,end:Int):String{
        if(full.isEmpty())return ""
        val stops=".!?…\n"
        var s=start.coerceIn(0,full.length);var e=end.coerceIn(s,full.length)
        var n=0;while(s>0&&n<220&&full[s-1] !in stops){s--;n++}
        n=0;while(e<full.length&&n<220&&full[e] !in stops){e++;n++};if(e<full.length&&full[e]!='\n')e++
        var out=full.subSequence(s,e).toString().replace(' ',' ').replace(Regex("\\s+")," ").trim().trimStart('—','–','-',' ')
        if(out.length>240){val mid=(start-s).coerceIn(0,out.length);val from=(mid-110).coerceAtLeast(0);out=(if(from>0)"…" else "")+out.substring(from,(from+220).coerceAtMost(out.length)).trim()+"…"}
        return out
    }
    private var tts:TextToSpeech?=null;private var ttsReady=false
    fun speak(c:Context,text:String,lang:String){
        val go={tts?.language=Locale(lang);tts?.speak(text.replace("́",""),TextToSpeech.QUEUE_FLUSH,null,"w")}
        if(tts==null)tts=TextToSpeech(c.applicationContext){if(it==TextToSpeech.SUCCESS){ttsReady=true;go()}} else if(ttsReady)go()
    }
    /** Where in [sentence] the word stands (any of its forms). */
    fun spot(w:JSONObject,sentence:String):IntRange?{
        val forms=(w.optJSONArray("forms")?.let{f->(0 until f.length()).map{f.getString(it)}}?:emptyList()).toSet()+w.str("k")
        return token.findAll(sentence).firstOrNull{Dictionary.key(it.value.trim('-','\'','’')) in forms}?.range}
    /** The sentence with the word in bold. */
    fun context(a:MainActivity,w:JSONObject,sentence:String,size:Float,color:Int,strong:Int):TextView{
        val sb=SpannableStringBuilder(sentence)
        spot(w,sentence)?.let{r->sb.setSpan(android.text.style.ForegroundColorSpan(strong),r.first,r.last+1,0);sb.setSpan(TypefaceSpanCompat(PolkaFonts.literata(a,true)),r.first,r.last+1,0)}
        return TextView(a).apply{text=sb;textSize=size;setTextColor(color);typeface=PolkaFonts.literata(a);cssLeading(1.5f)}
    }
    /** Other meanings (or other words) for the choices: the reader's own words first, then the dictionary's. */
    fun others(a:MainActivity,w:JSONObject,words:Boolean,n:Int=3):List<String>{
        val mine=all(a).filter{it.str("id")!=w.str("id")&&it.str("lang")==w.str("lang")}.map{if(words)it.str("w") else meaning(it)}
        val own=if(words)w.str("w") else meaning(w)
        val out=mine.filter{it.isNotBlank()&&it!=own&&it.length<=110}.distinct().shuffled().take(n).toMutableList()
        if(out.size<n)Dictionary.random(a,w.str("lang"),(n-out.size)*2).map{if(words)it.first else it.second}.filter{it.isNotBlank()&&it!=own&&it !in out}.take(n-out.size).forEach{out+=it}
        return out}
}

internal class TypefaceSpanCompat(private val face:Typeface):android.text.style.MetricAffectingSpan(){
    override fun updateDrawState(tp:android.text.TextPaint){tp.typeface=face}
    override fun updateMeasureState(tp:android.text.TextPaint){tp.typeface=face}
}

private fun MainActivity.quietFill()=if(light)0x0f000000 else 0x12ffffff
private fun MainActivity.roundButton(kind:String,desc:String,size:Int=40,fn:()->Unit)=FrameLayout(this).apply{
    background=shape(quietFill(),size/2);isClickable=true;contentDescription=desc;press(this);setOnClickListener{fn()}
    addView(LineIcon(this@roundButton,kind,fg).apply{this.size=20f},FrameLayout.LayoutParams(-1,-1))}
private val MainActivity.mint get()=if(light)0xff1f9d6b.toInt() else 0xff63d2a1.toInt()
private val MainActivity.rose get()=if(light)0xffd2543f.toInt() else 0xffff8a7a.toInt()

/** Five dots: the word's step. */
internal class StageDots(private val a:MainActivity,var stage:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(46),a.d(8))
    override fun onDraw(c:Canvas){val d=a.d(6).toFloat();val gap=a.d(4).toFloat();val y=height/2f
        for(i in 0 until 5){p.color=if(i<stage)a.fg else (a.fg and 0xffffff) or (0x29 shl 24);c.drawCircle(d/2+i*(d+gap),y,d/2,p)}}
}

/** The «Слова» tab (approved): today's lesson in one plaque, then every word as a plain row; the known ones quieter. */
fun MainActivity.wordsPage(){
    val words=Words.all(this)
    if(words.isEmpty()){space(page,96);page.addView(emptyView("words","Пока нет слов","Выделите слово в книге, откройте «Словарь» и нажмите «Учить слово»."),LinearLayout.LayoutParams(-1,-2));return}
    val today=Words.lesson(this).size
    val card=line().apply{floatCard(this,24);gravity=Gravity.CENTER_VERTICAL;setPadding(d(18),d(14),d(14),d(14))}
    card.addView(TextView(this).apply{val sb=SpannableStringBuilder(if(today>0)"$today" else "Всё повторено")
        if(today>0){val st=sb.length;sb.append(" на сегодня");sb.setSpan(android.text.style.AbsoluteSizeSpan(15,true),st,sb.length,0);sb.setSpan(android.text.style.ForegroundColorSpan(muted),st,sb.length,0);sb.setSpan(TypefaceSpanCompat(PolkaFonts.sans(400)),st,sb.length,0)}
        text=sb;textSize=if(today>0)32f else 18f;setTextColor(fg);typeface=PolkaFonts.serif(this@wordsPage,true);includeFontPadding=false},LinearLayout.LayoutParams(0,-2,1f))
    if(today>0)card.addView(text("Начать",15f,bg,true).apply{gravity=Gravity.CENTER;setPadding(d(24),0,d(24),0);background=shape(fg,24);isClickable=true;press(this);setOnClickListener{WordLesson(this@wordsPage).start()}},LinearLayout.LayoutParams(-2,d(48)))
    card.minimumHeight=d(76);page.addView(card,LinearLayout.LayoutParams(-1,-2));space(page,10)
    val learning=words.filterNot{Words.known(it)}.sortedByDescending{it.optLong("added")};val known=words.filter{Words.known(it)}.sortedByDescending{it.optLong("added")}
    val all=learning+known
    all.forEachIndexed{i,w->page.addView(wordRow(w,i<all.size-1))}
}

private fun MainActivity.wordRow(w:JSONObject,rule:Boolean):View{
    val row=column().apply{setPadding(d(4),d(13),d(4),d(13));isClickable=true;press(this);if(Words.known(w))alpha=.5f
        contentDescription=w.str("w")+", "+Words.meaning(w);setOnClickListener{openWord(w)}}
    row.addView(TextView(this).apply{text=w.str("w");textSize=19f;setTextColor(fg);typeface=PolkaFonts.literata(this@wordRow,true)})
    Words.meaning(w).takeIf{it.isNotBlank()}?.let{row.addView(TextView(this).apply{text=it;textSize=14f;setTextColor(muted);typeface=PolkaFonts.literata(this@wordRow);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(2)})}
    // removing is a long press and asks first: one tap must never lose a word
    row.isLongClickable=true;row.setOnLongClickListener{row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        dialog().setTitle("Удалить «${w.str("w")}»?").setMessage("Слово пропадёт из изучения вместе с историей повторений.").setPositiveButton("Удалить"){_,_->Words.remove(this,w.str("id"));renderLibraryResults()}.setNegativeButton("Отмена",null).show();true}
    val box=column();box.addView(row)
    if(rule)box.addView(View(this).apply{setBackgroundColor(if(light)0x10000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1))
    return box
}

/** A word's own page, one level in (see Depth): the word, its meaning, its step and when it comes back, and the
 *  sentences from the books it was met in. */
internal fun MainActivity.openWord(w:JSONObject){wordPage?.let{return}
    val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false;setBackgroundColor(bg);elevation=d(24).toFloat();isClickable=true}
    val content=column();scroll.addView(content)
    followInsets(content){i->content.setPadding(d(20)+i.left,i.top+d(8),d(20)+i.right,d(32)+i.bottom)}
    root.addView(scroll,FrameLayout.LayoutParams(-1,-1));wordPage=scroll
    val head=line()
    head.addView(roundButton("back","Назад"){closeWord()},LinearLayout.LayoutParams(d(40),d(40)))
    head.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
    head.addView(roundButton("speaker","Произнести"){Words.speak(this,w.str("w"),w.str("lang"))},LinearLayout.LayoutParams(d(40),d(40)))
    content.addView(head,LinearLayout.LayoutParams(-1,d(52)))
    content.addView(TextView(this).apply{text=w.str("w");textSize=38f;setTextColor(fg);typeface=PolkaFonts.literata(this@openWord,true)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(18)})
    Words.meaning(w).takeIf{it.isNotBlank()}?.let{content.addView(TextView(this).apply{text=it;textSize=18f;setTextColor(fg);typeface=PolkaFonts.literata(this@openWord);cssLeading(1.5f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(8)})}
    val st=line();st.addView(StageDots(this,Words.stage(w)))
    val due=w.optLong("due");val days=((due-System.currentTimeMillis())/86_400_000L).toInt()
    val when_=when{w.optInt("reps")==0->"новое";days<=0->"повторение сегодня";days==1->"повторение завтра";else->"повторение через $days ${daysWordRu(days)}"}
    st.addView(text(when_,13f,muted).apply{setPadding(d(10),0,0,0)})
    content.addView(st,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(16)})
    val list=column();Words.meetings(w).forEach{(s,_,title)->
        list.addView(View(this).apply{setBackgroundColor(if(light)0x10000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1))
        val c=column().apply{setPadding(0,d(14),0,d(14))}
        c.addView(Words.context(this,w,s,16f,if(light)0xff3a3a3c.toInt() else 0xffcfcfd4.toInt(),fg))
        if(title.isNotBlank())c.addView(text(title,12.5f,muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(6)})
        list.addView(c)}
    content.addView(list,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(30)})
    Depth(this,scroll).enter()
}
internal fun MainActivity.closeWord(){val page=wordPage?:return;wordPage=null;Depth(this,page).leave{root.removeView(page)}}

/**
 * A lesson (approved): one exercise at a time under a thin progress line. The step decides the exercise: pick the
 * meaning, pick the word, write the word into the sentence. A right answer goes on by itself; a wrong one shows the
 * right answer and waits for «Дальше», and the word comes once more at the end.
 */
internal class WordLesson(private val a:MainActivity){
    private val queue=ArrayDeque<JSONObject>();private val tried=HashSet<String>();private val misses=HashMap<String,Int>()
    private val learned=HashSet<String>();private var total=0;private var done=0
    private var dialog:android.app.Dialog?=null
    private lateinit var root:FrameLayout;private lateinit var col:LinearLayout;private lateinit var stage:FrameLayout;private lateinit var bar:Progress;private lateinit var next:TextView
    private var shownAt=0L;private var answered=false
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())

    private inner class Progress:View(a){
        private var v=0f;private var anim:android.animation.ValueAnimator?=null
        fun set(x:Float){anim?.cancel();if(reduced||width==0){v=x;invalidate();return}
            anim=android.animation.ValueAnimator.ofFloat(v,x).apply{duration=MotionSpec.SWITCH;interpolator=MotionSpec.move;addUpdateListener{v=it.animatedValue as Float;invalidate()};start()}}
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){val r=height/2f;p.color=(a.fg and 0xffffff) or (0x1a shl 24);c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,p);p.color=a.fg;if(v>0f)c.drawRoundRect(0f,0f,max(height.toFloat(),width*v),height.toFloat(),r,r,p)}}

    fun start(){
        queue.clear();queue.addAll(Words.lesson(a));total=queue.size;done=0;if(total==0)return
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        root=FrameLayout(a).apply{setBackgroundColor(a.bg)}
        col=a.column()
        val top=a.line()
        top.addView(IconView(a,"x",a.muted).apply{contentDescription="Закрыть";isClickable=true;setOnClickListener{close()}},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        bar=Progress();top.addView(bar,LinearLayout.LayoutParams(0,a.d(4),1f).apply{marginStart=a.d(6);marginEnd=a.d(6)})
        col.addView(top,LinearLayout.LayoutParams(-1,a.d(48)))
        stage=FrameLayout(a);col.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        next=a.text("Дальше",16f,a.bg,true).apply{gravity=Gravity.CENTER;background=a.shape(a.fg,26);isClickable=true;a.press(this);visibility=View.GONE;setOnClickListener{advance()}}
        col.addView(next,LinearLayout.LayoutParams(-1,a.d(52)).apply{topMargin=a.d(12)})
        root.addView(col,FrameLayout.LayoutParams(-1,-1));dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.setLayout(-1,-1);w.setWindowAnimations(0);ReaderWindow.configure(w,false,a.bg)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,true);col.setPadding(a.d(18)+s.left,a.d(8)+s.top,a.d(18)+s.right,a.d(20)+s.bottom)}
        dlg.setOnKeyListener{_,c,e->if(c==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.show()
        // the lesson rises over the app like a sheet (see MotionSpec)
        if(!reduced){root.translationY=a.resources.displayMetrics.heightPixels.toFloat();root.animate().translationY(0f).setDuration(MotionSpec.SHEET_IN).setInterpolator(MotionSpec.enter).start()}
        root.post{ask(true)}
    }
    private fun close(){val d=dialog?:return;dialog=null;handler.removeCallbacksAndMessages(null);hideKeyboard()
        val end={d.dismiss();if(a.tab=="words")a.renderLibraryResults()}
        if(reduced)end() else root.animate().translationY(root.height.toFloat()).setDuration(MotionSpec.SHEET_OUT).setInterpolator(MotionSpec.exit).withEndAction{end()}.start()}
    private fun hideKeyboard(){(a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)}

    /** The exercise for the word on top, by its step. */
    private fun ask(first:Boolean){
        val w=queue.firstOrNull();if(w==null){finish();return}
        bar.set(done.toFloat()/total);answered=false;next.visibility=View.GONE
        val st=Words.stage(w)
        val kind=when{st<=1->"meaning";st==2->"word";else->"write"}
        val choices=if(kind=="write")emptyList() else Words.others(a,w,kind=="word")
        val view=if(kind=="write"||choices.size<2)write(w) else choose(w,kind=="word",choices)
        val old=if(stage.childCount>0)stage.getChildAt(0) else null
        stage.addView(view,FrameLayout.LayoutParams(-1,-1))
        if(old!=null){if(reduced)stage.removeView(old) else old.animate().alpha(0f).translationX(-a.d(MotionSpec.TAB_SHIFT).toFloat()).setDuration(MotionSpec.TAB_OUT).setInterpolator(MotionSpec.exit).withEndAction{stage.removeView(old)}.start()}
        if(!reduced&&!first){view.alpha=0f;view.translationX=a.d(MotionSpec.TAB_SHIFT).toFloat();view.animate().alpha(1f).translationX(0f).setStartDelay(MotionSpec.TAB_IN_DELAY).setDuration(MotionSpec.TAB_IN).setInterpolator(MotionSpec.enter).start()}
        shownAt=android.os.SystemClock.uptimeMillis()
    }
    private fun sentenceOf(w:JSONObject):String?{val m=Words.meetings(w);return if(m.isEmpty())null else m[(w.optInt("reps")+done)%m.size].first}

    /** Pick the meaning of the word (or, a step on, the word for the meaning). */
    private fun choose(w:JSONObject,words:Boolean,others:List<String>):View{
        val c=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,a.d(36),0,0)}
        if(!words){c.addView(TextView(a).apply{text=w.str("w");textSize=40f;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
            sentenceOf(w)?.let{s->c.addView(Words.context(a,w,s,16.5f,a.muted,a.fg).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14);leftMargin=a.d(6);rightMargin=a.d(6)})}}
        else c.addView(TextView(a).apply{text=Words.meaning(w);textSize=22f;setTextColor(a.fg);typeface=PolkaFonts.literata(a);gravity=Gravity.CENTER;cssLeading(1.4f)},LinearLayout.LayoutParams(-1,-2).apply{leftMargin=a.d(6);rightMargin=a.d(6)})
        val right=if(words)w.str("w") else Words.meaning(w)
        val options=(others+right).shuffled()
        val list=a.column();val rows=ArrayList<TextView>()
        options.forEach{o->val t=TextView(a).apply{text=o;tag=o;textSize=if(words)19f else 16f;setTextColor(a.fg);typeface=PolkaFonts.literata(a,words);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(a.d(16),a.d(15),a.d(16),a.d(15));background=a.shape(if(a.light)Color.WHITE else 0xff242426.toInt(),18);isClickable=true;a.press(this)
            setOnClickListener{pick(w,o==right,this,rows.first{r->r.tag==right})}}
            rows+=t;list.addView(t,LinearLayout.LayoutParams(-1,-2).apply{if(list.childCount>0)topMargin=a.d(10)})}
        c.addView(list,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(36)})
        return ScrollView(a).apply{isVerticalScrollBarEnabled=false;addView(c)}
    }
    private fun paint(v:TextView,ink:Int){v.background=GradientDrawableCompat.box(a,(ink and 0xffffff) or (0x29 shl 24),ink,18)}
    private fun pick(w:JSONObject,ok:Boolean,chosen:TextView,right:TextView){if(answered)return;answered=true
        val secs=(android.os.SystemClock.uptimeMillis()-shownAt)/1000.0
        if(ok){paint(chosen,a.mint);result(w,if(secs<6)3 else 2);handler.postDelayed({advance()},650)}
        else{paint(chosen,a.rose);paint(right,a.mint);(right.parent as ViewGroup).let{p->for(i in 0 until p.childCount){val r=p.getChildAt(i);if(r!==chosen&&r!==right)r.alpha=.4f}};result(w,1);showNext()}}

    /** Write the word into the sentence it was met in (its meaning under it). */
    private fun write(w:JSONObject):View{
        val c=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,a.d(36),0,0)}
        val s=sentenceOf(w);val r=s?.let{Words.spot(w,it)}
        if(s!=null&&r!=null){val sb=SpannableStringBuilder(s.substring(0,r.first)).append(" ".repeat((r.last-r.first+1).coerceIn(5,12))).append(s.substring(r.last+1))
            val st=r.first;val en=st+(r.last-r.first+1).coerceIn(5,12);sb.setSpan(android.text.style.UnderlineSpan(),st,en,0)
            c.addView(TextView(a).apply{text=sb;textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.literata(a);gravity=Gravity.CENTER;cssLeading(1.6f)},LinearLayout.LayoutParams(-1,-2))}
        c.addView(TextView(a).apply{text=Words.meaning(w);textSize=if(s!=null&&r!=null)15f else 22f;setTextColor(if(s!=null&&r!=null)a.muted else a.fg);typeface=PolkaFonts.literata(a,false,s!=null&&r!=null);gravity=Gravity.CENTER;cssLeading(1.4f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        val field=EditText(a).apply{textSize=22f;setTextColor(a.fg);typeface=PolkaFonts.literata(a);gravity=Gravity.CENTER;background=a.shape(if(a.light)Color.WHITE else 0xff242426.toInt(),18)
            setPadding(a.d(16),0,a.d(16),0);isSingleLine=true;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setOnEditorActionListener{v,_,_->check(w,v as EditText,if(s!=null&&r!=null)s.substring(r.first,r.last+1) else w.str("w"));true}}
        c.addView(field,LinearLayout.LayoutParams(-1,a.d(56)).apply{topMargin=a.d(30)})
        val answer=TextView(a).apply{textSize=17f;setTextColor(a.mint);typeface=PolkaFonts.literata(a,true);gravity=Gravity.CENTER;visibility=View.GONE;tag="answer"}
        c.addView(answer,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})
        field.post{field.requestFocus();(a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field,0)}
        return ScrollView(a).apply{isVerticalScrollBarEnabled=false;addView(c)}
    }
    private fun check(w:JSONObject,field:EditText,inSentence:String){if(answered)return
        val typed=Dictionary.key(field.text.toString());if(typed.isBlank())return;answered=true
        val forms=(w.optJSONArray("forms")?.let{f->(0 until f.length()).map{f.getString(it)}}?:emptyList()).toSet()+w.str("k")+Dictionary.key(inSentence)
        val secs=(android.os.SystemClock.uptimeMillis()-shownAt)/1000.0
        field.isEnabled=false
        if(typed in forms){field.background=GradientDrawableCompat.box(a,(a.mint and 0xffffff) or (0x29 shl 24),a.mint,18);result(w,if(secs<8)4 else if(secs<20)3 else 2);handler.postDelayed({hideKeyboard();advance()},700)}
        else{field.background=GradientDrawableCompat.box(a,(a.rose and 0xffffff) or (0x29 shl 24),a.rose,18)
            (field.parent as ViewGroup).findViewWithTag<TextView>("answer")?.let{it.text=inSentence;it.visibility=View.VISIBLE}
            result(w,1);hideKeyboard();showNext()}}

    /** Grades the first try of a word in this lesson; a word got wrong comes once more at the end (twice at most). */
    private fun result(w:JSONObject,g:Int){val id=w.str("id")
        if(tried.add(id)){Words.grade(a,w,g);if(Words.known(w))learned+=id}
        queue.removeFirst()
        if(g==1){val n=(misses[id]?:0)+1;misses[id]=n;if(n<=2)queue.addLast(w) else done++} else done++}
    private fun showNext(){next.visibility=View.VISIBLE;if(!reduced){next.alpha=0f;next.animate().alpha(1f).setDuration(MotionSpec.ASK_IN).start()}}
    private fun advance(){if(dialog==null)return;ask(false)}
    private fun finish(){
        bar.set(1f);next.visibility=View.GONE;hideKeyboard()
        val box=a.column().apply{gravity=Gravity.CENTER}
        box.addView(a.text("Готово",34f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER})
        val n=total;val unit=if(n%100 in 11..14)"слов" else when(n%10){1->"слово";2,3,4->"слова";else->"слов"}
        val k=learned.size
        box.addView(a.text("$n $unit"+if(k>0)", $k из них теперь знаешь" else "",15.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(10);gravity=Gravity.CENTER_HORIZONTAL})
        stage.removeAllViews();stage.addView(box,FrameLayout.LayoutParams(-1,-1))
        next.text="Закрыть";next.setOnClickListener{close()};next.visibility=View.VISIBLE
    }
}

/** A rounded box with a fill and a 1.5 dp edge (the colours of a right or wrong answer). */
internal object GradientDrawableCompat{
    fun box(a:MainActivity,fill:Int,edge:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(fill);cornerRadius=a.d(radius).toFloat();setStroke((a.resources.displayMetrics.density*1.5f).toInt().coerceAtLeast(1),edge)}
}

/** Chapter titles that are only a number («11», «XIV») read as «Глава 11» (or «Chapter 11» in an English book). */
internal object ChapterTitles{
    private val number=Regex("^(\\d{1,4}|[IVXLCDM]{1,8})\\.?$")
    fun pretty(title:String,english:Boolean=false):String{val t=title.trim();val m=number.find(t)?:return title
        return (if(english)"Chapter " else "Глава ")+m.groupValues[1]}
}
