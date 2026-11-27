package app.polka.nativeapp
import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import org.json.JSONObject
import kotlin.math.roundToInt

/** A rating in half stars: 0 — none, 1…10 — half a star to five stars. Kept in the book as 0.5…5 («rating»). */
internal object Rating{
    fun halves(b:JSONObject)=(b.optDouble("rating",0.0).let{if(it.isNaN())0.0 else it}*2).roundToInt().coerceIn(0,10)
    fun put(b:JSONObject,h:Int){if(h<=0)b.remove("rating") else if(h%2==0)b.put("rating",h/2) else b.put("rating",h/2.0)}
    /** «4», «4,5». */
    fun text(h:Int)=if(h%2==0)"${h/2}" else "${h/2},5"
}

/**
 * Five stars in half steps: the left half of a star gives half of it, the right half the whole star, and a finger
 * drawn along the row picks as it goes. The fill sweeps to the new value; the chosen star gives a short press.
 * Tapping the chosen value again clears it when [toggle].
 */
class NativeRating(val a:MainActivity,initial:Int=0,val toggle:Boolean=true,private val starDp:Float=30f,private val gapDp:Float=10f,private val center:Boolean=true,val changed:(Int)->Unit):View(a) {
    /** Half stars, 0…10. */
    var value=initial.coerceIn(0,10); private set
    private var shown=value/2f
    private var sweep:ValueAnimator?=null
    private var press=FloatArray(5){1f}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
    private val star get()=a.d(1)*starDp
    private val gap get()=a.d(1)*gapDp
    private val rowWidth get()=star*5+gap*4
    private val left get()=if(center)(width-rowWidth)/2f else 0f
    private var downValue=0;private var dragged=false;private var downX=0f
    init{isClickable=true;isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;describe()}
    override fun onMeasure(w:Int,h:Int){val want=kotlin.math.ceil(rowWidth).toInt();val mw=MeasureSpec.getMode(w);val sw=MeasureSpec.getSize(w)
        val width=when(mw){MeasureSpec.EXACTLY->sw;MeasureSpec.AT_MOST->if(center)sw else minOf(want,sw);else->want}
        setMeasuredDimension(width,resolveSize((star+a.d(12)).roundToInt(),h))}
    private fun describe(){contentDescription=if(value==0)"Без оценки" else "Оценка ${Rating.text(value)} из 5"}
    /** The half step under [x]. */
    private fun at(x:Float):Int{val rel=x-left;if(rel<=0f)return 1
        val i=(rel/(star+gap)).toInt().coerceIn(0,4);val inStar=rel-i*(star+gap)
        return if(inStar>star)(i+1)*2 else i*2+if(inStar<star/2)1 else 2}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);downValue=value;dragged=false;downX=e.x;set(at(e.x),false)}
            MotionEvent.ACTION_MOVE->{if(kotlin.math.abs(e.x-downX)>a.d(6))dragged=true;set(at(e.x),false)}
            MotionEvent.ACTION_UP->{val v=at(e.x);if(!dragged&&toggle&&v==downValue)set(0,false) else set(v,false)
                hop(value);changed(value);performClick()}
            MotionEvent.ACTION_CANCEL->set(downValue,false)}
        return true}
    override fun performClick():Boolean{super.performClick();return true}
    /** Shows [v] (half stars); [silent] — no haptics. */
    fun set(v:Int,silent:Boolean){val n=v.coerceIn(0,10);if(n==value)return;value=n;describe()
        if(!silent)haptic(a,Haptic.SELECT)
        sweep?.cancel();val from=shown;val to=n/2f
        if(reduced||!isAttachedToWindow){shown=to;invalidate();return}
        sweep=ValueAnimator.ofFloat(0f,1f).apply{duration=(Motion.FAST+(kotlin.math.abs(to-from)*40).toLong()).coerceAtMost(Motion.EMPHASIS);interpolator=MotionSpec.enter
            addUpdateListener{shown=from+(to-from)*(it.animatedValue as Float);invalidate()};start()}}
    private fun hop(v:Int){if(v<=0||reduced)return;val i=(v+1)/2-1
        ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.EMPHASIS;addUpdateListener{val t=it.animatedFraction;press[i]=if(t<.25f)1f-.14f*(t/.25f) else .86f+.14f*Motion.expand.interpolator.getInterpolation((t-.25f)/.75f);invalidate()};start()}}
    override fun onDraw(c:Canvas){
        val path=StarShape.path;val y=(height-star)/2f;val yellow=StarRow.starYellow(a)
        for(i in 0 until 5){val x=left+i*(star+gap);val f=(shown-i).coerceIn(0f,1f)
            c.save();c.translate(x+star/2,y+star/2);c.scale(press[i],press[i]);c.translate(-star/2,-star/2);c.scale(star/24,star/24)
            // an empty star is a grey outline (the yellow one was 2:1 on white, too faint to count)
            if(f<1f){p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=Ui.icon(a.light);c.drawPath(path,p)}
            if(f>0f){c.save();c.clipRect(0f,0f,24f*f,24f);p.color=yellow;p.style=Paint.Style.FILL_AND_STROKE;p.strokeWidth=1.5f;c.drawPath(path,p);c.restore()}
            c.restore()}
    }
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar"
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)}
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean{
        val step=when(action){android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->1;android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->-1;else->return super.performAccessibilityAction(action,args)}
        set(value+step,true);changed(value);return true}
}

/** The star used for ratings: five points with every corner rounded off, outer and inner alike. */
internal object StarShape {
    val path:Path by lazy {
        val pts=ArrayList<PointF>()
        for(k in 0 until 10){ val r=if(k%2==0) 9.9f else 4.3f; val ang=Math.toRadians((-90+k*36).toDouble())
            pts.add(PointF(12f+r*Math.cos(ang).toFloat(),12.9f+r*Math.sin(ang).toFloat())) }
        val out=Path()
        for(k in 0 until 10){ val v=pts[k]; val pv=pts[(k+9)%10]; val nx=pts[(k+1)%10]; val cut=if(k%2==0) 1.7f else .9f
            fun toward(o:PointF):PointF{ val dx=o.x-v.x; val dy=o.y-v.y; val len=Math.hypot(dx.toDouble(),dy.toDouble()).toFloat(); return PointF(v.x+dx/len*cut,v.y+dy/len*cut) }
            val from=toward(pv); val to=toward(nx)
            if(k==0) out.moveTo(from.x,from.y) else out.lineTo(from.x,from.y)
            out.quadTo(v.x,v.y,to.x,to.y) }
        out.close(); out }
}
