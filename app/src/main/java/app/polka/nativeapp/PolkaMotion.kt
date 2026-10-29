package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.FrameLayout

/** Values copied from the final CSS / WAAPI rules, not the overridden early styles. */
object MotionSpec {
    const val TAB=420L; const val SHELF=460L; const val SHEET=500L; const val SCRIM=400L
    const val QUOTE_CARD=420L; const val QUOTE_GHOST=190L; const val QUOTE_HEADER=280L
    const val INDICATOR=320L; const val SEGMENT=300L; const val CHAPTER=540L
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
    private fun finishTab(){freezeTab();tabView?.translationX=0f;tabView=null;ghost?.let{(it.parent as? ViewGroup)?.removeView(it)};ghost=null}

    fun stop() { finishTab();motions.toList().forEach { it.cancel() }; motions.clear(); ghost?.let { (it.parent as? ViewGroup)?.removeView(it) }; ghost=null }
    fun play(view:View,from:MotionPose,duration:Long,delay:Long=0,curve:TimeInterpolator=MotionSpec.ease) {
        if(a.prefs.str("motion")=="reduced") return
        motions.add(NativeMotion(view,from,duration=duration,delay=delay,curve=curve).start())
    }
    /** A full-width adjacent-page push. No page alpha, scale, blur or vertical movement.
     * Capture the current composited presentation before render/stop, including an interrupted
     * outgoing layer, so a rapid second tap never snaps back to a previous resting page. */
    fun captureOutgoing(view:View,opening:Boolean):()->Unit {
        if(a.prefs.str("motion")=="reduced" || view.width<=0 || !view.isAttachedToWindow)return {}
        val origin=IntArray(2);val at=IntArray(2);a.root.getLocationOnScreen(origin);view.getLocationOnScreen(at)
        val left=a.root.paddingLeft;val top=maxOf(a.root.paddingTop,at[1]-origin[1])
        val width=a.root.width-left-a.root.paddingRight;val height=a.root.height-top-a.root.paddingBottom
        if(width<=0||height<=0)return {}
        val scale=1f;val image=Bitmap.createBitmap((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.scale(scale,scale);canvas.drawColor(a.bg)
        fun drawPresentation(v:View){val pos=IntArray(2);v.getLocationOnScreen(pos);val save=canvas.save();canvas.translate((pos[0]-origin[0]-left).toFloat(),(pos[1]-origin[1]-top).toFloat());v.draw(canvas);canvas.restoreToCount(save)}
        drawPresentation(a.scroll);ghost?.let{drawPresentation(it)}
        val layer=object:View(a){val paint=Paint(Paint.FILTER_BITMAP_FLAG);override fun onDraw(c:Canvas){if(!image.isRecycled)c.drawBitmap(image,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)};override fun onDetachedFromWindow(){if(!image.isRecycled)image.recycle();super.onDetachedFromWindow()}}.apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;isClickable=false}
        return {
            ghost=layer;tabView=a.page;tabDirection=if(opening)1f else -1f;tabWidth=width.toFloat()
            a.root.addView(layer,1.coerceAtMost(a.root.childCount),FrameLayout.LayoutParams(width,height).apply{leftMargin=left-a.root.paddingLeft;topMargin=top-a.root.paddingTop})
            sampleTab(0)
            // The destination's posted scroll restoration runs before the first animated draw.
            tabClock=ValueAnimator.ofFloat(0f,1f).apply{duration=300;interpolator=LinearInterpolator();addUpdateListener{sampleTab(currentPlayTime)};start()}
        }
    }
}
