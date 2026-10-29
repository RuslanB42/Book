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
    private val menu=a.line().apply{gravity=Gravity.CENTER;setPadding(a.d(4),a.d(2),a.d(4),a.d(2));elevation=a.d(8).toFloat();a.controlSurface(this,22f,sheet=true)}
    private val pageTimer=Runnable { val dir=edge;edge=0;if(active&&dragging&&!edgeLocked&&dir!=0){edgeLocked=true;menu.visibility=INVISIBLE;reader.turnWithSelection(dir){ok->edgeLocked=false;if(ok){val full=reader.selectionFullText();var boundary=if(dir>0)reader.pageBase() else (reader.pageLimit()-1).coerceAtLeast(0);if(dir<0)while(boundary>reader.pageBase()&&full[boundary].isWhitespace())boundary--;atStart=range.move(full,boundary,atStart)}else{Toast.makeText(a,"Выделение ограничено текущей главой",Toast.LENGTH_SHORT).show()};invalidate();positionMenu();if(dragging){x=if(dir>0)reader.body.paddingLeft+a.d(8).toFloat()else width-reader.body.paddingRight-a.d(8).toFloat();y=if(dir>0)reader.body.paddingTop+a.d(12).toFloat()else min(height-a.d(60).toFloat(),reader.body.height.toFloat());}}} }
    init {
        setWillNotDraw(false);clipChildren=false;visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        fun action(label:String,fn:()->Unit){menu.addView(a.text(label,14f,a.fg,true).apply{gravity=Gravity.CENTER;minHeight=a.d(44);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(a.d(4),0,a.d(4),0);a.press(this);setOnClickListener{fn()}},LinearLayout.LayoutParams(0,a.d(44),1f))}
        action("Цитата"){val text=range.text(reader.selectionFullText());val s=range.start;val e=range.end;clear();reader.addQuote(s,e,text)}
        action("Копировать"){(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата",range.text(reader.selectionFullText())));clear()}
        action("Заметка"){val text=range.text(reader.selectionFullText());clear();reader.addNote(text)}
        menu.addView(a.icon("x","Убрать выделение"){clear()},LinearLayout.LayoutParams(a.d(44),a.d(44)));addView(menu,LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{leftMargin=a.d(8);rightMargin=a.d(8)})
    }
    fun begin(offset:Int){if(!range.begin(reader.selectionFullText(),offset))return;active=true;visibility=VISIBLE;dragging=false;menu.visibility=VISIBLE;contentDescription="Выделено: ${range.text(reader.selectionFullText())}";invalidate();post{positionMenu()};if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)}
    fun clear(){active=false;dragging=false;edgeLocked=false;timer.removeCallbacksAndMessages(null);edge=0;visibility=GONE;invalidate()}
    override fun onDetachedFromWindow(){timer.removeCallbacksAndMessages(null);super.onDetachedFromWindow()}
    private fun point(offset:Int,end:Boolean,p:PointF):Boolean {
        val layout=reader.body.layout?:return false;val base=reader.pageBase();val limit=reader.pageLimit()
        if(offset<base || offset>limit || (end&&offset==base)||(!end&&offset==limit))return false
        val local=(offset-base).coerceIn(0,reader.body.length());val line=layout.getLineForOffset(local)
        p.set(reader.body.paddingLeft+layout.getPrimaryHorizontal(local),reader.body.paddingTop+layout.getLineBottom(line).toFloat());return true
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c);if(!active||edgeLocked)return
        val layout=reader.body.layout?:return;val base=reader.pageBase();val s=(range.start-base).coerceIn(0,reader.body.length());val e=(range.end-base).coerceIn(0,reader.body.length())
        highlight.rewind();if(e>s){layout.getSelectionPath(s,e,highlight);c.save();c.translate(reader.body.paddingLeft.toFloat(),reader.body.paddingTop.toFloat());marker.color=0x65e7ac48;c.drawPath(highlight,marker);c.restore()}
        startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle)
        marker.color=if(Color.luminance(reader.backgroundColor)>.4)0xff9b5c12.toInt()else 0xffefbd70.toInt();marker.strokeWidth=a.d(2).toFloat()
        for((p,visible)in listOf(startHandle to startVisible,endHandle to endVisible)){if(visible){c.drawLine(p.x,p.y-a.d(20),p.x,p.y+a.d(5),marker);c.drawCircle(p.x,p.y+a.d(9),a.d(6).toFloat(),marker)}}
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
            MotionEvent.ACTION_DOWN->{startVisible=point(range.start,false,startHandle);endVisible=point(range.end,true,endHandle);x=e.x;y=e.y;val ds=if(startVisible)hypot(x-startHandle.x,y-startHandle.y-a.d(9))else Float.MAX_VALUE;val de=if(endVisible)hypot(x-endHandle.x,y-endHandle.y-a.d(9))else Float.MAX_VALUE
                if(min(ds,de)>a.d(32)){clear();return true};atStart=ds<de;dragging=true;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{if(!dragging)return true;x=e.x;y=e.y;if(!edgeLocked){val dir=when{!atStart&&(x>width-reader.body.paddingRight+a.d(1)||y>height-reader.body.paddingBottom+a.d(8))->1;atStart&&(x<reader.body.paddingLeft-a.d(1)||y<reader.body.paddingTop-a.d(8))->-1;else->0};if(dir==0)atStart=range.move(reader.selectionFullText(),reader.selectionOffset(x,y),atStart);if(dir!=edge){timer.removeCallbacks(pageTimer);edge=dir;if(dir!=0)timer.postDelayed(pageTimer,340)};invalidate()};return true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;edge=0;timer.removeCallbacks(pageTimer);parent.requestDisallowInterceptTouchEvent(false);contentDescription="Выделено: ${range.text(reader.selectionFullText())}";positionMenu();invalidate();return true}
        };return true
    }
    fun continueHold(e:MotionEvent){if(!active)return;if(e.actionMasked==MotionEvent.ACTION_MOVE&&!dragging){dragging=true;atStart=false;menu.visibility=INVISIBLE;parent.requestDisallowInterceptTouchEvent(true)};if(dragging)onTouchEvent(e)}
    internal fun handlePoint(start:Boolean):PointF {point(if(start)range.start else range.end,!start,if(start)startHandle else endHandle);return PointF(if(start)startHandle.x else endHandle.x,(if(start)startHandle.y else endHandle.y)+a.d(9))}
}
