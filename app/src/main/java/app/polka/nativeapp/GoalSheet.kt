package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

/** «Огонёк»: minutes a day that keep the streak. A slider that snaps to round values; the flame grows inside its own stage, the number rolls, the pace is named. */
internal fun MainActivity.editStreakThreshold(saved:(Int)->Unit={}) {
    var current=10
    work({current=library.dao.meta("stats")?.json?.let{JSONObject(it).optJSONObject("data")?.optInt("goal",10)} ?: 10}){flameSheet(current,saved)}
}
private class GoalLevel(val below:Int,val text:String,val color:Int)
private val goalLevels=listOf(GoalLevel(15,"Пара страниц перед сном",0xff4f8dff.toInt()),GoalLevel(30,"Спокойный вечер с книгой",0xff35c4a0.toInt()),
    GoalLevel(45,"Глава в день",0xffffc531.toInt()),GoalLevel(60,"Читатель всерьёз",0xffff9a2b.toInt()),GoalLevel(Int.MAX_VALUE,"Книжный марафон",0xffff4f6d.toInt()))
private fun goalLevel(v:Int)=goalLevels.first{v<it.below}

private fun MainActivity.goalSheet(initial:Int,saved:(Int)->Unit){
    val min=5;val max=90
    var value=((initial.coerceIn(min,max)+2)/5)*5
    val reduced=prefs.str("motion")=="reduced"
    fun ink(c:Int)=if(light)androidx.core.graphics.ColorUtils.blendARGB(c,Color.BLACK,.28f) else c
    val box=column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    // The stage has a fixed height; the flame scales from its base and its largest size still fits inside.
    val stage=FrameLayout(this).apply{clipChildren=false}
    val glow=BreathGlow(this,0xffff7a1e.toInt(),if(light).18f else .28f,!reduced)
    val flame=FlameArt(this,1f)
    stage.addView(glow,FrameLayout.LayoutParams(d(150),d(150),Gravity.CENTER))
    stage.addView(flame,FrameLayout.LayoutParams(d(84),d(105),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    box.addView(stage,LinearLayout.LayoutParams(-1,d(116)))
    space(box,6)
    val number=Odometer(this,"$value",56f,fg,true)
    box.addView(number,LinearLayout.LayoutParams(-2,-2))
    val unit=text("минут в день",15f,ink(goalLevel(value).color),true).apply{gravity=Gravity.CENTER}
    box.addView(unit,LinearLayout.LayoutParams(-2,-2));space(box,6)
    val desc=text(goalLevel(value).text,14f,muted).apply{gravity=Gravity.CENTER}
    box.addView(desc,LinearLayout.LayoutParams(-1,-2));space(box,18)
    val slider=GoalSlider(this,min,max,5,value)
    box.addView(slider,LinearLayout.LayoutParams(-1,-2));space(box,18)
    val save=text("Сохранить",16f,0xff1b1204.toInt(),true).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true}
    val saveBg=shape(goalLevel(value).color,28);save.background=saveBg;press(save)
    box.addView(save,LinearLayout.LayoutParams(-1,d(52)))
    fun scaleFor(v:Int)=.62f+.38f*(v-min)/(max-min).toFloat()
    flame.post{flame.scaleX=scaleFor(value);flame.scaleY=scaleFor(value);glow.level=.55f+.45f*(value-min)/(max-min).toFloat()}
    var shownColor=goalLevel(value).color;var colorAnim:ValueAnimator?=null
    slider.color=shownColor
    slider.onChange={v->
        val old=value;value=v;val lvl=goalLevel(v)
        number.set("$v","$old",up=v>old)
        val s=scaleFor(v)
        if(reduced){flame.scaleX=s;flame.scaleY=s}else flame.animate().scaleX(s).scaleY(s).setDuration(320).setInterpolator(OvershootInterpolator(1.8f)).start()
        glow.level=.55f+.45f*(v-min)/(max-min).toFloat()
        desc.text=lvl.text
        if(lvl.color!=shownColor){val from=shownColor;shownColor=lvl.color;colorAnim?.cancel()
            colorAnim=ValueAnimator.ofObject(ArgbEvaluator(),from,lvl.color).apply{duration=if(reduced)0L else 320L;addUpdateListener{val c=it.animatedValue as Int;saveBg.setColor(c);slider.color=c;unit.setTextColor(ink(c))};start()}}
    }
    val sheet=dialog().setTitle("Огонёк").setView(box).show()
    save.setOnClickListener{val v=value;updateStatistics{it.put("goal",v)};saved(v);sheet.dismiss()}
}

/** Rail with snapping ticks: the thumb follows the finger, the value snaps to steps (with a light tick), on release the thumb glides onto the step. */
internal class GoalSlider(private val a:MainActivity,private val lo:Int,private val hi:Int,private val step:Int,initial:Int):View(a){
    var onChange:(Int)->Unit={}
    var color=Color.WHITE; set(v){field=v;invalidate()}
    var value=initial; private set
    private var thumb=frac(initial)
    private var dragging=false
    private var press=0f
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels=setOf(15,30,45,60,90)
    private var glide:ValueAnimator?=null
    init{isClickable=true;isFocusable=true;contentDescription="Минут в день: $initial";p.textSize=11*a.resources.displayMetrics.scaledDensity}
    private fun frac(v:Int)=(v-lo)/(hi-lo).toFloat()
    private val pad get()=a.d(16).toFloat()
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),a.d(66))
    private fun valueAt(x:Float):Int{val f=((x-pad)/(width-2*pad)).coerceIn(0f,1f);return lo+((f*(hi-lo)/step).roundToInt()*step)}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);dragging=true;glide?.cancel();animatePress(1f);move(e.x)}
            MotionEvent.ACTION_MOVE->move(e.x)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;animatePress(0f);settle()}
        };return true
    }
    private fun move(x:Float){thumb=((x-pad)/(width-2*pad)).coerceIn(0f,1f);val v=valueAt(x);if(v!=value){value=v;contentDescription="Минут в день: $v";if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onChange(v)};invalidate()}
    private fun settle(){val target=frac(value);val from=thumb;glide=ValueAnimator.ofFloat(0f,1f).apply{duration=260;interpolator=PathInterpolator(.2f,1.3f,.4f,1f);addUpdateListener{thumb=from+(target-from)*(it.animatedValue as Float);invalidate()};start()}}
    private fun animatePress(to:Float){val from=press;ValueAnimator.ofFloat(0f,1f).apply{duration=160;addUpdateListener{press=from+(to-from)*(it.animatedValue as Float);invalidate()};start()}}
    override fun onDraw(c:Canvas){
        val w=width-2*pad;val cy=a.d(22).toFloat();val rh=a.d(8).toFloat()
        p.color=if(a.light)0xffe8e8ea.toInt() else 0xff333336.toInt();c.drawRoundRect(pad,cy-rh/2,pad+w,cy+rh/2,rh/2,rh/2,p)
        p.color=color;c.drawRoundRect(pad,cy-rh/2,max(pad+rh,pad+w*thumb),cy+rh/2,rh/2,rh/2,p)
        p.textAlign=Paint.Align.CENTER
        var v=lo;while(v<=hi){val x=pad+w*frac(v);val big=v in labels;p.color=if(a.light)0x33000000 else 0x33ffffff;val th=a.d(if(big)7 else 4).toFloat()
            c.drawRoundRect(x-a.d(1),cy+a.d(10),x+a.d(1),cy+a.d(10)+th,a.d(1).toFloat(),a.d(1).toFloat(),p)
            if(big){p.color=if(v==value)a.fg else a.muted;c.drawText("$v",x,cy+a.d(34),p)}
            v+=step}
        val tx=pad+w*thumb;val r=a.d(13)*(1f+.15f*press)
        p.color=0x40000000;c.drawCircle(tx,cy+a.d(2),r+a.d(1),p)
        p.color=Color.WHITE;c.drawCircle(tx,cy,r,p)
        p.color=color;c.drawCircle(tx,cy,a.d(4)*(1f+.3f*press),p)
    }
}
