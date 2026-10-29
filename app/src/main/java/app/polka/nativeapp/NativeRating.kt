package app.polka.nativeapp
import android.graphics.*
import android.view.*
import android.widget.*
/** Original SVG star, not a platform-dependent Unicode glyph. */
class NativeRating(val a:MainActivity,initial:Int=0,val toggle:Boolean=true,val changed:(Int)->Unit):LinearLayout(a) {
    var value=initial; private set
    private val stars=mutableListOf<Star>()
    init { orientation=HORIZONTAL; gravity=Gravity.CENTER; contentDescription="Ваша оценка"; for(n in 1..5) { val star=Star(n); stars.add(star); addView(star,LayoutParams(0,a.d(52),1f)) }; paint() }
    private fun paint() { stars.forEach { it.isSelected=it.n==value; it.contentDescription="${it.n} из 5"+(if(it.n==value) ", выбрано" else ""); it.invalidate() } }
    inner class Star(val n:Int):View(a) {
        private val path=androidx.core.graphics.PathParser.createPathFromPathData("M12 2.8l2.85 5.77 6.37 .93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z")!!
        private val p=Paint(3)
        init { isClickable=true; isFocusable=true; setOnClickListener { value=if(toggle && value==n) 0 else n; paint(); changed(value); if(a.prefs.str("motion")!="reduced") animate().scaleX(1.3f).scaleY(1.3f).setDuration(130).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start() } }
        override fun onDraw(c:Canvas) { val size=a.d(30).toFloat(); c.save(); c.translate((width-size)/2,(height-size)/2); c.scale(size/24,size/24); p.color=if(n<=value) a.accentFill else a.muted; p.strokeWidth=1.6f; p.strokeJoin=Paint.Join.ROUND; p.style=if(n<=value) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE; c.drawPath(path,p); c.restore() }
        override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info); info.className="android.widget.RadioButton"; info.isCheckable=true; info.isChecked=n==value }
    }
}
