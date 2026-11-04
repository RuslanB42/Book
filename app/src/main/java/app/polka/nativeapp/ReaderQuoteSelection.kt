package app.polka.nativeapp

import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*

/** Chapter-global endpoints survive replacement of the TextView page fragment. */
internal class ChapterSelection {
    var start=0;private set
    var end=0;private set
    private var source:CharSequence?=null
    private val breaks=java.text.BreakIterator.getWordInstance(java.util.Locale("ru"))
    fun word(text:CharSequence,offset:Int):IntRange? {
        if(text.isEmpty())return null
        var p=offset.coerceIn(0,text.length-1)
        while(p<text.length-1 && text[p].isWhitespace())p++
        if(text[p].isWhitespace())return null
        if(source!==text){source=text;breaks.setText(text.toString())}
        val from=if(breaks.isBoundary(p))p else breaks.preceding(p).coerceAtLeast(0)
        val to=breaks.following(p).takeIf{it!=java.text.BreakIterator.DONE} ?: text.length
        return from until to
    }
    fun begin(text:CharSequence,offset:Int):Boolean {source=null;val word=word(text,offset)?:return false;start=word.first;end=word.last+1;return true}
    fun move(text:CharSequence,offset:Int,atStart:Boolean):Boolean {
        val word=word(text,offset)?:return atStart
        return if(atStart){if(word.first<end){start=word.first;true}else{start=end;end=word.last+1;false}}
        else {if(word.last+1>start){end=word.last+1;false}else{end=start;start=word.first;true}}
    }
    fun set(s:Int,e:Int){start=s;end=e}
    /** Character-precise drag: the dragged end sits exactly at [offset]; crossing the other end swaps roles. Returns whether the start is being dragged. */
    fun drag(offset:Int,atStart:Boolean):Boolean {
        return if(atStart){if(offset<end){start=offset;true}else{start=end;end=maxOf(offset,start+1);false}}
        else {if(offset>start){end=offset;false}else{end=start;start=minOf(offset,end-1).coerceAtLeast(0);true}}
    }
    /** After release: ends grow to whole words and never start or end on a space. */
    fun snap(text:CharSequence){
        if(text.isEmpty())return
        var s=start.coerceIn(0,text.length);var e=end.coerceIn(0,text.length)
        while(s<e&&text[s].isWhitespace())s++;while(e>s&&text[e-1].isWhitespace())e--
        if(e<=s)return
        word(text,s)?.let{if(it.first<s&&!text[s-1].isWhitespace())s=it.first}
        word(text,e-1)?.let{if(it.last+1>e&&e<text.length&&!text[e].isWhitespace())e=it.last+1}
        start=s;end=e
    }
    fun text(full:CharSequence)=full.subSequence(start.coerceIn(0,full.length),end.coerceIn(0,full.length)).toString()
}

/** The five highlight colours of quotes; the last one chosen is used for the next quote. */
/** Background of a saved quote. Unlike BackgroundColorSpan its colour can change in place, so recolouring glides without re-setting the page. */
class QuoteInk(var color:Int):android.text.style.CharacterStyle(),android.text.style.UpdateAppearance{
    var anim:android.animation.ValueAnimator?=null
    override fun updateDrawState(tp:android.text.TextPaint){tp.bgColor=color}
}
internal object QuoteColors{
    val all=listOf("amber" to 0xf0a742,"mint" to 0x7cc98e,"sky" to 0x6fa8f0,"rose" to 0xef7fa8,"lavender" to 0xa98bf7)
    fun rgb(key:String?)=all.firstOrNull{it.first==key}?.second?:0xf0a742
    /** The highlight behind the text (the same soft strength for every colour). */
    fun ink(key:String?)=(0x48 shl 24) or rgb(key)
    fun last(a:MainActivity)=a.prefs.str("quoteColor","amber").let{k->if(all.any{it.first==k})k else "amber"}
}

/** A colour dot of the quote colour strip; the chosen one has a ring. */
internal class QuoteDot(private val a:MainActivity,val key:String):View(a){
    private var ring=0f;private var ringAnim:android.animation.ValueAnimator?=null
    var on=false;set(v){if(field==v){return};field=v;ringAnim?.cancel()
        if(!isAttachedToWindow||a.prefs.str("motion")=="reduced"){ring=if(v)1f else 0f;invalidate();return}
        ringAnim=android.animation.ValueAnimator.ofFloat(ring,if(v)1f else 0f).apply{duration=240;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{ring=it.animatedValue as Float;invalidate()};start()}}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val cx=width/2f;val cy=height/2f;val r=a.d(13).toFloat()
        if(ring>0f){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=a.fg;p.alpha=(255*ring).toInt();c.drawCircle(cx,cy,r+a.d(2)+a.d(2)*ring,p);p.alpha=255}
        p.style=Paint.Style.FILL;p.color=(0xff shl 24) or QuoteColors.rgb(key);c.drawCircle(cx,cy,r,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x2effffff;c.drawCircle(cx,cy,r,p)}
}

internal class ReaderQuoteSelection(private val a:MainActivity,private val reader:NativeReader):FrameLayout(a) {
    val range=ChapterSelection()
    var active=false;private set
    var dragging=false;private set
    private var atStart=false
    private var x=0f;private var y=0f
    private var edge=0;private var edgeLocked=false
    private val timer=Handler(Looper.getMainLooper())
    private val marker=Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight=Path();private val clip=RectF()
    private val startHandle=PointF();private val endHandle=PointF()
    private var startVisible=false;private var endVisible=false
    /** Offset from the finger to the text under the dragged handle, so the handle moves with the finger instead of jumping a line down. */
    private var grabDx=0f;private var grabDy=0f
    private var lastOff=-1
    private var dragLine=-1
    /** Offset under the finger with a little hysteresis between lines, so a finger resting on a line edge doesn't flicker between two lines. */
    private fun offsetAt(px:Float,py:Float):Int{val l=reader.body.layout?:return reader.pageBase();val ly=py-reader.body.paddingTop;var line=l.getLineForVertical(ly.toInt())
        if(dragLine in 0 until l.lineCount&&line!=dragLine){val top=l.getLineTop(dragLine);val bot=l.getLineBottom(dragLine);val slack=(bot-top)*.3f;if(ly>top-slack&&ly<bot+slack)line=dragLine}
        dragLine=line;return (reader.pageBase()+JustifiedGeometry.offset(reader.body,l,line,px-reader.body.paddingLeft).coerceIn(0,reader.body.length())).coerceAtMost(reader.pageLimit())}
    private var appear=1f
    private var settling=false;private var settleColor=0;private var settleStart=0;private var settleEnd=0;private var settle:android.animation.ValueAnimator?=null
    private lateinit var primary:TextView
    private lateinit var third:TextView
    private val strip=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(4),a.d(2),a.d(4),a.d(2));elevation=a.d(8).toFloat();a.controlSurface(this,22f,sheet=true);isClickable=true}
    private val dots=mutableListOf<QuoteDot>()
    /** The quote the strip recolours (chapter offsets), set when it opens. */
    private var stripQuote:Pair<Int,Int>?=null;private var stripOpen=false
    private val stripTimer=Runnable{hideStrip()}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val menu=a.line().apply{gravity=Gravity.CENTER;setPadding(a.d(4),a.d(2),a.d(4),a.d(2));elevation=a.d(8).toFloat();a.controlSurface(this,22f,sheet=true)}
    private val pageTimer=Runnable { val dir=edge;edge=0;if(active&&dragging&&!edgeLocked&&dir!=0){edgeLocked=true;menu.visibility=INVISIBLE;reader.turnWithSelection(dir){ok->edgeLocked=false;if(ok){val full=reader.selectionFullText();var boundary=if(dir>0)reader.pageBase() else (reader.pageLimit()-1).coerceAtLeast(0);if(dir<0)while(boundary>reader.pageBase()&&full[boundary].isWhitespace())boundary--;atStart=range.move(full,boundary,atStart)}else{Toast.makeText(a,"Выделение ограничено текущей главой",Toast.LENGTH_SHORT).show()};invalidate();showMenu();if(dragging){x=if(dir>0)reader.body.paddingLeft+a.d(8).toFloat()else width-reader.body.paddingRight-a.d(8).toFloat();y=if(dir>0)reader.body.paddingTop+a.d(12).toFloat()else min(height-a.d(60).toFloat(),reader.body.height.toFloat());}}} }
    init {
        setWillNotDraw(false);clipChildren=false;visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        fun action(label:String,fn:()->Unit):TextView{val v=a.text(label,14f,a.fg,true).apply{gravity=Gravity.CENTER;minHeight=a.d(44);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(a.d(4),0,a.d(4),0);pressable(this);setOnClickListener{fn()}};menu.addView(v,LinearLayout.LayoutParams(0,a.d(44),1f));return v}
        primary=action("Цитата"){quoteAction()}
        action("Копировать"){(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата",range.text(reader.selectionFullText())));clear()}
        third=action("Заметка"){thirdAction()}
        menu.addView(IconView(a,"x",a.muted).apply{contentDescription="Убрать выделение";isClickable=true;setPadding(a.d(12),a.d(12),a.d(12),a.d(12));setOnClickListener{clear()};pressable(this)},LinearLayout.LayoutParams(a.d(44),a.d(44)));addView(menu,LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=a.d(8);rightMargin=a.d(8)})
        // the colour strip: shown right after a quote is saved (or from «Цвет» on a saved one), in the menu's place
        strip.addView(a.text("Цвет",13f,a.muted).apply{setPadding(a.d(12),0,a.d(6),0)})
        QuoteColors.all.forEach{(key,_)->val dot=QuoteDot(a,key).apply{isClickable=true;contentDescription="Цвет цитаты";setOnClickListener{recolor(key)}};dots.add(dot);strip.addView(dot,LinearLayout.LayoutParams(a.d(40),a.d(44)))}
        strip.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
        strip.addView(a.text("Готово",13.5f,a.fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(12),0,a.d(12),0);minHeight=a.d(44);a.press(this);setOnClickListener{hideStrip()}})
        strip.visibility=GONE;addView(strip,LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=a.d(8);rightMargin=a.d(8)})
    }
    /** Long press: a saved quote under the finger is picked up whole (so it can be removed); otherwise the word. */
    fun begin(offset:Int){val hit=reader.quoteAt(offset);if(hit!=null)range.set(hit.second.first,hit.second.last+1) else if(!range.begin(reader.selectionFullText(),offset))return;start(true)}
    /** A tap on a saved quote selects it. */
    fun select(r:IntRange){range.set(r.first,r.last+1);start(false)}
    private fun start(haptic:Boolean){finishSettle();active=true;visibility=VISIBLE;dragging=false;grabDx=0f;grabDy=0f;contentDescription="Выделено: ${range.text(reader.selectionFullText())}"
        appear=if(reduced)1f else 0f;if(!reduced)android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=200;interpolator=android.view.animation.DecelerateInterpolator();addUpdateListener{appear=it.animatedValue as Float;invalidate()};start()}
        menu.visibility=INVISIBLE;invalidate();post{showMenu()};if(haptic&&a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)}
    fun clear(fadeOut:Boolean=true){val was=active;active=false;dragging=false;edgeLocked=false;timer.removeCallbacksAndMessages(null);edge=0
        if(was&&!reduced){if(!fadeOut){popOut(menu){menu.visibility=INVISIBLE;menu.alpha=1f;menu.scaleX=1f;menu.scaleY=1f};if(!settling&&!stripOpen)post{if(!active&&!settling&&!stripOpen&&fade<=0f)visibility=GONE};invalidate();return}
            fadeStart=range.start;fadeEnd=range.end;fadeAnim?.cancel();fadeAnim=android.animation.ValueAnimator.ofFloat(appear,0f).apply{duration=180;addUpdateListener{fade=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){fade=0f;if(!active&&!settling&&!stripOpen)visibility=GONE;invalidate()}});start()}
            popOut(menu){menu.visibility=INVISIBLE;menu.alpha=1f;menu.scaleX=1f;menu.scaleY=1f};invalidate();return}
        menu.visibility=INVISIBLE;if(!settling&&!stripOpen)visibility=GONE;invalidate()}
    private var fade=0f;private var fadeStart=0;private var fadeEnd=0;private var fadeAnim:android.animation.ValueAnimator?=null
    /** The strip with the five colours, in the menu's place, for the quote at [s, e). It goes away by itself. */
    private fun showStrip(s:Int,e:Int,color:String){stripQuote=s to e;stripOpen=true;visibility=VISIBLE
        dots.forEach{it.on=it.key==color};strip.translationY=menu.translationY;strip.visibility=VISIBLE
        if(!reduced){popIn(strip);dots.forEachIndexed{i,d->d.scaleX=.4f;d.scaleY=.4f;d.alpha=0f
            d.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60L+i*35L).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()}}
        removeCallbacks(stripTimer);postDelayed(stripTimer,3500)}
    private fun hideStrip(){removeCallbacks(stripTimer);if(!stripOpen)return;stripOpen=false
        val done={strip.visibility=GONE;strip.alpha=1f;strip.scaleX=1f;strip.scaleY=1f;if(!active&&!settling&&fade<=0f)visibility=GONE}
        popOut(strip){done()}}
    private fun recolor(key:String){val (s,e)=stripQuote?:return;val q=reader.quoteOverlapping(s,e)?:return;val old=q.first.optString("color")
        a.prefs.put("quoteColor",key);a.savePreferences();dots.forEach{it.on=it.key==key}
        if(old==key){removeCallbacks(stripTimer);postDelayed(stripTimer,2500);return}
        reader.recolorQuote(q.first,q.second,key,!reduced)
        if(!reduced){
            dots.firstOrNull{it.key==key}?.let{d->d.scaleX=.7f;d.scaleY=.7f;d.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()}}
        removeCallbacks(stripTimer);postDelayed(stripTimer,2500)}
    /** Third button: «Заметка» for a new selection, «Убрать» for a saved quote. */
    private fun thirdAction(){val existing=reader.quoteOverlapping(range.start,range.end)
        if(existing!=null){val r=existing.second;val ink=QuoteColors.ink(existing.first.optString("color"));reader.removeQuote(existing.first,r);settleOut(r.first,r.last+1,ink,ink and 0x00ffffff,280){};clear(false);return}
        val text=range.text(reader.selectionFullText());clear();reader.addNote(text)}
    private fun finishSettle(){settle?.end();settle=null}
    /** «Цитата» marks the selection: the selection tint deepens into the quote tint, then the mark stays in the text.
     *  «Убрать цитату» drops the mark: the tint fades out where it was. */
    private fun quoteAction(){
        val full=reader.selectionFullText();val s=range.start;val e=range.end;val existing=reader.quoteOverlapping(s,e)
        // a saved quote: «Цвет» opens the colour strip for it
        if(existing!=null){val r=existing.second;val y=menu.translationY;clear(false);menu.translationY=y;showStrip(r.first,r.last+1,existing.first.optString("color","amber"));return}
        val text=range.text(full);val color=QuoteColors.last(a);val y=menu.translationY;clear(false);menu.translationY=y
        if(reduced){reader.addQuote(s,e,text,color);showStrip(s,e,color);return}
        settleOut(s,e,0x65e7ac48,QuoteColors.ink(color),240){reader.addQuote(s,e,text,color)}
        showStrip(s,e,color)
    }
    private fun settleOut(s:Int,e:Int,from:Int,to:Int,ms:Long,done:()->Unit){
        if(reduced){done();return}
        settleStart=s;settleEnd=e;settleColor=from;settling=true;visibility=VISIBLE;menu.visibility=INVISIBLE
        settle=android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(),from,to).apply{duration=ms;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener{settleColor=it.animatedValue as Int;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){settling=false;done();if(!active&&!stripOpen)visibility=GONE;invalidate()}});start()}
    }
    /** The menu pops in next to the selection and names what the first action will do. */
    private fun showMenu(){if(!active||dragging||edgeLocked)return;val saved=reader.quoteOverlapping(range.start,range.end);hideStrip()
        primary.text=if(saved!=null)"● Цвет" else "Цитата";third.text=if(saved!=null)"Убрать" else "Заметка"
        third.setTextColor(if(saved!=null)0xffff8a7a.toInt() else a.fg)
        if(saved!=null){val sp=android.text.SpannableString("● Цвет");sp.setSpan(android.text.style.ForegroundColorSpan((0xff shl 24) or QuoteColors.rgb(saved.first.optString("color"))),0,1,0);primary.text=sp}
        val wasHidden=menu.visibility!=VISIBLE;positionMenu()
        if(wasHidden&&!reduced)popIn(menu) else{menu.alpha=1f;menu.scaleX=1f;menu.scaleY=1f}}
    private val glide=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
    /** Menus come out of the selection: a short fade with a small rise and growth, settling softly. */
    private fun popIn(v:View){v.animate().cancel();v.pivotX=v.width/2f;v.pivotY=if(v.translationY<startHandle.y)v.height.toFloat() else 0f
        v.alpha=0f;v.scaleX=.9f;v.scaleY=.9f;v.translationX=0f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(glide).start()}
    private fun popOut(v:View,end:()->Unit){if(reduced||v.visibility!=VISIBLE){end();return};v.animate().cancel()
        v.animate().alpha(0f).scaleX(.94f).scaleY(.94f).setDuration(150).setInterpolator(android.view.animation.PathInterpolator(.4f,0f,1f,1f)).withEndAction{end()}.start()}
    /** Buttons sink a little under the finger and come back when released. */
    private fun pressable(v:View){v.setOnTouchListener{view,e->if(reduced)return@setOnTouchListener false
        when(e.actionMasked){MotionEvent.ACTION_DOWN->view.animate().scaleX(.9f).scaleY(.9f).alpha(.7f).setDuration(90).start()
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()};false}}
    override fun onDetachedFromWindow(){timer.removeCallbacksAndMessages(null);removeCallbacks(stripTimer);super.onDetachedFromWindow()}
    private fun point(offset:Int,end:Boolean,p:PointF):Boolean {
        val layout=reader.body.layout?:return false;val base=reader.pageBase();val limit=reader.pageLimit()
        if(offset<base || offset>limit || (end&&offset==base)||(!end&&offset==limit))return false
        val local=(offset-base).coerceIn(0,reader.body.length())
        // The end handle belongs to the line of the last selected character, not to the next line.
        val line=layout.getLineForOffset(if(end&&local>0)local-1 else local)
        p.set(reader.body.paddingLeft+JustifiedGeometry.x(reader.body,layout,line,local),reader.body.paddingTop+JustifiedGeometry.band(reader.body,layout,line).second);return true
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        if(settling){val layout=reader.body.layout?:return;val base=reader.pageBase();val s=(settleStart-base).coerceIn(0,reader.body.length());val e=(settleEnd-base).coerceIn(0,reader.body.length())
            highlight.rewind();if(e>s){JustifiedGeometry.path(reader.body,layout,s,e,highlight,a.d(3).toFloat());c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=settleColor;c.drawPath(highlight,marker);c.restore()}}
        if(!active&&fade>0f){val layout=reader.body.layout?:return;val base=reader.pageBase();val fs=(fadeStart-base).coerceIn(0,reader.body.length());val fe=(fadeEnd-base).coerceIn(0,reader.body.length())
            highlight.rewind();if(fe>fs){JustifiedGeometry.path(reader.body,layout,fs,fe,highlight,a.d(3).toFloat());c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=0x65e7ac48;marker.alpha=(0x65*fade).toInt();c.drawPath(highlight,marker);c.restore();marker.alpha=255}}
        if(!active||edgeLocked)return
        val layout=reader.body.layout?:return;val base=reader.pageBase();val s=(range.start-base).coerceIn(0,reader.body.length());val e=(range.end-base).coerceIn(0,reader.body.length())
        highlight.rewind();if(e>s){JustifiedGeometry.path(reader.body,layout,s,e,highlight,a.d(3).toFloat());c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=0x65e7ac48;marker.alpha=(0x65*appear).toInt();c.drawPath(highlight,marker);c.restore()}
        marker.alpha=255
        startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle)
        marker.color=if(Color.luminance(reader.backgroundColor)>.4)0xff9b5c12.toInt()else 0xffefbd70.toInt();marker.strokeWidth=a.d(2).toFloat()
        marker.alpha=(255*appear).toInt();for((p,visible)in listOf(startHandle to startVisible,endHandle to endVisible)){if(visible){val lh=reader.body.paint.fontMetrics.let{it.descent*1.25f-it.ascent*1.02f};c.drawLine(p.x,p.y-lh,p.x,p.y+a.d(3),marker);c.drawCircle(p.x,p.y+a.d(8),a.d(6)*(.6f+.4f*appear),marker)}};marker.alpha=255
        if(edge!=0){marker.color=0xa6e7ac48.toInt();val cx=if(edge>0)width-a.d(13).toFloat()else a.d(13).toFloat();c.drawCircle(cx,y.coerceIn(a.d(30).toFloat(),height-a.d(30).toFloat()),a.d(11).toFloat(),marker)}
    }
    private fun positionMenu(){
        if(!active||dragging||edgeLocked)return
        startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle);menu.visibility=VISIBLE
        val menuHeight=menu.height.takeIf{it>0}?:a.d(48)
        val desired=when{
            startVisible&&startHandle.y>a.d(100)->startHandle.y-a.d(92)
            endVisible&&endHandle.y+a.d(28)+menuHeight<height-a.d(8)->endHandle.y+a.d(28)
            else->height-menuHeight-a.d(12).toFloat()
        }
        menu.translationY=desired.coerceIn(a.d(8).toFloat(),max(a.d(8).toFloat(),height-menuHeight-a.d(12).toFloat()))
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!active)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dragLine=-1;lastOff=-1;startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle);x=e.x;y=e.y;val ds=if(startVisible)hypot(x-startHandle.x,y-startHandle.y-a.d(4))else Float.MAX_VALUE;val de=if(endVisible)hypot(x-endHandle.x,y-endHandle.y-a.d(4))else Float.MAX_VALUE
                if(min(ds,de)>a.d(36)){clear();return true};atStart=ds<de;dragging=true
                val h=if(atStart)startHandle else endHandle;val lh=reader.body.paint.fontMetrics.let{it.descent*1.25f-it.ascent*1.02f}
                grabDx=h.x-x;grabDy=(h.y-lh/2f)-y;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{if(!dragging)return true;x=e.x;y=e.y;if(!edgeLocked){val off=offsetAt(x+grabDx,y+grabDy);if(off!=lastOff){lastOff=off;atStart=range.drag(off,atStart)}
                // The page turns only past the text block or at the very screen edge of its first/last line: reaching for the last word of a line no longer flips the page.
                val lines=reader.body.layout?.lineCount?:0;val edgeZone=a.d(12)
                val dir=when{!atStart&&(y>height-reader.body.paddingBottom+a.d(16)||(dragLine>=lines-1&&x>width-edgeZone))->1;atStart&&(y<reader.body.paddingTop-a.d(16)||(dragLine<=0&&x<edgeZone))->-1;else->0};if(dir!=edge){timer.removeCallbacks(pageTimer);edge=dir;if(dir!=0)timer.postDelayed(pageTimer,340)};invalidate()};return true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;edge=0;lastOff=-1;timer.removeCallbacks(pageTimer);parent.requestDisallowInterceptTouchEvent(false);range.snap(reader.selectionFullText());contentDescription="Выделено: ${range.text(reader.selectionFullText())}";showMenu();invalidate();return true}
        };return true
    }
    fun continueHold(e:MotionEvent){if(!active)return;if(e.actionMasked==MotionEvent.ACTION_MOVE&&!dragging){dragging=true;atStart=false;grabDx=0f;grabDy=0f;dragLine=-1;lastOff=-1;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true)};if(dragging)onTouchEvent(e)}
    internal fun handlePoint(start:Boolean):PointF {point(if(start)range.start else range.end,!start,if(start)startHandle else endHandle);return PointF(if(start)startHandle.x else endHandle.x,(if(start)startHandle.y else endHandle.y)+a.d(9))}
}
