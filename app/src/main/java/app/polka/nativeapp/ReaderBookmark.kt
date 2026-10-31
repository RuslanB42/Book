package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.min

/** Toolbar bookmark: an outline that fills with the accent colour while the ribbon drops in. */
internal class BookmarkIcon(private val a:MainActivity,ink:Int):IconView(a,"bookmark",ink) {
    private var fill=0f;private var anim:ValueAnimator?=null;private var on=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path().apply{moveTo(6f,3f);lineTo(18f,3f);lineTo(18f,21f);lineTo(12f,17f);lineTo(6f,21f);close()}
    fun mark(value:Boolean,animate:Boolean){
        if(value==on&&anim==null&&fill==(if(value)1f else 0f))return
        on=value;anim?.cancel();anim=null;val target=if(value)1f else 0f
        if(!animate||a.prefs.str("motion")=="reduced"){fill=target;invalidate();return}
        anim=ValueAnimator.ofFloat(fill,target).apply{duration=if(value)320L else 220L;interpolator=PathInterpolator(.33f,0f,.2f,1f);addUpdateListener{fill=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onDetachedFromWindow(){anim?.cancel();anim=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val size=22f*resources.displayMetrics.density;val k=size/24f;val f=fill.coerceIn(0f,1f)
        c.save();c.translate((width-size)/2f,(height-size)/2f);c.scale(k,k)
        // Fixed shape; the accent pours in from the top and the outline takes the same colour.
        val accent=a.accent
        if(f>0f){c.save();c.clipRect(0f,0f,24f,3f+19f*f);paint.style=Paint.Style.FILL;paint.color=accent;c.drawPath(path,paint);c.restore()}
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND
        paint.color=ColorUtils.blend(ink,accent,f);c.drawPath(path,paint);c.restore()
    }
}
private object ColorUtils {
    private fun m(p:Int,q:Int,t:Float):Int = (p+(q-p)*t).toInt().coerceIn(0,255)
    fun blend(x:Int,y:Int,t:Float):Int = Color.argb(m(Color.alpha(x),Color.alpha(y),t),m(Color.red(x),Color.red(y),t),m(Color.green(x),Color.green(y),t),m(Color.blue(x),Color.blue(y),t))
}

/** A ribbon hanging from the very top of the screen: it rests on a bookmarked page and follows the finger while one is pulled down. */
internal class BookmarkRibbon(private val a:MainActivity):View(a) {
    companion object {
        const val WIDTH_DP=22;const val REST_DP=20;const val THRESHOLD_DP=72;const val RIGHT_DP=18
        /** The same drawing is used live and baked into page snapshots, so it turns with the sheet. */
        fun draw(c:Canvas,a:MainActivity,length:Float){
            if(length<=.5f)return;val w=a.d(WIDTH_DP).toFloat();val notch=min(w*.4f,length*.45f)
            val p=Paint(Paint.ANTI_ALIAS_FLAG);val path=Path().apply{moveTo(0f,-4f);lineTo(w,-4f);lineTo(w,length);lineTo(w/2f,length-notch);lineTo(0f,length);close()}
            p.setShadowLayer(a.d(3).toFloat(),0f,a.d(1).toFloat(),0x55000000);p.color=a.accent;c.drawPath(path,p)
        }
    }
    var length=0f;private set
    /** Centre of the ribbon in this view's coordinates; NaN means "at its resting place". */
    private var cx=Float.NaN
    var insetTop=0;var insetRight=0
    private var anim:ValueAnimator?=null
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false}
    val rest get()=insetTop+a.d(REST_DP).toFloat()
    /** Resting centre: near the right edge, clear of the safe area. */
    fun restX(w:Int=width)=w-insetRight-a.d(RIGHT_DP)-a.d(WIDTH_DP)/2f
    private fun x()=if(cx.isNaN())restX() else cx
    fun set(value:Float,x:Float){anim?.cancel();anim=null;length=value.coerceAtLeast(0f);val half=a.d(WIDTH_DP)/2f;cx=x.coerceIn(half,(width-half).coerceAtLeast(half));invalidate()}
    /** Animate to a resting length; a visible ribbon also glides back to its place, a vanishing one retracts where it is. */
    fun settle(target:Float,drop:Boolean,animate:Boolean=true){
        anim?.cancel();anim=null
        val fromX=x();val toX=if(target>0f)restX() else fromX
        if(!animate||a.prefs.str("motion")=="reduced"||!isAttachedToWindow){length=target;cx=if(target>0f)Float.NaN else fromX;invalidate();return}
        val from=length;val grow=from<target
        anim=ValueAnimator.ofFloat(0f,1f).apply{duration=if(drop&&grow)420L else 300L;interpolator=if(drop&&grow)OvershootInterpolator(1.5f) else PathInterpolator(.22f,1f,.36f,1f)
            addUpdateListener{val t=it.animatedValue as Float;length=(from+(target-from)*t).coerceAtLeast(0f);cx=fromX+(toX-fromX)*t.coerceIn(0f,1f);invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(anim===x&&target>0f){cx=Float.NaN;invalidate()}}});start()}
    }
    override fun onDetachedFromWindow(){anim?.cancel();anim=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){c.save();c.translate(x()-a.d(WIDTH_DP)/2f,0f);draw(c,a,length);c.restore()}
}
