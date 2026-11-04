package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import java.time.*
import java.time.format.TextStyle
import java.util.Locale

/**
 * The streak card's month (widget style): opens under the week with a smooth height reveal. Streak days are glowing
 * orange circles joined by a soft ribbon within each row, today is ringed, future days are faint; arrows page months.
 */
internal class StreakMonth(private val a:MainActivity,private val model:ReadingStatistics,private val today:LocalDate,private val motion:Boolean):LinearLayout(a){
    private var month=YearMonth.from(today)
    private var open=false
    var onToggle:((Boolean)->Unit)?=null
    private val title=a.text("",17f,a.fg,true,serif=true)
    private val year=a.text("",13f,a.muted)
    private val grid=MonthGrid()
    private fun arrow(kind:String,label:String,click:()->Unit)=FrameLayout(a).apply{contentDescription=label;isClickable=true;setOnClickListener{click()}
        background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(if(a.light)0x0d000000 else 0x12ffffff)}
        addView(IconView(a,kind,a.fg),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER));a.press(this)}
    private val prev=arrow("back","Предыдущий месяц"){go(-1)}
    private val next=arrow("next","Следующий месяц"){go(1)}
    private var anim:ValueAnimator?=null
    init{
        orientation=VERTICAL;visibility=GONE;clipChildren=false
        // month name on the left, small round arrows on the right; no separating lines
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(20),0,a.d(8))}
        val name=a.line().apply{gravity=Gravity.BOTTOM};name.addView(title);name.addView(year.apply{setPadding(a.d(6),0,0,a.d(1))})
        bar.addView(name,LayoutParams(0,-2,1f));bar.addView(prev,LayoutParams(a.d(32),a.d(32)));bar.addView(next,LayoutParams(a.d(32),a.d(32)).apply{marginStart=a.d(6)})
        addView(bar,LayoutParams(-1,-2))
        val names=a.line();listOf("пн","вт","ср","чт","пт","сб","вс").forEach{names.addView(a.text(it,11f,a.muted).apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f))}
        addView(names,LayoutParams(-1,-2));addView(grid,LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        bind()
    }
    private fun bind(){title.text=month.month.getDisplayName(TextStyle.FULL_STANDALONE,Locale("ru")).replaceFirstChar{it.titlecase()};year.text="${month.year}"
        next.alpha=if(month<YearMonth.from(today))1f else .3f;next.isEnabled=month<YearMonth.from(today);grid.requestLayout();grid.invalidate()}
    private fun go(dir:Int){val target=month.plusMonths(dir.toLong());if(target>YearMonth.from(today))return
        if(!motion){month=target;bind();return}
        grid.animate().cancel();grid.animate().alpha(0f).translationX(-dir*a.d(24).toFloat()).setDuration(130).withEndAction{month=target;bind();grid.translationX=dir*a.d(24).toFloat();grid.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    fun toggle(){open=!open;anim?.cancel();onToggle?.invoke(open)
        if(!motion){visibility=if(open)VISIBLE else GONE;return}
        val w=(parent as View).width-(parent as View).paddingLeft-(parent as View).paddingRight
        measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));val full=measuredHeight
        val from=if(visibility==VISIBLE)height else 0;val to=if(open)full else 0
        visibility=VISIBLE;layoutParams=layoutParams.apply{height=from}
        anim=ValueAnimator.ofInt(from,to).apply{duration=if(open)460 else 340;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{val h=it.animatedValue as Int;layoutParams=layoutParams.apply{height=h};alpha=if(full>0)(h.toFloat()/full).coerceIn(0f,1f) else 1f}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(open)layoutParams=layoutParams.apply{height=ViewGroup.LayoutParams.WRAP_CONTENT} else visibility=GONE}});start()}
        if(open)grid.reveal()
    }
    private inner class MonthGrid:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;textSize=13.5f*a.resources.displayMetrics.scaledDensity;fontFeatureSettings="tnum"}
        private var phase=1f
        fun reveal(){phase=0f;ValueAnimator.ofFloat(0f,1f).apply{startDelay=120;duration=620;interpolator=PathInterpolator(.22f,1f,.36f,1f);addUpdateListener{phase=it.animatedValue as Float;invalidate()};start()}}
        private val rows get()=(month.atDay(1).dayOfWeek.value-1+month.lengthOfMonth()+6)/7
        private val rowH get()=a.d(42)
        override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),rows*rowH)
        override fun onDraw(c:Canvas){
            val lead=month.atDay(1).dayOfWeek.value-1;val cw=width/7f;val r=a.d(15).toFloat();val quiet=if(a.light)0x0f000000 else 0x14ffffff
            fun dayAt(row:Int,col:Int):LocalDate?{val day=row*7+col-lead+1;return if(day<1||day>month.lengthOfMonth())null else month.atDay(day)}
            for(row in 0 until rows){val cy=row*rowH+rowH/2f
                // a soft ribbon joins neighbouring streak days within the row
                for(col in 0 until 6){val d1=dayAt(row,col)?:continue;val d2=dayAt(row,col+1)?:continue
                    if(!model.met(d1)||!model.met(d2))continue
                    p.shader=null;p.color=0x38ff6e28;val x1=col*cw+cw/2;val x2=x1+cw*phase;c.drawRect(x1,cy-r*.62f,x2,cy+r*.62f,p)}
                for(col in 0 until 7){val d=dayAt(row,col)?:continue;val cx=col*cw+cw/2
                    val met=model.met(d);val future=d.isAfter(today)
                    if(met){val k=phase.coerceIn(0f,1f)
                        if(!a.light){p.shader=RadialGradient(cx,cy,r*1.45f,intArrayOf(0x24ff6e28,0x00ff6e28),null,Shader.TileMode.CLAMP);p.color=Color.WHITE;c.drawCircle(cx,cy,r*1.45f*k,p)}
                        p.color=Color.WHITE;p.shader=LinearGradient(cx-r,cy-r,cx+r*.4f,cy+r,0xffffb522.toInt(),0xffff5a1e.toInt(),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,r*k,p);p.shader=null}
                    else if(!future&&model.ms(d)>0){p.shader=null;p.color=quiet;c.drawCircle(cx,cy,r,p)}
                    if(d==today&&!met){p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=0xffffb522.toInt();c.drawCircle(cx,cy,r-a.d(1),p);p.style=Paint.Style.FILL}
                    text.typeface=PolkaFonts.sans(if(met||d==today)600 else 400)
                    text.color=when{met->Color.WHITE;future->(a.muted and 0xffffff) or 0x59000000;d==today->a.fg;else->a.muted}
                    c.drawText("${d.dayOfMonth}",cx,cy-(text.ascent()+text.descent())/2,text)}
            }
        }
    }
}
