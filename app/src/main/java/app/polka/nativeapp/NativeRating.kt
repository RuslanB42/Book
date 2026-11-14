package app.polka.nativeapp
import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.LinearLayout

/**
 * Five stars. Choosing fills them one after another from the left (each fill grows softly from the star's centre)
 * and the chosen star gives a short, quiet press; lowering or clearing drains the extra stars right to left.
 */
class NativeRating(val a:MainActivity,initial:Int=0,val toggle:Boolean=true,val changed:(Int)->Unit):LinearLayout(a) {
    var value=initial; private set
    private val stars=mutableListOf<Star>()
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init { orientation=HORIZONTAL; gravity=Gravity.CENTER; clipChildren=false; clipToPadding=false; contentDescription="Ваша оценка"; for(n in 1..5) { val star=Star(n); star.fill=if(n<=initial)1f else 0f; stars.add(star); addView(star,LayoutParams(0,a.d(52),1f)) }; describe() }
    private fun describe() { stars.forEach { it.contentDescription="${it.n} из 5"+(if(it.n==value) ", выбрано" else "") } }
    private fun choose(n:Int) {
        val old=value; value=if(toggle && value==n) 0 else n; describe(); changed(value)
        if(a.prefs.optBoolean("haptics",true)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if(reduced) { stars.forEach { it.fill=if(it.n<=value)1f else 0f }; return }
        val rising=stars.filter { it.n<=value && it.fill<1f }
        val falling=stars.filter { it.n>value && it.fill>0f }.reversed()
        rising.forEachIndexed { i,s -> s.animateFill(1f,i*55L) }
        falling.forEachIndexed { i,s -> s.animateFill(0f,i*45L) }
        val hop=stars.getOrNull((if(value>0) value else n)-1) ?: return
        hop.hop(if(value>0) rising.size*55L else 0L)
    }
    inner class Star(val n:Int):View(a) {
        private val path=StarShape.path
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
        var fill=0f; set(v) { field=v; invalidate() }
        private var fillAnim:Animator?=null
        init { isClickable=true; isFocusable=true; setOnClickListener { choose(n) } }
        fun animateFill(to:Float,delay:Long) { fillAnim?.cancel(); val from=fill
            fillAnim=ValueAnimator.ofFloat(0f,1f).apply { startDelay=delay; duration=if(to>from) 300 else 200; interpolator=if(to>from) PathInterpolator(.3f,1.25f,.5f,1f) else PathInterpolator(.4f,0f,1f,1f); addUpdateListener { fill=from+(to-from)*(it.animatedValue as Float) }; start() } }
        /** A short press on the chosen star: in a little, back with a soft settle. No turn, no rays. */
        fun hop(delay:Long) {
            animate().cancel(); animate().scaleX(.86f).scaleY(.86f).setStartDelay(delay).setDuration(110).setInterpolator(DecelerateInterpolator())
                .withEndAction { animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(360).setInterpolator(PathInterpolator(.3f,1.4f,.5f,1f)).start() }.start()
        }
        override fun onDraw(c:Canvas) {
            val size=a.d(30).toFloat(); val cx=width/2f; val cy=height/2f
            c.save(); c.translate(cx-size/2,cy-size/2); c.scale(size/24,size/24)
            val f=fill.coerceIn(0f,1f)
            // the empty star: a thin outline with the same rounded corners
            if(f<1f){ p.style=Paint.Style.STROKE; p.strokeWidth=1.5f; p.color=a.muted; p.alpha=(255*(1f-f)).toInt(); c.drawPath(path,p) }
            // the full star: the same silhouette, filled, grown from the centre
            if(fill>0f){ p.alpha=255; p.color=StarRow.starYellow(a); p.style=Paint.Style.FILL_AND_STROKE; p.strokeWidth=1.5f; c.save(); val k=fill.coerceAtLeast(0f); c.scale(k,k,12f,12.6f); c.drawPath(path,p); c.restore() }
            c.restore()
        }
        override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.className="android.widget.RadioButton"; info.isCheckable=true; info.isChecked=n==value }
    }
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
