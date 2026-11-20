package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.FrameLayout

/** Values copied from the final CSS / WAAPI rules, not the overridden early styles. */
object MotionSpec {
    const val SHELF=460L
    val ease=PathInterpolator(.25f,.1f,.25f,1f)
    val settle=PathInterpolator(.22f,1f,.36f,1f)
}
data class MotionPose(val alpha:Float=1f,val x:Float=0f,val y:Float=0f,val scale:Float=1f)
class NativeMotion(val view:View,val from:MotionPose,val to:MotionPose=MotionPose(),val duration:Long,val delay:Long=0,val curve:TimeInterpolator=MotionSpec.ease,val complete:()->Unit={}) {
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
    private var tabClock:ValueAnimator?=null
    private var tabView:View?=null
    private var tabDirection=1f
    private var tabWidth=0f
    private val tabCurve=PathInterpolator(.25f,.1f,.25f,1f)
    internal fun freezeTab(){tabClock?.removeAllListeners();tabClock?.cancel();tabClock=null}
    internal fun sampleTab(ms:Long){
        val q=tabCurve.getInterpolation((ms/300f).coerceIn(0f,1f))
        tabView?.translationX=tabDirection*tabWidth*(1-q)
        ghost?.translationX=-tabDirection*tabWidth*q
        if(ms>=300)finishTab()
    }
    private fun finishTab(){freezeTab();tabView?.translationX=0f;tabView?.clipBounds=null;tabView=null;ghost?.let{(it.parent as? ViewGroup)?.removeView(it)};ghost=null}

    /** Tab switch with the REAL outgoing page (no snapshot): it keeps its exact look — shadows, glass, state —
     *  and slides out while the new page, top bar included, slides in. */
    fun pushTab(old:View?,opening:Boolean){
        if(old==null||a.prefs.str("motion")=="reduced"||a.root.width<=0)return
        (old.parent as? ViewGroup)?.removeView(old)
        old.isEnabled=false
        val blocker=object:FrameLayout(a){override fun onInterceptTouchEvent(e:android.view.MotionEvent)=true;override fun onTouchEvent(e:android.view.MotionEvent)=true}
        blocker.addView(old,FrameLayout.LayoutParams(-1,-1))
        ghost=blocker;tabView=(a.page.parent as? View)?:a.page;tabDirection=if(opening)1f else -1f;tabWidth=a.root.width.toFloat()
        a.root.addView(blocker,1.coerceAtMost(a.root.childCount),FrameLayout.LayoutParams(-1,-1))
        // While the two pages slide side by side each is cut to its own width: rows that scroll sideways (shelves,
        // covers) reach past the screen edge and would otherwise show over the neighbouring page.
        // (sized from the screen, not from the new page: that page is not laid out yet, a clip from its zero height hid
        // it for the whole slide — the screen blinked)
        (tabView as? View)?.let{v->v.clipBounds=android.graphics.Rect(0,-a.root.height,a.root.width,a.root.height*3)}
        blocker.clipBounds=android.graphics.Rect(0,-a.root.height,a.root.width,a.root.height*2)
        sampleTab(0)
        tabClock=ValueAnimator.ofFloat(0f,1f).apply{duration=300;interpolator=LinearInterpolator();addUpdateListener{sampleTab(currentPlayTime)};start()}
    }
    fun stop() { finishTab();motions.toList().forEach { it.cancel() }; motions.clear(); ghost?.let { (it.parent as? ViewGroup)?.removeView(it) }; ghost=null }
    fun play(view:View,from:MotionPose,duration:Long,delay:Long=0,curve:TimeInterpolator=MotionSpec.ease) {
        if(a.prefs.str("motion")=="reduced") return
        motions.add(NativeMotion(view,from,duration=duration,delay=delay,curve=curve).start())
    }
}
