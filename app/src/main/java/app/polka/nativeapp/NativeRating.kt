package app.polka.nativeapp
import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.LinearLayout

/**
 * Five stars. Choosing fills them one after another from the left (each fill grows from the star's centre),
 * the chosen star hops with a short burst of rays; lowering or clearing drains the extra stars right to left.
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
        hop.hop(if(value>0) rising.size*55L else 0L, value>0 && value>=old)
    }
    inner class Star(val n:Int):View(a) {
        private val path=androidx.core.graphics.PathParser.createPathFromPathData("M12 2.8l2.85 5.77 6.37 .93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z")!!
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        var fill=0f; set(v) { field=v; invalidate() }
        private var rays=-1f
        private var fillAnim:Animator?=null
        init { isClickable=true; isFocusable=true; setOnClickListener { choose(n) } }
        fun animateFill(to:Float,delay:Long) { fillAnim?.cancel(); val from=fill
            fillAnim=ValueAnimator.ofFloat(0f,1f).apply { startDelay=delay; duration=if(to>from) 340 else 220; interpolator=if(to>from) OvershootInterpolator(1.6f) else AccelerateInterpolator(1.2f); addUpdateListener { fill=from+(to-from)*(it.animatedValue as Float) }; start() } }
        fun hop(delay:Long,burst:Boolean) {
            animate().cancel(); animate().scaleX(1.32f).scaleY(1.32f).rotation(-8f).setStartDelay(delay).setDuration(150).setInterpolator(DecelerateInterpolator())
                .withEndAction { animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(0).setDuration(420).setInterpolator(OvershootInterpolator(2.4f)).start() }.start()
            if(burst) ValueAnimator.ofFloat(0f,1f).apply { startDelay=delay+60; duration=460; interpolator=DecelerateInterpolator(); addUpdateListener { rays=it.animatedValue as Float; invalidate() }; addListener(object:AnimatorListenerAdapter(){ override fun onAnimationEnd(x:Animator){ rays=-1f; invalidate() } }); start() }
        }
        override fun onDraw(c:Canvas) {
            val size=a.d(30).toFloat(); val cx=width/2f; val cy=height/2f
            if(rays in 0f..1f) { p.style=Paint.Style.STROKE; p.strokeCap=Paint.Cap.ROUND; p.strokeWidth=a.d(2).toFloat(); p.color=StarRow.starYellow(a); p.alpha=(255*(1f-rays)).toInt()
                val r0=size*.55f+size*.25f*rays; val r1=r0+size*.22f*(1f-rays*.6f)
                for(k in 0 until 6) { val ang=Math.toRadians((k*60-90).toDouble()); val dx=Math.cos(ang).toFloat(); val dy=Math.sin(ang).toFloat(); c.drawLine(cx+dx*r0,cy+dy*r0,cx+dx*r1,cy+dy*r1,p) }
                p.alpha=255 }
            c.save(); c.translate(cx-size/2,cy-size/2); c.scale(size/24,size/24)
            p.strokeWidth=1.6f; p.strokeJoin=Paint.Join.ROUND
            p.style=Paint.Style.STROKE; p.color=a.muted; p.alpha=(255*(1f-fill.coerceIn(0f,1f))).toInt().coerceAtLeast(0); if(fill<1f) c.drawPath(path,p)
            if(fill>0f) { p.alpha=255; p.color=StarRow.starYellow(a); p.style=Paint.Style.FILL_AND_STROKE; c.save(); val f=fill.coerceAtLeast(0f); c.scale(f,f,12f,12.4f); c.drawPath(path,p); c.restore() }
            c.restore()
        }
        override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.className="android.widget.RadioButton"; info.isCheckable=true; info.isChecked=n==value }
    }
}
