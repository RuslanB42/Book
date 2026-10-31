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
        anim=ValueAnimator.ofFloat(fill,target).apply{duration=if(value)380L else 200L;interpolator=if(value)OvershootInterpolator(1.8f) else DecelerateInterpolator();addUpdateListener{fill=it.animatedValue as Float;invalidate()};start()}
    }
    override fun onDetachedFromWindow(){anim?.cancel();anim=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val size=22f*resources.displayMetrics.density;val k=size/24f;val f=fill.coerceIn(0f,1f)
        c.save();c.translate((width-size)/2f,(height-size)/2f);c.scale(k,k)
        // The ribbon "drops": its height follows the (overshooting) fill value, pinned to the top edge.
        c.translate(0f,3f);c.scale(1f,(.62f+.38f*fill).coerceAtLeast(.3f));c.translate(0f,-3f)
        val accent=a.accent
        paint.style=Paint.Style.FILL;paint.color=(accent and 0xffffff) or ((255*f).toInt() shl 24);c.drawPath(path,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f;paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND
        paint.color=ColorUtils.blend(ink,accent,f);c.drawPath(path,paint);c.restore()
    }
}
private object ColorUtils{fun blend(x:Int,y:Int,t:Float):Int{fun m(p:Int,q:Int)=(p+(q-p)*t).toInt().coerceIn(0,255);return Color.argb(m(Color.alpha(x),Color.alpha(y)),m(Color.red(x),Color.red(y)),m(Color.green(x),Color.green(y)),m(Color.blue(x),Color.blue(y)))}}

/** A ribbon hanging from the top edge of the page: visible on a bookmarked page, and pulled down by hand to add or remove one. */
internal class BookmarkRibbon(private val a:MainActivity):View(a) {
    companion object {
        const val WIDTH_DP=22;const val REST_DP=20;const val PULL_DP=64;const val THRESHOLD_DP=62
        /** The same drawing is used live and baked into page snapshots, so it turns with the sheet. */
        fun draw(c:Canvas,a:MainActivity,length:Float){
            if(length<=.5f)return;val w=a.d(WIDTH_DP).toFloat();val notch=min(w*.4f,length*.45f)
            val p=Paint(Paint.ANTI_ALIAS_FLAG);val path=Path().apply{moveTo(0f,-4f);lineTo(w,-4f);lineTo(w,length);lineTo(w/2f,length-notch);lineTo(0f,length);close()}
            p.setShadowLayer(a.d(3).toFloat(),0f,a.d(1).toFloat(),0x55000000);p.color=a.accent;c.drawPath(path,p)
        }
    }
    var length=0f;private set
    private var anim:ValueAnimator?=null
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false}
    val rest get()=a.d(REST_DP).toFloat()
    fun set(value:Float){anim?.cancel();anim=null;length=value.coerceIn(0f,a.d(PULL_DP+14).toFloat());invalidate()}
    /** Animate to a resting length; [drop] gives the ribbon a small overshoot when it appears. */
    fun settle(target:Float,drop:Boolean,animate:Boolean=true){
        anim?.cancel();anim=null
        if(!animate||a.prefs.str("motion")=="reduced"||!isAttachedToWindow){length=target;invalidate();return}
        anim=ValueAnimator.ofFloat(length,target).apply{duration=if(drop)420L else 240L;interpolator=if(drop)OvershootInterpolator(1.5f) else PathInterpolator(.22f,1f,.36f,1f);addUpdateListener{length=(it.animatedValue as Float).coerceAtLeast(0f);invalidate()};start()}
    }
    override fun onDetachedFromWindow(){anim?.cancel();anim=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){c.save();c.translate(a.d(3).toFloat(),0f);draw(c,a,length);c.restore()}
}
