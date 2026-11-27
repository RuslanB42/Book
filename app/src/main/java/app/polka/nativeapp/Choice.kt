package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.*

/**
 * The marks of a choice in a row (after the reference the user sent): they answer the finger while it is down (the
 * box goes soft, the ring stretches) and change with a small motion of their own, not a blink. They
 * follow their row's press (the row is what is tapped), so they never take the touch themselves.
 */
internal abstract class ChoiceMark(protected val a:MainActivity,on:Boolean):View(a){
    /** How far the mark is in its «on» look (0..1, eased inside each drawing). */
    protected var v=if(on)1f else 0f
    /** Going off (the way out differs from the way in), not on. */
    protected var leaving=false
    /** How much the finger is on it (0..1). */
    protected var press=0f
    private var anim:ValueAnimator?=null;private var pressAnim:ValueAnimator?=null
    protected val reduced get()=a.prefs.str("motion")=="reduced"
    var on=on;set(x){if(x==field)return;field=x;go(x)}
    protected abstract val inMs:Long;protected abstract val outMs:Long
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isDuplicateParentStateEnabled=true}
    private fun go(x:Boolean){anim?.cancel();leaving=!x
        haptic(a,Haptic.SELECT)
        if(reduced){v=if(x)1f else 0f;invalidate();return}
        // a mark that changes before it has finished changing goes on from where it is
        val from=if(x)(if(v>=1f)0f else v) else (if(v<=0f)1f else v)
        anim=ValueAnimator.ofFloat(from,if(x)1f else 0f).apply{duration=((if(x)inMs else outMs)*abs((if(x)1f else 0f)-from)).toLong().coerceAtLeast(1);interpolator=LinearInterpolator()
            addUpdateListener{v=it.animatedValue as Float;invalidate()};start()}}
    override fun drawableStateChanged(){super.drawableStateChanged();val p=isPressed;if(reduced)return
        val target=if(p)1f else 0f;if((pressAnim?.isRunning==true&&pressTarget==target)||press==target)return;pressTarget=target
        pressAnim?.cancel();pressAnim=ValueAnimator.ofFloat(press,target).apply{duration=if(p)140 else 260;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{press=it.animatedValue as Float;invalidate()};start()}}
    private var pressTarget=-1f
    override fun onDetachedFromWindow(){anim?.cancel();pressAnim?.cancel();super.onDetachedFromWindow()}
    protected fun ease(t:Float):Float{val x=t.coerceIn(0f,1f);return 1f-(1f-x)*(1f-x)*(1f-x)}
    /** 0 → 1 → 0 with its top at [at]: the little swell as a mark settles. */
    protected fun bump(t:Float,at:Float=.6f):Float{val x=t.coerceIn(0f,1f);return if(x<at)sin(x/at*PI.toFloat()/2f) else cos((x-at)/(1f-at)*PI.toFloat()/2f)}
    protected val outline get()=if(a.light)0x40000000 else 0x5affffff
}

/** A square box to tick (several may be ticked): an outline when empty; ticked, it fills from its middle and the check
 *  draws itself in; unticked, the check runs back out and the fill fades. Under the finger it sinks a little and its
 *  corners round off, all of it one smooth outline. */
internal class TickBox(a:MainActivity,on:Boolean):ChoiceMark(a,on){
    override val inMs=340L;override val outMs=280L
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path();private val check=Path();private val part=Path();private val measure=PathMeasure()
    private fun box(l:Float,t:Float,r:Float,b:Float,rad:Float){path.rewind();path.addRoundRect(l,t,r,b,rad,rad,Path.Direction.CW)}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val k=a.d(1)*1.75f
        val fill=if(leaving)ease((v-.35f)/.65f) else ease(v/.5f)
        val tick=if(leaving)ease(v/.45f) else ease((v-.42f)/.58f)
        val swell=if(leaving)0f else .07f*bump(v)
        c.save();val s=1f-.06f*press+swell;c.scale(s,s,w/2f,h/2f)
        box(k/2,k/2,w-k/2,h-k/2,a.d(7)+a.d(2)*press)
        // the outline fades as the fill comes; the fill comes in pale first, then whole
        if(fill<1f){p.style=Paint.Style.STROKE;p.strokeWidth=k;p.color=outline;p.alpha=(Color.alpha(outline)*(1f-fill)).toInt();c.drawPath(path,p)}
        if(fill>0f){p.style=Paint.Style.FILL;p.color=a.fg;p.alpha=(255*(.22f+.78f*fill)*min(1f,fill*4f)).toInt()
            c.save();val g=.72f+.28f*fill;c.scale(g,g,w/2f,h/2f);c.drawPath(path,p);c.restore()}
        if(tick>0f){val u=w*.68f/24f;val ox=(w-24f*u)/2f;val oy=(h-24f*u)/2f
            check.rewind();check.moveTo(ox+5f*u,oy+12.5f*u);check.lineTo(ox+9.8f*u,oy+17.2f*u);check.lineTo(ox+19f*u,oy+7.2f*u)
            measure.setPath(check,false);part.rewind();measure.getSegment(0f,max(.01f,measure.length*tick),part,true)
            p.style=Paint.Style.STROKE;p.strokeWidth=2.6f*u;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=a.bg;c.drawPath(part,p)}
        c.restore()}
}

/** A round mark for the one choice of several: an empty ring; chosen, it fills pale with a wide hole that closes in
 *  to the ring (a little past and back) as the ink comes; unchosen, the hole closes to a dot, then the whole mark
 *  fades. Under the finger it stretches wide. */
internal class RadioMark(a:MainActivity,on:Boolean):ChoiceMark(a,on){
    override val inMs=360L;override val outMs=320L
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val k=a.d(1)*1.75f;val r=min(w,h)/2f-k/2f;val outer=r+k/2f;val hole=outer*.42f
        c.save();c.scale(1f+.16f*press,1f-.06f*press,w/2f,h/2f)
        // how much of the mark is ink (0: only the empty ring) and how big its hole is (0: a whole dot)
        val ink:Float;val open:Float
        if(leaving){val u=1f-v;open=hole*(1f-ease(u/.4f));ink=1f-ease((u-.3f)/.7f)}
        else{ink=ease(v/.45f);open=hole+(outer*.85f-hole)*(1f-ease(v))-hole*.18f*bump(v,.72f)}
        if(ink<1f){p.style=Paint.Style.STROKE;p.strokeWidth=k;p.color=outline;p.alpha=(Color.alpha(outline)*(1f-ink)).toInt();c.drawCircle(w/2f,h/2f,r,p)}
        if(ink>0f){val rw=(outer-open).coerceIn(.5f,outer);p.style=Paint.Style.STROKE;p.strokeWidth=rw;p.color=a.fg;p.alpha=(255*(.25f+.75f*ink)*min(1f,ink*4f)).toInt()
            c.drawCircle(w/2f,h/2f,outer-rw/2f,p)}
        c.restore()}
}
