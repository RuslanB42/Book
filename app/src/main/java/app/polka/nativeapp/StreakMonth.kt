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
 * The streak card's month: opens under the week with a smooth height reveal. Days that kept the streak join into
 * warm capsules within each week row, today is ringed, future days are faint; arrows page through months.
 */
internal class StreakMonth(private val a:MainActivity,private val model:ReadingStatistics,private val today:LocalDate,private val motion:Boolean):LinearLayout(a){
    private var month=YearMonth.from(today)
    private var open=false
    private val title=a.text("",16f,a.fg,true)
    private val grid=MonthGrid()
    private val prev=a.icon("back","Предыдущий месяц"){go(-1)}
    private val next=a.icon("next","Следующий месяц"){go(1)}
    private var anim:ValueAnimator?=null
    init{
        orientation=VERTICAL;visibility=GONE;clipChildren=false
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(18),0,a.d(8))}
        bar.addView(prev,LayoutParams(a.d(40),a.d(40)));bar.addView(title.apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f));bar.addView(next,LayoutParams(a.d(40),a.d(40)))
        addView(bar,LayoutParams(-1,-2))
        val names=a.line();listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach{names.addView(a.text(it,12f,a.muted).apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f))}
        addView(names,LayoutParams(-1,-2));addView(grid,LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        bind()
    }
    private fun bind(){title.text=month.month.getDisplayName(TextStyle.FULL_STANDALONE,Locale("ru")).replaceFirstChar{it.titlecase()}+if(month.year!=today.year)" ${month.year}" else ""
        next.alpha=if(month<YearMonth.from(today))1f else .3f;next.isEnabled=month<YearMonth.from(today);grid.requestLayout();grid.invalidate()}
    private fun go(dir:Int){val target=month.plusMonths(dir.toLong());if(target>YearMonth.from(today))return
        if(!motion){month=target;bind();return}
        grid.animate().cancel();grid.animate().alpha(0f).translationX(-dir*a.d(24).toFloat()).setDuration(130).withEndAction{month=target;bind();grid.translationX=dir*a.d(24).toFloat();grid.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    fun toggle(){open=!open;anim?.cancel()
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
        private val rowH get()=a.d(40)
        override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),rows*rowH)
        override fun onDraw(c:Canvas){
            val lead=month.atDay(1).dayOfWeek.value-1;val cw=width/7f;val r=a.d(15).toFloat();val quiet=if(a.light)0xffeeeeec.toInt() else 0xff2f2f31.toInt()
            for(row in 0 until rows){val cy=row*rowH+rowH/2f
                // join consecutive met days inside this row into capsules
                var col=0
                while(col<7){val day=row*7+col-lead+1;if(day<1||day>month.lengthOfMonth()){col++;continue}
                    val d=month.atDay(day);if(!model.met(d)){col++;continue}
                    var end=col;while(end+1<7){val nd=row*7+end+1-lead+1;if(nd>month.lengthOfMonth()||!model.met(month.atDay(nd)))break;end++}
                    val left=col*cw+cw/2-r;val right=left+(end*cw+cw/2+r-left)*phase.coerceAtLeast(.001f)
                    p.shader=LinearGradient(left,0f,right.coerceAtLeast(left+1),0f,0xffff5a2c.toInt(),0xffff9b2b.toInt(),Shader.TileMode.CLAMP);p.style=Paint.Style.FILL
                    c.drawRoundRect(left,cy-r,maxOf(right,left+2*r*phase),cy+r,r,r,p);p.shader=null
                    col=end+1}
                for(col2 in 0 until 7){val day=row*7+col2-lead+1;if(day<1||day>month.lengthOfMonth())continue;val d=month.atDay(day);val cx=col2*cw+cw/2
                    val met=model.met(d);val future=d.isAfter(today)
                    if(!met&&!future&&model.ms(d)>0){p.color=quiet;c.drawCircle(cx,cy,r,p)}
                    if(d==today&&!met){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=a.accentFill;c.drawCircle(cx,cy,r-a.d(1),p);p.style=Paint.Style.FILL}
                    text.color=when{met->Color.WHITE;future->(a.muted and 0xffffff) or 0x66000000;d==today->a.fg;else->a.muted};text.isFakeBoldText=met||d==today
                    c.drawText("$day",cx,cy-(text.ascent()+text.descent())/2,text)}
            }
        }
    }
}
