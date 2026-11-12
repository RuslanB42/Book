package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.*
import android.view.animation.PathInterpolator
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
    /** Called whenever the shown text is replaced (a new page), so read-aloud colouring can be put back. */
    var onNewText:(()->Unit)?=null
    override fun onTextChanged(text:CharSequence?,start:Int,before:Int,after:Int){super.onTextChanged(text,start,before,after);onNewText?.invoke()}
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

/** Words the reader learns: saved from the dictionary with the sentence they were met in, repeated by FSRS. */
internal object Words {
    private const val DAY=86_400_000L
    private const val DECAY=-0.5
    private const val FACTOR=19.0/81.0
    /** FSRS-4.5 default weights. */
    private val W=doubleArrayOf(0.4872,1.4003,3.7145,13.8206,5.1618,1.2298,0.8975,0.031,1.6474,0.1367,1.0461,2.1072,0.0793,0.3246,1.587,0.2272,2.8755)
    var filter="all"

    fun list(a:MainActivity):JSONArray=a.prefs.optJSONArray("words")?:JSONArray().also{a.prefs.put("words",it)}
    fun all(a:MainActivity)=list(a).objects()
    fun find(a:MainActivity,key:String,lang:String)=all(a).firstOrNull{it.str("k")==key&&it.str("lang")==lang}
    fun known(w:JSONObject)=w.optDouble("s",0.0)>=21.0
    fun due(a:MainActivity,now:Long=System.currentTimeMillis())=all(a).filter{it.optLong("due")<=now}.sortedBy{it.optLong("due")}
    /** 0..5 dots: how firmly the word sits. */
    fun level(w:JSONObject):Int{if(w.optInt("reps")==0)return 0;val s=w.optDouble("s",0.0);return when{s<1.5->1;s<4->2;s<10->3;s<21->4;else->5}}

    fun add(a:MainActivity,entry:JSONObject,surface:String,source:WordSource?,forms:List<String>):JSONObject{
        val lemma=entry.optString("w").ifBlank{surface};val lang=entry.optString("lang","ru")
        find(a,Dictionary.key(lemma),lang)?.let{return it}
        val tr=entry.optJSONArray("t");val senses=entry.optJSONArray("s")
        val w=JSONObject().put("id",uid()).put("w",lemma).put("k",Dictionary.key(lemma)).put("lang",lang)
            .put("t",if(tr==null)"" else (0 until tr.length()).joinToString(", "){tr.getString(it)})
            .put("d",senses?.optJSONArray(0)?.optString(0)?:"").put("p",entry.optString("p")).put("i",entry.optString("i"))
            .put("surf",surface).put("ctx",source?.sentence?:"").put("book",source?.bookId?:"").put("title",source?.title?:"")
            .put("forms",JSONArray((forms+Dictionary.key(lemma)+Dictionary.key(surface)).filter{it.isNotBlank()}.distinct()))
            .put("added",System.currentTimeMillis()).put("due",System.currentTimeMillis()).put("reps",0).put("lapses",0)
        list(a).put(w);a.savePreferences();return w
    }
    fun setForms(a:MainActivity,id:String,forms:List<String>){val w=all(a).firstOrNull{it.str("id")==id}?:return
        val have=w.optJSONArray("forms")?.let{f->(0 until f.length()).map{f.getString(it)}}?:emptyList()
        w.put("forms",JSONArray((have+forms).filter{it.isNotBlank()}.distinct()));a.savePreferences()}
    fun remove(a:MainActivity,id:String){a.prefs.put("words",JSONArray(all(a).filterNot{it.str("id")==id}));a.savePreferences()}

    private fun initD(g:Int)=(W[4]-exp(W[5]*(g-1))+1).coerceIn(1.0,10.0)
    /** One answer: «помню» (good) or «не помню». */
    fun grade(a:MainActivity,w:JSONObject,good:Boolean,now:Long=System.currentTimeMillis()){
        val g=if(good)3 else 1
        val s:Double;val d:Double
        if(w.optInt("reps")==0||!w.has("s")){s=W[g-1];d=initD(g)}
        else{val s0=w.optDouble("s",W[2]).coerceAtLeast(.1);val d0=w.optDouble("dif",5.0)
            val elapsed=max(0.0,(now-w.optLong("last",now))/DAY.toDouble())
            val r=(1+FACTOR*elapsed/s0).pow(DECAY)
            d=(W[7]*initD(4)+(1-W[7])*(d0-W[6]*(g-3))).coerceIn(1.0,10.0)
            s=if(good)s0*(exp(W[8])*(11-d0)*s0.pow(-W[9])*(exp(W[10]*(1-r))-1)+1)
              else min(s0,W[11]*d0.pow(-W[12])*((s0+1).pow(W[13])-1)*exp(W[14]*(1-r)))}
        w.put("s",s).put("dif",d).put("last",now).put("reps",w.optInt("reps")+1)
        if(!good)w.put("lapses",w.optInt("lapses")+1)
        w.put("due",if(good)now+max(1L,s.roundToLong())*DAY else now+10*60_000L)
        a.savePreferences()
    }

    /** Form key → word id, for underlining in the book text. */
    fun index(a:MainActivity):Map<String,String>{val m=HashMap<String,String>()
        all(a).forEach{w->val id=w.str("id");w.optJSONArray("forms")?.let{f->for(i in 0 until f.length())m[f.getString(i)]=id};m[w.str("k")]=id};return m}
    private val token=Regex("[\\p{L}\\u0301\\u0300'’-]+")
    fun mark(text:SpannableStringBuilder,index:Map<String,String>){
        if(index.isEmpty())return
        for(m in token.findAll(text)){var s=m.range.first;var e=m.range.last+1
            while(e>s&&(text[e-1]=='-'||text[e-1]=='\''||text[e-1]=='’'))e--;while(s<e&&(text[s]=='-'||text[s]=='\''||text[s]=='’'))s++
            if(e<=s)continue
            val id=index[Dictionary.key(text.substring(s,e))]?:continue
            text.setSpan(LearnMark(id),s,e,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
    }
    /** The sentence around a selection, short enough for a card. */
    fun sentence(full:CharSequence,start:Int,end:Int):String{
        if(full.isEmpty())return ""
        val stops=".!?…\n"
        var s=start.coerceIn(0,full.length);var e=end.coerceIn(s,full.length)
        var n=0;while(s>0&&n<220&&full[s-1] !in stops){s--;n++}
        n=0;while(e<full.length&&n<220&&full[e] !in stops){e++;n++};if(e<full.length&&full[e]!='\n')e++
        var out=full.subSequence(s,e).toString().replace(' ',' ').replace(Regex("\\s+")," ").trim().trimStart('—','–','-',' ')
        if(out.length>240){val mid=(start-s).coerceIn(0,out.length);val from=(mid-110).coerceAtLeast(0);out=(if(from>0)"…" else "")+out.substring(from,(from+220).coerceAtMost(out.length)).trim()+"…"}
        return out
    }
    private var tts:TextToSpeech?=null;private var ttsReady=false
    fun speak(c:Context,text:String,lang:String){
        val go={tts?.language=Locale(lang);tts?.speak(text.replace("́",""),TextToSpeech.QUEUE_FLUSH,null,"w")}
        if(tts==null)tts=TextToSpeech(c.applicationContext){if(it==TextToSpeech.SUCCESS){ttsReady=true;go()}} else if(ttsReady)go()
    }
    /** The sentence with the word in bold. */
    fun context(a:MainActivity,w:JSONObject,size:Float,color:Int,strong:Int):TextView{
        val ctx=w.str("ctx");val sb=SpannableStringBuilder(ctx)
        val forms=(w.optJSONArray("forms")?.let{f->(0 until f.length()).map{f.getString(it)}}?:emptyList()).toSet()
        for(m in token.findAll(ctx)){if(Dictionary.key(m.value.trim('-','\'','’')) in forms){
            sb.setSpan(android.text.style.ForegroundColorSpan(strong),m.range.first,m.range.last+1,0)
            sb.setSpan(TypefaceSpanCompat(PolkaFonts.literata(a,true)),m.range.first,m.range.last+1,0)}}
        return TextView(a).apply{text=sb;textSize=size;setTextColor(color);typeface=PolkaFonts.literata(a,false,true);cssLeading(1.5f)}
    }
}

internal class TypefaceSpanCompat(private val face:Typeface):android.text.style.MetricAffectingSpan(){
    override fun updateDrawState(tp:android.text.TextPaint){tp.typeface=face}
    override fun updateMeasureState(tp:android.text.TextPaint){tp.typeface=face}
}

/** Five small dots: how firmly a word sits. */
internal class LevelDots(private val a:MainActivity,private val level:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(5*6+4*3),a.d(8))
    override fun onDraw(c:Canvas){val r=a.d(3).toFloat();val step=a.d(9).toFloat()
        for(i in 0 until 5){p.color=if(i<level)a.fg else (a.fg and 0xffffff) or (0x2a shl 24);c.drawCircle(r+i*step,height/2f,r,p)}}
}

private fun MainActivity.quietFill()=if(light)0x0f000000 else 0x0fffffff
private fun MainActivity.roundButton(kind:String,desc:String,size:Int=36,fn:()->Unit)=FrameLayout(this).apply{
    background=shape(quietFill(),size/2);isClickable=true;contentDescription=desc;press(this);setOnClickListener{fn()}
    addView(LineIcon(this@roundButton,kind,fg).apply{this.size=18f},FrameLayout.LayoutParams(-1,-1))}

/** The «Слова» tab: a due deck on top, then every word as a card. */
fun MainActivity.wordsPage(){
    val words=Words.all(this)
    if(words.isEmpty()){space(page,96);page.addView(emptyView("words","Пока нет слов","Выделите слово в книге, откройте «Словарь» и нажмите «Учить»."),LinearLayout.LayoutParams(-1,-2));return}
    val learning=words.filterNot{Words.known(it)};val known=words.filter{Words.known(it)}
    val chips=line()
    listOf(Triple("all","Все",words.size),Triple("learn","Учу",learning.size),Triple("known","Выучено",known.size)).forEach{(key,label,n)->
        val on=Words.filter==key
        val chip=line().apply{gravity=Gravity.CENTER;setPadding(d(16),0,d(16),0);background=shape(if(on)fg else quietFill(),19);isClickable=true;press(this);setOnClickListener{Words.filter=key;renderLibraryResults()}}
        chip.addView(text(label,14f,if(on)bg else fg,true));chip.addView(text("$n",14f,((if(on)bg else fg) and 0xffffff) or (0x8c shl 24),true).apply{setPadding(d(6),0,0,0)})
        chips.addView(chip,LinearLayout.LayoutParams(-2,d(38)).apply{marginEnd=d(8)})}
    page.addView(chips);space(page,16)
    val due=Words.due(this)
    if(due.isNotEmpty()){
        val stack=QuoteStackLayer(this,3)
        val card=line().apply{floatCard(this,22);setPadding(d(20),d(18),d(18),d(18))}
        card.addView(TextView(this).apply{text="${due.size}";textSize=36f;setTextColor(fg);typeface=PolkaFonts.sans(600);includeFontPadding=false})
        val t=column();t.addView(text("Пора повторить",16f,fg,true));t.addView(text("около ${maxOf(1,(due.size*15+59)/60)} мин",13.5f,muted))
        card.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=d(14)})
        card.addView(text("Начать",14.5f,bg,true).apply{gravity=Gravity.CENTER;setPadding(d(20),0,d(20),0);background=shape(fg,22);isClickable=true;press(this);setOnClickListener{WordReview(this@wordsPage).start()}},LinearLayout.LayoutParams(-2,d(44)))
        stack.addView(card);page.addView(stack,LinearLayout.LayoutParams(-1,-2));space(page,18)}
    val shown=when(Words.filter){"learn"->learning;"known"->known;else->words}.sortedByDescending{it.optLong("added")}
    if(shown.isEmpty()){space(page,40);page.addView(text(if(Words.filter=="known")"Выученных слов пока нет" else "Все слова выучены",15f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2));return}
    shown.forEach{w->page.addView(wordCard(w));space(page,12)}
}

private fun MainActivity.wordCard(w:JSONObject):View{
    val card=column().apply{floatCard(this,20);setPadding(d(18),d(16),d(16),d(14))}
    val head=TextView(this).apply{val sb=SpannableStringBuilder(w.str("w"));if(w.str("i").isNotBlank()){val st=sb.length+2;sb.append("  ").append(w.str("i"));sb.setSpan(android.text.style.AbsoluteSizeSpan(13,true),st,sb.length,0);sb.setSpan(android.text.style.ForegroundColorSpan(muted),st,sb.length,0);sb.setSpan(TypefaceSpanCompat(PolkaFonts.sans(400)),st,sb.length,0)}
        text=sb;textSize=22f;setTextColor(fg);typeface=PolkaFonts.literata(this@wordCard,true)}
    card.addView(head)
    if(w.str("t").isNotBlank()||w.str("d").isNotBlank())card.addView(TextView(this).apply{text=w.str("t").ifBlank{w.str("d")};textSize=16f;setTextColor(fg);typeface=PolkaFonts.literata(this@wordCard)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(4)})
    if(w.str("ctx").isNotBlank())card.addView(Words.context(this,w,14.5f,muted,fg),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(10)})
    card.addView(View(this).apply{setBackgroundColor(if(light)0x12000000 else 0x12ffffff)},LinearLayout.LayoutParams(-1,1).apply{topMargin=d(12);bottomMargin=d(10)})
    val foot=line()
    if(w.str("title").isNotBlank())foot.addView(text(w.str("title"),12.5f,muted,true).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(12),0,d(12),0);background=shape(quietFill(),15);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=d(170)},LinearLayout.LayoutParams(-2,d(30)))
    foot.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
    foot.addView(LevelDots(this,Words.level(w)),LinearLayout.LayoutParams(-2,-2).apply{marginEnd=d(10)})
    foot.addView(roundButton("speaker","Произнести"){Words.speak(this,w.str("w"),w.str("lang"))},LinearLayout.LayoutParams(d(36),d(36)))
    foot.addView(roundButton("trash","Удалить слово"){Words.remove(this,w.str("id"));renderLibraryResults()},LinearLayout.LayoutParams(d(36),d(36)).apply{marginStart=d(8)})
    card.addView(foot,LinearLayout.LayoutParams(-1,-2))
    return card
}

/** Review as a deck: tap flips the card, swipe right is «помню», left is «не помню». */
internal class WordReview(private val a:MainActivity){
    private val queue=ArrayDeque<JSONObject>();private val misses=HashMap<String,Int>()
    private var total=0;private var done=0
    private var dialog:android.app.Dialog?=null
    private lateinit var deck:FrameLayout;private lateinit var counter:TextView;private lateinit var bar:Progress;private lateinit var buttons:LinearLayout;private lateinit var hint:TextView
    private var front:View?=null;private var flipped=false;private var busy=false
    private val reduced get()=a.prefs.str("motion")=="reduced"

    private inner class Progress:View(a){
        var v=0f
            set(x){field=x;invalidate()}
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){val r=height/2f;p.color=(a.fg and 0xffffff) or (0x18 shl 24);c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,p);p.color=a.fg;c.drawRoundRect(0f,0f,max(height.toFloat(),width*v),height.toFloat(),r,r,p)}}

    fun start(){
        queue.clear();queue.addAll(Words.due(a));total=queue.size;done=0;if(total==0)return
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=FrameLayout(a).apply{setBackgroundColor(a.bg)}
        val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(IconView(a,"x",a.muted).apply{contentDescription="Закрыть";isClickable=true;setOnClickListener{close()}},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        bar=Progress();top.addView(bar,LinearLayout.LayoutParams(0,a.d(4),1f).apply{marginStart=a.d(6);marginEnd=a.d(14)})
        counter=a.text("",13f,a.muted,true);top.addView(counter)
        val col=a.column()
        col.addView(top,LinearLayout.LayoutParams(-1,a.d(48)))
        deck=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
        col.addView(deck,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=a.d(16);bottomMargin=a.d(14)})
        hint=a.text("Влево — не помню, вправо — помню",12.5f,a.muted).apply{gravity=Gravity.CENTER};col.addView(hint,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(10)})
        buttons=a.line()
        buttons.addView(a.text("Не помню",16f,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(a.quietFill(),28);isClickable=true;a.press(this);setOnClickListener{answer(false)}},LinearLayout.LayoutParams(0,a.d(56),1f))
        buttons.addView(View(a),LinearLayout.LayoutParams(a.d(10),1))
        buttons.addView(a.text("Помню",16f,a.bg,true).apply{gravity=Gravity.CENTER;background=a.shape(a.fg,28);isClickable=true;a.press(this);setOnClickListener{answer(true)}},LinearLayout.LayoutParams(0,a.d(56),1f))
        col.addView(buttons,LinearLayout.LayoutParams(-1,-2))
        val lp=FrameLayout.LayoutParams(-1,-1);root.addView(col,lp);dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(a.bg));w.setLayout(-1,-1);w.setWindowAnimations(0);ReaderWindow.configure(w,false,a.bg)
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);col.setPadding(a.d(18)+s.left,a.d(8)+s.top,a.d(18)+s.right,a.d(20)+s.bottom)}
        dlg.setOnKeyListener{_,c,e->if(c==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.show()
        deck.post{deal(true)}
    }
    private fun close(){dialog?.dismiss();dialog=null;if(a.tab=="words")a.renderLibraryResults()}
    private fun status(){counter.text="${minOf(done+1,total)} из $total";bar.v=done.toFloat()/total}

    private fun sheet(level:Int):View=View(a).apply{a.floatCard(this,28,if(a.light)(if(level==1)0xfff7f7f5.toInt() else 0xffefefec.toInt()) else (if(level==1)0xff202022.toInt() else 0xff1c1c1e.toInt()),if(a.light)5-level else 7-level)}
    /** Lays the next card with the pile under it. */
    private fun deal(first:Boolean){
        deck.removeAllViews();front=null;flipped=false;busy=false
        val w=queue.firstOrNull();if(w==null){finish();return};status()
        val left=queue.size
        if(left>2)deck.addView(sheet(2),FrameLayout.LayoutParams(-1,-1).apply{setMargins(a.d(28),0,a.d(28),a.d(24))})
        if(left>1)deck.addView(sheet(1),FrameLayout.LayoutParams(-1,-1).apply{setMargins(a.d(14),a.d(11),a.d(14),a.d(12))})
        val card=FrameLayout(a).apply{a.floatCard(this,28,if(a.light)Color.WHITE else 0xff242426.toInt(),if(a.light)10 else 14);cameraDistance=a.d(9000).toFloat()}
        card.addView(face(w,false),FrameLayout.LayoutParams(-1,-1))
        deck.addView(card,FrameLayout.LayoutParams(-1,-1).apply{topMargin=a.d(22)});front=card
        touch(card,w)
        if(!reduced&&!first){card.alpha=0f;card.scaleX=.96f;card.scaleY=.96f;card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
    }
    private fun face(w:JSONObject,back:Boolean):View{
        val c=a.column().apply{setPadding(a.d(24),a.d(22),a.d(24),a.d(22));gravity=Gravity.CENTER_HORIZONTAL}
        val src=a.line()
        if(w.str("title").isNotBlank()){src.addView(LineIcon(a,"book",a.muted).apply{size=15f},LinearLayout.LayoutParams(a.d(16),a.d(16)));src.addView(a.text(w.str("title"),12.5f,a.muted,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(a.d(8),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))}
        else src.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
        if(back)src.addView(a.roundButton("speaker","Произнести"){Words.speak(a,w.str("w"),w.str("lang"))},LinearLayout.LayoutParams(a.d(36),a.d(36)))
        c.addView(src,LinearLayout.LayoutParams(-1,a.d(36)))
        c.addView(View(a),LinearLayout.LayoutParams(1,0,1f))
        c.addView(TextView(a).apply{text=w.str("w");textSize=if(back)32f else 38f;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
        if(!back){
            if(w.str("i").isNotBlank())c.addView(a.text(w.str("i"),15f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
            if(w.str("ctx").isNotBlank())c.addView(Words.context(a,w,17f,a.muted,a.fg).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(22)})
        }else{
            if(w.str("t").isNotBlank())c.addView(TextView(a).apply{text=w.str("t");textSize=21f;setTextColor(a.fg);typeface=PolkaFonts.literata(a);gravity=Gravity.CENTER;cssLeading(1.35f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
            if(w.str("d").isNotBlank())c.addView(TextView(a).apply{text=w.str("d");textSize=16f;setTextColor(a.muted);typeface=PolkaFonts.literata(a,false,true);gravity=Gravity.CENTER;cssLeading(1.45f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16)})
        }
        c.addView(View(a),LinearLayout.LayoutParams(1,0,1f))
        c.addView(a.text(if(back)w.str("p") else "Нажмите, чтобы перевернуть",12.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
        return c
    }
    private fun flip(w:JSONObject){
        val card=front as? FrameLayout?:return;if(busy)return
        val swap={card.removeAllViews();flipped=!flipped;card.addView(face(w,flipped),FrameLayout.LayoutParams(-1,-1))}
        if(reduced){swap();return}
        busy=true
        card.animate().rotationY(90f).setDuration(140).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{
            swap();card.rotationY=-90f
            card.animate().rotationY(0f).setDuration(180).setInterpolator(PathInterpolator(0f,0f,.2f,1f)).withEndAction{busy=false}.start()}.start()
    }
    private fun touch(card:View,w:JSONObject){
        val slop=ViewConfiguration.get(a).scaledTouchSlop;var x0=0f;var y0=0f;var moving=false;var tracker:VelocityTracker?=null
        card.setOnTouchListener{v,e->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{x0=e.rawX;y0=e.rawY;moving=false;tracker?.recycle();tracker=VelocityTracker.obtain();tracker?.addMovement(e);v.animate().cancel();true}
                MotionEvent.ACTION_MOVE->{tracker?.addMovement(e);val dx=e.rawX-x0;val dy=e.rawY-y0
                    if(!moving&&abs(dx)>slop&&abs(dx)>abs(dy)){moving=true;v.parent?.requestDisallowInterceptTouchEvent(true)}
                    if(moving&&!busy){v.translationX=dx;v.translationY=dy*.25f;v.rotation=dx/v.width*12f};true}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{tracker?.addMovement(e);tracker?.computeCurrentVelocity(1000);val vx=tracker?.xVelocity?:0f;tracker?.recycle();tracker=null
                    val dx=v.translationX
                    if(!moving){if(e.actionMasked==MotionEvent.ACTION_UP)flip(w)}
                    else if(abs(dx)>v.width*.3f||abs(vx)>a.d(900))fly(dx+vx*.1f>0)
                    else v.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(220).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
                    moving=false;true}
                else->false}}
    }
    private fun answer(good:Boolean){if(busy)return;fly(good)}
    private fun fly(good:Boolean){
        val card=front?:return;val w=queue.firstOrNull()?:return;if(busy&&card.rotationY!=0f)return;busy=true
        Words.grade(a,w,good);queue.removeFirst()
        if(good)done++ else{val n=(misses[w.str("id")]?:0)+1;misses[w.str("id")]=n;if(n<3)queue.addLast(w) else done++}
        if(reduced){deal(false);return}
        val dir=if(good)1f else -1f
        card.animate().translationX(dir*deck.width*1.3f).translationY(card.translationY+a.d(30)).rotation(dir*18f).alpha(.6f).setDuration(260).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{deal(false)}.start()
    }
    private fun finish(){
        bar.v=1f;counter.text="$total из $total";buttons.visibility=View.GONE;hint.visibility=View.GONE
        val box=a.column().apply{gravity=Gravity.CENTER}
        box.addView(a.text("Готово",28f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER})
        box.addView(a.text("Повторено слов: $total. Следующие придут, когда их пора будет вспомнить.",14f,a.muted).apply{gravity=Gravity.CENTER;cssLeading(1.45f);maxWidth=a.d(260)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(8);gravity=Gravity.CENTER_HORIZONTAL})
        box.addView(a.text("Закрыть",15f,a.bg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(28),0,a.d(28),0);background=a.shape(a.fg,24);isClickable=true;a.press(this);setOnClickListener{close()}},LinearLayout.LayoutParams(-2,a.d(48)).apply{topMargin=a.d(22);gravity=Gravity.CENTER_HORIZONTAL})
        deck.removeAllViews();deck.addView(box,FrameLayout.LayoutParams(-1,-1))
    }
}
