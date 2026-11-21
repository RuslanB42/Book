package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.FrameLayout

/**
 * One rule for every page change (approved «Движение Glyph»): how a page moves depends only on where you go.
 * Tabs are neighbours (a short shift, variant «Б»), a page deeper in comes from the right over the one it came from,
 * sheets rise from below, a book opens through the dark. Three curves serve the whole app.
 */
object MotionSpec {
    /** Everything that comes in. */
    val enter=PathInterpolator(.2f,0f,0f,1f)
    /** Everything that goes away. */
    val exit=PathInterpolator(.4f,0f,1f,1f)
    /** Back, and changes in place. */
    val move=PathInterpolator(.4f,0f,.2f,1f)
    const val TAB_OUT=120L;const val TAB_IN=260L;const val TAB_IN_DELAY=60L;const val TAB_SHIFT=30
    const val DEEP_IN=340L;const val DEEP_BACK=280L;
    /** Let go too early (a swipe back, a sheet pulled down): back in place. */
    const val SETTLE=280L
   const val DEEP_SHIFT=.3f;const val DEEP_DIM=.35f
    const val SHEET_IN=380L;const val SHEET_OUT=240L
    /** A question or a picture shown over the page, in place. */
    const val ASK_IN=220L;const val ASK_OUT=150L
    const val DARK_IN=240L;const val DARK_OUT=320L
    /** A switch in place (week, month, year): the pill, the figures and the chart arrive together. */
    const val SWITCH=420L
    /** A page built anew in place. */
    const val PAGE_IN=340L
}
data class MotionPose(val alpha:Float=1f,val x:Float=0f,val y:Float=0f,val scale:Float=1f)
class NativeMotion(val view:View,val from:MotionPose,val to:MotionPose=MotionPose(),val duration:Long,val delay:Long=0,val curve:TimeInterpolator=MotionSpec.enter,val complete:()->Unit={}) {
    private var animator:ValueAnimator?=null
    private var ended=false
    private val detach=object:View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v:View) {} ; override fun onViewDetachedFromWindow(v:View) { cancel() } }
    fun sample(elapsed:Long) { val t=curve.getInterpolation(((elapsed-delay).toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)); fun mix(x:Float,y:Float)=x+(y-x)*t
        view.alpha=mix(from.alpha,to.alpha); view.translationX=mix(from.x,to.x); view.translationY=mix(from.y,to.y); view.scaleX=mix(from.scale,to.scale); view.scaleY=view.scaleX }
    fun start():NativeMotion { view.addOnAttachStateChangeListener(detach); sample(0); animator=ValueAnimator.ofFloat(0f,1f).apply { duration=this@NativeMotion.duration+delay; interpolator=LinearInterpolator(); addUpdateListener { sample(currentPlayTime) }; addListener(object:AnimatorListenerAdapter() { override fun onAnimationEnd(animation:Animator) { finish() } }); start() }; return this }
    private fun finish(commit:Boolean=true) { if(ended)return; ended=true; animator?.removeAllListeners(); animator?.cancel(); animator=null; sample(duration+delay); view.removeOnAttachStateChangeListener(detach); if(commit)complete() }
    fun cancel()=finish(false)
}
class ScreenMotion(private val a:MainActivity) {
    private val motions=mutableListOf<NativeMotion>()
    private var ghost:View?=null
    private var tabView:View?=null
    private val tabAnims=mutableListOf<android.view.ViewPropertyAnimator>()
    private fun finishTab(){tabAnims.forEach{it.cancel()};tabAnims.clear();tabView?.let{it.translationX=0f;it.alpha=1f};tabView=null;ghost?.let{(it.parent as? ViewGroup)?.removeView(it)};ghost=null}

    /** Tabs are neighbours, not deeper: the REAL outgoing page (no snapshot) fades out shifting a little toward the
     *  side it is left for, the new one comes in from the other side the same short way (approved variant «Б»). */
    fun pushTab(old:View?,opening:Boolean){
        if(old==null||a.prefs.str("motion")=="reduced"||a.root.width<=0)return
        (old.parent as? ViewGroup)?.removeView(old)
        old.isEnabled=false
        val blocker=object:FrameLayout(a){override fun onInterceptTouchEvent(e:android.view.MotionEvent)=true;override fun onTouchEvent(e:android.view.MotionEvent)=true}
        blocker.addView(old,FrameLayout.LayoutParams(-1,-1))
        ghost=blocker;val page=a.scroll;tabView=page
        a.root.addView(blocker,1.coerceAtMost(a.root.childCount),FrameLayout.LayoutParams(-1,-1))
        val dir=if(opening)1f else -1f;val shift=a.d(MotionSpec.TAB_SHIFT)*dir
        tabAnims+=blocker.animate().translationX(-shift).alpha(0f).setDuration(MotionSpec.TAB_OUT).setInterpolator(MotionSpec.exit).withEndAction{if(ghost===blocker){(blocker.parent as? ViewGroup)?.removeView(blocker);ghost=null}}.also{it.start()}
        page.translationX=shift;page.alpha=0f
        tabAnims+=page.animate().translationX(0f).alpha(1f).setStartDelay(MotionSpec.TAB_IN_DELAY).setDuration(MotionSpec.TAB_IN).setInterpolator(MotionSpec.enter).withEndAction{if(tabView===page)tabView=null}.also{it.start()}
    }
    fun stop() { finishTab();motions.toList().forEach { it.cancel() }; motions.clear() }
    fun play(view:View,from:MotionPose,duration:Long,delay:Long=0,curve:TimeInterpolator=MotionSpec.enter) {
        if(a.prefs.str("motion")=="reduced") return
        motions.add(NativeMotion(view,from,duration=duration,delay=delay,curve=curve).start())
    }
}

/**
 * «Вглубь»: the page on [top] comes in from the right edge while everything beneath it (its siblings: the live page or
 * a picture of it) moves a third of the way left and darkens. Back is the same motion reversed, and a swipe back drives
 * it under the finger. [at] 0 is the top page fully in, 1 fully away to the right.
 */
internal class Depth(private val a:MainActivity,private val top:View){
    private val host get()=top.parent as? ViewGroup
    private val dim=View(a).apply{setBackgroundColor(Color.BLACK);alpha=0f;outlineProvider=null;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var under=emptyList<View>()
    private var topZ=0f
    private var clock:ValueAnimator?=null
    var at=0f;private set
    private fun attach(){val h=host?:return;if(dim.parent!=null)return
        under=(0 until h.childCount).map{h.getChildAt(it)}.filter{it!==top&&it!==dim}
        dim.elevation=(under.maxOfOrNull{it.z}?:0f)+a.d(1)
        h.addView(dim,h.indexOfChild(top),ViewGroup.LayoutParams(-1,-1))
        topZ=top.translationZ;if(top.z<=dim.elevation)top.translationZ=dim.elevation+a.d(1)-top.elevation}
    private fun detach(){(dim.parent as? ViewGroup)?.removeView(dim);top.translationZ=topZ;under.forEach{it.translationX=0f};under=emptyList()}
    /** Puts the pages where they are at [p] (0 the top page in, 1 gone to the right). */
    fun pose(p:Float){attach();at=p;val w=(host?.width?:a.root.width).toFloat()
        top.translationX=w*p;under.forEach{it.translationX=-w*MotionSpec.DEEP_SHIFT*(1-p)};dim.alpha=MotionSpec.DEEP_DIM*(1-p)}
    private fun run(to:Float,ms:Long,curve:TimeInterpolator,done:()->Unit){stop();val from=at
        clock=ValueAnimator.ofFloat(from,to).apply{duration=ms;interpolator=curve;addUpdateListener{pose(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){var cancelled=false;override fun onAnimationCancel(x:Animator){cancelled=true}
                override fun onAnimationEnd(x:Animator){if(!cancelled){detach();done()}}});start()}}
    fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
    /** The top page comes in. */
    fun enter(done:()->Unit={}){if(a.prefs.str("motion")=="reduced"){pose(0f);detach();done();return};pose(1f);run(0f,MotionSpec.DEEP_IN,MotionSpec.enter,done)}
    /** The top page goes back to the right, from wherever it is now. */
    fun leave(done:()->Unit={}){if(a.prefs.str("motion")=="reduced"){detach();done();return};if(dim.parent==null)pose(0f)
        run(1f,(MotionSpec.DEEP_BACK*(1-at)).toLong().coerceAtLeast(90L),MotionSpec.move,done)}
    /** Let go too early in a swipe back: the top page settles in again. */
    fun settle(done:()->Unit={}){run(0f,MotionSpec.SETTLE,MotionSpec.move,done)}
}

/**
 * Where a sheet is: 0 up, 1 down out of sight. Rising (toward 0) it comes in, sinking (toward 1) it goes away; a part of
 * the way takes its part of the time. No bounce: sheets move like every other page (see [MotionSpec]).
 */
internal class SheetMotion(private val changed:(Float)->Unit){
    var value=0f;private set
    private var clock:ValueAnimator?=null
    fun snap(to:Float){stop();value=to;changed(value)}
    fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
    fun to(target:Float){stop();val from=value;if(from==target){changed(value);return}
        val rising=target<from;val part=kotlin.math.abs(target-from).coerceIn(.4f,1f)
        clock=ValueAnimator.ofFloat(from,target).apply{duration=((if(rising)MotionSpec.SHEET_IN else MotionSpec.SHEET_OUT)*part).toLong()
            interpolator=if(rising)MotionSpec.enter else MotionSpec.exit;addUpdateListener{value=it.animatedValue as Float;changed(value)};start()}}
}
