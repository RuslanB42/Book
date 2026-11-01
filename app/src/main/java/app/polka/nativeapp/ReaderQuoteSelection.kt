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
        dragLine=line;return (reader.pageBase()+l.getOffsetForHorizontal(line,px-reader.body.paddingLeft).coerceIn(0,reader.body.length())).coerceAtMost(reader.pageLimit())}
    private var appear=1f
    private var settling=false;private var settleColor=0;private var settleStart=0;private var settleEnd=0;private var settle:android.animation.ValueAnimator?=null
    private lateinit var primary:TextView
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val menu=a.line().apply{gravity=Gravity.CENTER;setPadding(a.d(4),a.d(2),a.d(4),a.d(2));elevation=a.d(8).toFloat();a.controlSurface(this,22f,sheet=true)}
    private val pageTimer=Runnable { val dir=edge;edge=0;if(active&&dragging&&!edgeLocked&&dir!=0){edgeLocked=true;menu.visibility=INVISIBLE;reader.turnWithSelection(dir){ok->edgeLocked=false;if(ok){val full=reader.selectionFullText();var boundary=if(dir>0)reader.pageBase() else (reader.pageLimit()-1).coerceAtLeast(0);if(dir<0)while(boundary>reader.pageBase()&&full[boundary].isWhitespace())boundary--;atStart=range.move(full,boundary,atStart)}else{Toast.makeText(a,"Выделение ограничено текущей главой",Toast.LENGTH_SHORT).show()};invalidate();showMenu();if(dragging){x=if(dir>0)reader.body.paddingLeft+a.d(8).toFloat()else width-reader.body.paddingRight-a.d(8).toFloat();y=if(dir>0)reader.body.paddingTop+a.d(12).toFloat()else min(height-a.d(60).toFloat(),reader.body.height.toFloat());}}} }
    init {
        setWillNotDraw(false);clipChildren=false;visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        fun action(label:String,fn:()->Unit):TextView{val v=a.text(label,14f,a.fg,true).apply{gravity=Gravity.CENTER;minHeight=a.d(44);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(a.d(4),0,a.d(4),0);a.press(this);setOnClickListener{fn()}};menu.addView(v,LinearLayout.LayoutParams(0,a.d(44),1f));return v}
        primary=action("Цитата"){quoteAction()}
        action("Копировать"){(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата",range.text(reader.selectionFullText())));clear()}
        action("Заметка"){val text=range.text(reader.selectionFullText());clear();reader.addNote(text)}
        menu.addView(a.icon("x","Убрать выделение"){clear()},LinearLayout.LayoutParams(a.d(44),a.d(44)));addView(menu,LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=a.d(8);rightMargin=a.d(8)})
    }
    /** Long press: a saved quote under the finger is picked up whole (so it can be removed); otherwise the word. */
    fun begin(offset:Int){val hit=reader.quoteAt(offset);if(hit!=null)range.set(hit.second.first,hit.second.last+1) else if(!range.begin(reader.selectionFullText(),offset))return;start(true)}
    /** A tap on a saved quote selects it. */
    fun select(r:IntRange){range.set(r.first,r.last+1);start(false)}
    private fun start(haptic:Boolean){finishSettle();active=true;visibility=VISIBLE;dragging=false;grabDx=0f;grabDy=0f;contentDescription="Выделено: ${range.text(reader.selectionFullText())}"
        appear=if(reduced)1f else 0f;if(!reduced)android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=200;interpolator=android.view.animation.DecelerateInterpolator();addUpdateListener{appear=it.animatedValue as Float;invalidate()};start()}
        menu.visibility=INVISIBLE;invalidate();post{showMenu()};if(haptic&&a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)}
    fun clear(){active=false;dragging=false;edgeLocked=false;timer.removeCallbacksAndMessages(null);edge=0;if(!settling)visibility=GONE;invalidate()}
    private fun finishSettle(){settle?.end();settle=null}
    /** «Цитата» marks the selection: the selection tint deepens into the quote tint, then the mark stays in the text.
     *  «Убрать цитату» drops the mark: the tint fades out where it was. */
    private fun quoteAction(){
        val full=reader.selectionFullText();val s=range.start;val e=range.end;val existing=reader.quoteOverlapping(s,e)
        if(existing!=null){val r=existing.second;reader.removeQuote(existing.first,r);settleOut(r.first,r.last+1,0x44f0a742,0x00f0a742,280){};clear();return}
        val text=range.text(full);clear()
        if(reduced){reader.addQuote(s,e,text);return}
        settleOut(s,e,0x65e7ac48,0x44f0a742,240){reader.addQuote(s,e,text)}
    }
    private fun settleOut(s:Int,e:Int,from:Int,to:Int,ms:Long,done:()->Unit){
        if(reduced){done();return}
        settleStart=s;settleEnd=e;settleColor=from;settling=true;visibility=VISIBLE;menu.visibility=INVISIBLE
        settle=android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(),from,to).apply{duration=ms;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener{settleColor=it.animatedValue as Int;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){settling=false;done();if(!active)visibility=GONE;invalidate()}});start()}
    }
    /** The menu pops in next to the selection and names what the first action will do. */
    private fun showMenu(){if(!active||dragging||edgeLocked)return;primary.text=if(reader.quoteOverlapping(range.start,range.end)!=null)"Убрать цитату" else "Цитата"
        val wasHidden=menu.visibility!=VISIBLE;positionMenu()
        if(wasHidden&&!reduced){menu.alpha=0f;menu.scaleX=.94f;menu.scaleY=.94f;menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()}else menu.alpha=1f}
    override fun onDetachedFromWindow(){timer.removeCallbacksAndMessages(null);super.onDetachedFromWindow()}
    private fun point(offset:Int,end:Boolean,p:PointF):Boolean {
        val layout=reader.body.layout?:return false;val base=reader.pageBase();val limit=reader.pageLimit()
        if(offset<base || offset>limit || (end&&offset==base)||(!end&&offset==limit))return false
        val local=(offset-base).coerceIn(0,reader.body.length());val line=layout.getLineForOffset(local)
        p.set(reader.body.paddingLeft+layout.getPrimaryHorizontal(local),reader.body.paddingTop+layout.getLineBottom(line).toFloat());return true
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        if(settling){val layout=reader.body.layout?:return;val base=reader.pageBase();val s=(settleStart-base).coerceIn(0,reader.body.length());val e=(settleEnd-base).coerceIn(0,reader.body.length())
            highlight.rewind();if(e>s){layout.getSelectionPath(s,e,highlight);c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=settleColor;c.drawPath(highlight,marker);c.restore()}}
        if(!active||edgeLocked)return
        val layout=reader.body.layout?:return;val base=reader.pageBase();val s=(range.start-base).coerceIn(0,reader.body.length());val e=(range.end-base).coerceIn(0,reader.body.length())
        highlight.rewind();if(e>s){layout.getSelectionPath(s,e,highlight);c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=0x65e7ac48;marker.alpha=(0x65*appear).toInt();c.drawPath(highlight,marker);c.restore()}
        marker.alpha=255
        startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle)
        marker.color=if(Color.luminance(reader.backgroundColor)>.4)0xff9b5c12.toInt()else 0xffefbd70.toInt();marker.strokeWidth=a.d(2).toFloat()
        marker.alpha=(255*appear).toInt();for((p,visible)in listOf(startHandle to startVisible,endHandle to endVisible)){if(visible){c.drawLine(p.x,p.y-a.d(20),p.x,p.y+a.d(5),marker);c.drawCircle(p.x,p.y+a.d(9),a.d(6)*(.6f+.4f*appear),marker)}};marker.alpha=255
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
            MotionEvent.ACTION_DOWN->{dragLine=-1;lastOff=-1;startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle);x=e.x;y=e.y;val ds=if(startVisible)hypot(x-startHandle.x,y-startHandle.y-a.d(9))else Float.MAX_VALUE;val de=if(endVisible)hypot(x-endHandle.x,y-endHandle.y-a.d(9))else Float.MAX_VALUE
                if(min(ds,de)>a.d(32)){clear();return true};atStart=ds<de;dragging=true
                val h=if(atStart)startHandle else endHandle;val lh=reader.body.layout?.let{l->val local=((if(atStart)range.start else range.end)-reader.pageBase()).coerceIn(0,reader.body.length());val line=l.getLineForOffset(if(!atStart&&local>0)local-1 else local);(l.getLineBottom(line)-l.getLineTop(line)).toFloat()}?:a.d(24).toFloat()
                grabDx=h.x-x;grabDy=(h.y-lh/2f)-y;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{if(!dragging)return true;x=e.x;y=e.y;if(!edgeLocked){val dir=when{!atStart&&(x>width-reader.body.paddingRight+a.d(1)||y>height-reader.body.paddingBottom+a.d(8))->1;atStart&&(x<reader.body.paddingLeft-a.d(1)||y<reader.body.paddingTop-a.d(8))->-1;else->0};if(dir==0){val off=offsetAt(x+grabDx,y+grabDy);if(off!=lastOff){lastOff=off;atStart=range.drag(off,atStart)}};if(dir!=edge){timer.removeCallbacks(pageTimer);edge=dir;if(dir!=0)timer.postDelayed(pageTimer,340)};invalidate()};return true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;edge=0;lastOff=-1;timer.removeCallbacks(pageTimer);parent.requestDisallowInterceptTouchEvent(false);range.snap(reader.selectionFullText());contentDescription="Выделено: ${range.text(reader.selectionFullText())}";showMenu();invalidate();return true}
        };return true
    }
    fun continueHold(e:MotionEvent){if(!active)return;if(e.actionMasked==MotionEvent.ACTION_MOVE&&!dragging){dragging=true;atStart=false;grabDx=0f;grabDy=0f;dragLine=-1;lastOff=-1;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true)};if(dragging)onTouchEvent(e)}
    internal fun handlePoint(start:Boolean):PointF {point(if(start)range.start else range.end,!start,if(start)startHandle else endHandle);return PointF(if(start)startHandle.x else endHandle.x,(if(start)startHandle.y else endHandle.y)+a.d(9))}
}
