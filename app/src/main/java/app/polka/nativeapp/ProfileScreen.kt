package app.polka.nativeapp
import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.*
import java.time.LocalDate
import kotlin.math.*

private val igColors=intArrayOf(0xfff28a2e.toInt(),0xff6f9dff.toInt(),0xff34c27a.toInt(),0xffc77dff.toInt(),0xffffc93c.toInt(),0xffff6b8a.toInt(),0xff2ec5d3.toInt(),0xff8e8e93.toInt())
private val weekdays=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
/** Same calendar, historical goal and speed rules as the original profile. */
class ProfileData(val source:JSONObject,val today:LocalDate=LocalDate.now()) {
    val days=source.optJSONObject("days") ?: JSONObject()
    val records=source.optJSONObject("books") ?: JSONObject()
    val goal=source.optInt("goal",10).coerceAtLeast(1)
    fun ms(day:LocalDate)=days.optJSONObject(day.toString())?.optLong("ms") ?: 0L
    fun met(day:LocalDate)=days.optJSONObject(day.toString())?.optBoolean("met")==true || ms(day)>=goal*60000L
    val entries=days.keys().asSequence().mapNotNull { key -> try { LocalDate.parse(key) to days.getJSONObject(key) } catch(_:Exception) { null } }.sortedBy { it.first }.toList()
    val total=entries.sumOf { it.second.optLong("ms") }
    val chars=entries.sumOf { it.second.optLong("chars") }
    val speed=readingSpeed(chars,total)
    val first=entries.firstOrNull { it.second.optLong("ms")>0 }?.first
    val sessions=records.keys().asSequence().sumOf { records.getJSONObject(it).optInt("sessions") }
    val streak:Int get() { var n=0; var at=if(met(today)) today else today.minusDays(1); while(met(at)) { n++; at=at.minusDays(1) }; return n }
    val best:Int get() { var run=0; var best=0; var previous:LocalDate?=null; entries.forEach { (date,_) -> run=if(met(date)) if(previous==date.minusDays(1)) run+1 else 1 else 0; best=max(best,run); previous=date }; return maxOf(best,source.optInt("best"),streak) }
    fun series(n:Int,offset:Int=0)=(n-1 downTo 0).map { ms(today.minusDays((it+offset).toLong())) }
    fun rhythm():List<Double> { val sums=DoubleArray(7); repeat(56) { val date=today.minusDays(it.toLong()); sums[date.dayOfWeek.value-1]+=ms(date).toDouble() }; return sums.map { it/8/60000 } }
}
fun readingSpeed(chars:Long,ms:Long)=if(ms>=60000 && chars>300) (chars/6.4/(ms/60000.0)).roundToInt() else 0
fun duration(ms:Long):String { val m=(ms/60000.0).roundToLong(); return when { ms>0 && m<1 -> "<1 мин"; m<60 -> "$m мин"; m%60==0L -> "${m/60} ч"; else -> "${m/60} ч ${m%60} мин" } }
fun MainActivity.statistics()=profile()
fun MainActivity.profile() { var stats=JSONObject(); work({ stats=library.dao.meta("stats")?.let { JSONObject(it.json).optJSONObject("data") } ?: JSONObject() }) { showProfile(stats,profileRange) } }
fun MainActivity.showProfile(stats:JSONObject,range:Int,animate:Boolean=true) {
    profileStats=stats; profileRange=range
    profileOverlay?.let { root.removeView(it) }
    StatisticsScreen(this,stats,range,animate).show()
}
fun MainActivity.rise(v:View,delay:Long=0) { if(prefs.str("motion")=="reduced") return; v.alpha=0f; v.translationY=d(12).toFloat(); v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(500).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start() }
/** Cancels when detached, including theme switches and activity replacement. */
open class AnimatedProfileView(val a:MainActivity,val animatePlot:Boolean=true):View(a) {
    var phase=1f; private var animator:ValueAnimator?=null
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if(animatePlot && a.prefs.str("motion")!="reduced") { phase=0f; animator=ValueAnimator.ofFloat(0f,1f).apply { duration=1000; interpolator=PathInterpolator(.22f,1f,.36f,1f); addUpdateListener { phase=it.animatedValue as Float; invalidate() }; start() } } }
    override fun onDetachedFromWindow() { animator?.cancel(); animator=null; super.onDetachedFromWindow() }
}
class MiniMetric(a:MainActivity,val kind:String,val values:List<Double>,val color:Int,animate:Boolean=true):AnimatedProfileView(a,animate) {
    override fun onDraw(c:Canvas) { val p=Paint(3); val w=width.toFloat(); val h=height.toFloat(); val track=if(a.light) 0xffddddda.toInt() else 0xff38383b.toInt()
        when(kind) {
            "spark"->{ if(values.isEmpty()) return; val max=max(1.0,values.maxOrNull() ?: 1.0); val path=Path(); val pts=values.mapIndexed { i,v -> (if(values.size>1) i*w/(values.size-1) else w/2) to (h-3-(v/max*(h-7)).toFloat()) }; path.moveTo(pts[0].first,pts[0].second); for(i in 1 until pts.size) { val prev=pts[i-1]; val next=pts[i]; val mid=(prev.first+next.first)/2; path.cubicTo(mid,prev.second,mid,next.second,next.first,next.second) }; c.save(); c.clipRect(0f,0f,w*phase,h); val area=Path(path); area.lineTo(w,h); area.lineTo(0f,h); area.close(); p.shader=LinearGradient(0f,0f,0f,h,intArrayOf((color and 0xffffff) or 0x52000000,color and 0xffffff),null,Shader.TileMode.CLAMP); c.drawPath(area,p); p.shader=null; p.color=color; p.style=Paint.Style.STROKE; p.strokeWidth=a.d(2).toFloat(); c.drawPath(path,p); c.restore() }
            "gauge"->{ val size=min(w,a.d(112).toFloat()); val unit=size/100; val x=(w-size)/2; val r=RectF(x+10*unit,h-46*unit,x+90*unit,h+34*unit); p.style=Paint.Style.STROKE; p.strokeWidth=9*unit; p.strokeCap=Paint.Cap.ROUND; p.color=track; c.drawArc(r,180f,180f,false,p); p.color=color; c.drawArc(r,180f,180f*values[0].toFloat()*phase,false,p); p.style=Paint.Style.FILL; p.color=a.fg; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); p.textSize=15*unit; p.textAlign=Paint.Align.CENTER; c.drawText("${(values[0]*100).roundToInt()}%",w/2,h-8*unit,p) }
            "speed"->{ val top=a.d(7).toFloat(); p.shader=LinearGradient(0f,0f,w,0f,track,color,Shader.TileMode.CLAMP); c.drawRoundRect(0f,top,w,top+a.d(6),a.d(3).toFloat(),a.d(3).toFloat(),p); p.shader=null; p.color=a.muted; c.drawRect(w/2,top-a.d(4),w/2+a.d(1),top+a.d(10),p); if(values[0]>0) { p.color=a.fg; c.drawCircle((values[0]/400).coerceIn(.02,.98).toFloat()*w*phase,top+a.d(3),a.d(6).toFloat(),p) }; p.color=a.muted; p.textSize=a.d(10).toFloat(); listOf("0","ср. 200","400+").forEachIndexed { i,s -> p.textAlign=when(i) { 0->Paint.Align.LEFT; 1->Paint.Align.CENTER; else->Paint.Align.RIGHT }; c.drawText(s,w*i/2,h-2,p) } }
        }
    }
}
class WeekDot(val a:MainActivity,val met:Boolean,val partial:Boolean,val today:Boolean):View(a) {
    private val flame by lazy { Bitmap.createBitmap(a.d(19),a.d(24),Bitmap.Config.ARGB_8888).also { bitmap -> val icon=FlameView(a,0,true,false,false); icon.layout(0,0,bitmap.width,bitmap.height); icon.draw(Canvas(bitmap)) } }
    override fun onDraw(c:Canvas) { val p=Paint(3); val x=width/2f; val y=height/2f; val radius=a.d(17).toFloat(); p.color=if(met) 0x29f28a2e else if(a.light) 0xffe8e8e5.toInt() else 0xff2c2c2e.toInt(); c.drawCircle(x,y,radius,p); p.style=Paint.Style.STROKE; p.strokeWidth=a.d(2).toFloat(); p.color=if(today && !met) a.muted else if(met || partial) igColors[0] else a.muted; if(today) c.drawCircle(x,y,a.d(18).toFloat(),p) else if(partial) { p.alpha=150; c.drawCircle(x,y,radius-1,p); p.alpha=255 }; if(met) { p.style=Paint.Style.FILL; c.drawBitmap(flame,x-flame.width/2f,y-flame.height/2f,p) } }
}
class ActivityBars(a:MainActivity,val values:List<Double>,val labels:List<String>,val goal:Double,val rhythm:Boolean=false,animate:Boolean=true,val tipLabels:List<String> = labels):AnimatedProfileView(a,animate) {
    private var selected=-1
    init { contentDescription=values.indices.joinToString("; ") { "${labels[it]}: ${values[it].toInt()} мин" }; isFocusable=true; setOnTouchListener { _,e -> if(e.action==MotionEvent.ACTION_UP && values.isNotEmpty()) { selected=(e.x/width*values.size).toInt().coerceIn(0,values.lastIndex); invalidate(); performClick() }; true } }
    override fun performClick():Boolean { super.performClick(); return true }
    override fun onDraw(c:Canvas) { val p=Paint(3); val w=width.toFloat(); val top=if(rhythm) a.d(22).toFloat() else a.d(5).toFloat(); val bottom=height-a.d(24).toFloat(); val maximum=maxOf(values.maxOrNull() ?: 0.0,goal*1.25,1.0); val cell=w/maxOf(1,values.size); val peak=values.indices.maxByOrNull { values[it] } ?: -1; val track=if(a.light) 0xffe2e2df.toInt() else 0xff303033.toInt()
        if(!rhythm) { p.color=track; p.strokeWidth=1f; c.drawLine(0f,bottom,w,bottom,p); if(goal>0) { val y=bottom-((bottom-top)*goal/maximum).toFloat(); p.color=0xa6f28a2e.toInt(); p.pathEffect=DashPathEffect(floatArrayOf(a.d(4).toFloat(),a.d(4).toFloat()),0f); c.drawLine(0f,y,w,y,p); p.pathEffect=null; p.textSize=a.d(11).toFloat(); p.textAlign=Paint.Align.RIGHT; p.color=a.muted; c.drawText("цель ${goal.toInt()} мин",w,y-a.d(4),p) } }
        values.forEachIndexed { i,v -> val bw=if(rhythm) min(a.d(34).toFloat(),cell-a.d(8)) else min(a.d(28).toFloat(),cell-a.d(4)); val x=cell*i+(cell-bw)/2; val y=bottom-((bottom-top)*max(if(v>0) .025 else 0.0,v/maximum)).toFloat()*phase
            if(selected==i) { p.color=track; c.drawRoundRect(cell*i,top,cell*(i+1),bottom,a.d(6).toFloat(),a.d(6).toFloat(),p) }
            if(rhythm) { p.color=track; c.drawRoundRect(x,top,x+bw,bottom,a.d(10).toFloat(),a.d(10).toFloat(),p) }
            val active=if(rhythm) i==peak else v>=goal
            p.color=a.muted; if(active && v>0) p.shader=LinearGradient(0f,y,0f,bottom,0xffffb43b.toInt(),0xffe8562a.toInt(),Shader.TileMode.CLAMP)
            if(v>0) c.drawRoundRect(x,y,x+bw,bottom,a.d(if(rhythm) 10 else 4).toFloat(),a.d(if(rhythm) 10 else 4).toFloat(),p); p.shader=null
            p.textSize=a.d(if(rhythm) 12 else 11).toFloat(); p.textAlign=Paint.Align.CENTER; p.color=if(rhythm && active) a.fg else a.muted; c.drawText(labels[i],cell*(i+.5f),height-a.d(5).toFloat(),p)
            if(rhythm && v>0) { p.textSize=a.d(11).toFloat(); c.drawText(if(v>=.5) "${v.roundToInt()}м" else "<1м",cell*(i+.5f),a.d(12).toFloat(),p) }
        }
        if(!rhythm && selected in values.indices) { val label=if(values[selected]>0) duration((values[selected]*60000).toLong()) else "Не читали"; val detail=tipLabels.getOrElse(selected) { "" }; val cx=(cell*(selected+.5f)).coerceIn(a.d(55).toFloat(),(w-a.d(55)).coerceAtLeast(a.d(55).toFloat())); val rect=RectF(cx-a.d(52),0f,cx+a.d(52),a.d(46).toFloat()); p.color=if(a.light) Color.WHITE else 0xff343437.toInt(); p.setShadowLayer(a.d(6).toFloat(),0f,a.d(2).toFloat(),0x44000000); c.drawRoundRect(rect,a.d(9).toFloat(),a.d(9).toFloat(),p); p.clearShadowLayer(); p.textAlign=Paint.Align.CENTER; p.color=a.fg; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); p.textSize=a.d(12).toFloat(); c.drawText(label,cx,a.d(18).toFloat(),p); p.typeface=Typeface.create("sans-serif",Typeface.NORMAL); p.textSize=a.d(11).toFloat(); p.color=a.muted; c.drawText(detail,cx,a.d(34).toFloat(),p) }
        if(!rhythm && values.sum()==0.0) { p.color=a.muted; p.textSize=a.d(14).toFloat(); p.textAlign=Paint.Align.CENTER; c.drawText("График заполнится, когда",w/2,(top+bottom)/2-a.d(8),p); c.drawText("вы начнёте читать",w/2,(top+bottom)/2+a.d(12),p) }
    }
}
class ShareRing(a:MainActivity,val values:List<Double>,val label:String,val subtitle:String="",val colors:List<Int> = igColors.toList(),val stroke:Float=16f,val small:Boolean=false,animate:Boolean=true):AnimatedProfileView(a,animate) {
    override fun onDraw(c:Canvas) { val p=Paint(3).apply { style=Paint.Style.STROKE; strokeWidth=stroke*a.resources.displayMetrics.density }; val size=min(width,height)-p.strokeWidth; val r=RectF((width-size)/2f,(height-size)/2f,(width+size)/2f,(height+size)/2f); val total=values.sum(); p.color=if(a.light) 0xffddddda.toInt() else 0xff38383b.toInt(); c.drawOval(r,p); var angle=-90f; values.forEachIndexed { i,v -> val sweep=if(total>0) (v/total*360).toFloat() else 0f; p.color=colors[i%colors.size]; c.drawArc(r,angle,maxOf(0f,sweep-(if(values.size>1) 4.32f else 0f))*phase,false,p); angle+=sweep }; p.style=Paint.Style.FILL; p.color=a.fg; p.textSize=a.d(if(small) 10 else 26).toFloat(); p.typeface=if(small) Typeface.create("sans-serif",Typeface.BOLD) else PolkaFonts.serif(a,true); p.textAlign=Paint.Align.CENTER; val baseline=height/2f-(p.ascent()+p.descent())/2-if(subtitle.isNotBlank()) a.d(7) else 0; c.drawText(label,width/2f,baseline,p); if(subtitle.isNotBlank()) { p.typeface=Typeface.DEFAULT; p.textSize=a.d(11).toFloat(); p.color=a.muted; c.drawText(subtitle,width/2f,baseline+a.d(19),p) } }
}
class FlameView(val a:MainActivity,val number:Int,val active:Boolean,val risk:Boolean=false,val showNumber:Boolean=true):View(a) {
    override fun onDraw(c:Canvas) {
        c.save(); val scale=if(showNumber) min((width-a.d(8))/120f,(height-a.d(8))/178f) else min(width/120f,height/150f); c.translate((width-120*scale)/2,if(showNumber) (height-178*scale)/2+14*scale else (height-150*scale)/2); c.scale(scale,scale)
        val p=Paint(3); if(risk && active) { val matrix=ColorMatrix().apply { setSaturation(.7f); postConcat(ColorMatrix(floatArrayOf(.9f,0f,0f,0f,0f,0f,.9f,0f,0f,0f,0f,0f,.9f,0f,0f,0f,0f,0f,1f,0f))) }; p.colorFilter=ColorMatrixColorFilter(matrix) }
        val paths=listOf("M60 145C32 145 12 129 9 108C5 86 15 66 31 51C29 63 31 74 38 81C35 61 48 48 57 34C65 21 62 10 54 3C82 5 101 25 99 49C98 62 92 73 97 81C101 87 108 82 111 72C124 90 119 112 105 128C94 140 78 146 60 145Z","M60 145C43 145 31 133 30 117C29 105 34 94 42 86C43 94 46 99 51 101C44 84 49 66 63 52C67 47 72 44 77 42C71 59 75 72 84 85C94 99 99 111 94 125C89 139 76 146 60 145Z","M60 145C49 145 41 137 42 126C42 116 46 107 51 101C51 110 56 114 61 115C57 106 58 97 63 89C65 101 74 109 78 119C85 134 76 145 60 145Z")
        val on=listOf(listOf("#f52d32","#ff4c16","#ff8006","#ffb522"),listOf("#ffe347","#ffe54d","#fff17b","#fff4ab"),listOf("#fff075","#fff7ad","#fffef0"))
        val off=listOf(listOf("#a5a5ac","#83838c","#62626c"),listOf("#c5c5cb","#b1b1b9","#dedee2"),listOf("#e2e2e7","#f5f5f7"))
        paths.forEachIndexed { i,path -> val colors=(if(active) on else off)[i].map { Color.parseColor(it) }.toIntArray(); val stops=if(active) when(i) { 0->floatArrayOf(0f,.3f,.65f,1f); 1->floatArrayOf(0f,.48f,.8f,1f); else->floatArrayOf(0f,.55f,1f) } else when(i) { 0->floatArrayOf(0f,.55f,1f); 1->floatArrayOf(0f,.6f,1f); else->floatArrayOf(0f,1f) }; p.shader=LinearGradient(0f,if(i==0) 3f else if(i==1) 42f else 89f,0f,145f,colors,stops,Shader.TileMode.CLAMP); c.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(path)!!,p) }
        if(!showNumber) { c.restore(); return }; p.shader=null; p.textSize=54f; p.typeface=Typeface.create("sans-serif-black",Typeface.BOLD); p.textAlign=Paint.Align.CENTER; p.strokeJoin=Paint.Join.ROUND; p.strokeWidth=11f; p.style=Paint.Style.STROKE; p.color=if(active) 0xffd63c0a.toInt() else 0xff5f5f64.toInt(); c.drawText(number.toString(),60f,160f,p); p.style=Paint.Style.FILL; p.shader=LinearGradient(0f,110f,0f,160f,Color.WHITE,0xfffff3e6.toInt(),Shader.TileMode.CLAMP); c.drawText(number.toString(),60f,160f,p); c.restore()
    }
}

/** Same presentation-to-target transition for toolbar and system Back. */
fun MainActivity.dismissProfile(){val panel=profileOverlay?:return;profileOverlay=null;profileStats=null
 if(prefs.str("motion")=="reduced"){root.removeView(panel);return}
 NativeMotion(panel,MotionPose(panel.alpha,panel.translationX,panel.translationY,panel.scaleX),MotionPose(0f,y=d(14).toFloat(),scale=.985f),240,curve=MotionSpec.settle,complete={root.removeView(panel)}).start()
}
