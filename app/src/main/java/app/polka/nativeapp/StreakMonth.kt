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
 * The streak card's month: opens under the week, the card growing and the weeks coming in one after another. Each day
 * is a rounded tile in the week strip's colours (approved «месяц плитками»); arrows page months.
 */
internal class StreakMonth(private val a:MainActivity,private val model:ReadingStatistics,private val today:LocalDate,private val motion:Boolean):LinearLayout(a){
    private var month=YearMonth.from(today)
    private var open=false
    var onToggle:((Boolean)->Unit)?=null
    private val title=a.text("",17f,a.fg,true)
    private val year=a.text("",13f,a.muted)
    private val grid=MonthGrid()
    // approved: plain arrows, no circles (the card already has its round button)
    private fun arrow(kind:String,label:String,click:()->Unit)=FrameLayout(a).apply{contentDescription=label;isClickable=true;setOnClickListener{click()}
        addView(IconView(a,kind,if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER));a.press(this)}
    private val prev=arrow("back","Предыдущий месяц"){go(-1)}
    private val next=arrow("next","Следующий месяц"){go(1)}
    private var anim:ValueAnimator?=null
    init{
        orientation=VERTICAL;visibility=GONE;clipChildren=false
        // month name on the left, small round arrows on the right; no separating lines
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(20),0,a.d(8))}
        val name=a.line().apply{gravity=Gravity.BOTTOM};name.addView(title);name.addView(year.apply{setPadding(a.d(6),0,0,a.d(1))})
        bar.addView(name,LayoutParams(0,-2,1f));bar.addView(prev,LayoutParams(a.d(36),a.d(36)));bar.addView(next,LayoutParams(a.d(36),a.d(36)).apply{marginStart=a.d(2);marginEnd=-a.d(10)})
        addView(bar,LayoutParams(-1,-2))
        val names=a.line();listOf("пн","вт","ср","чт","пт","сб","вс").forEachIndexed{i,n->names.addView(a.text(n,11f,a.muted).apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f).apply{if(i>0)marginStart=a.d(6)})}
        addView(names,LayoutParams(-1,-2));addView(grid,LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        bind()
    }
    private fun bind(){title.text=month.month.getDisplayName(TextStyle.FULL_STANDALONE,Locale("ru")).replaceFirstChar{it.titlecase()};year.text="${month.year}"
        next.alpha=if(month<YearMonth.from(today))1f else .3f;next.isEnabled=month<YearMonth.from(today);grid.requestLayout();grid.invalidate()}
    private fun go(dir:Int){val target=month.plusMonths(dir.toLong());if(target>YearMonth.from(today))return
        if(!motion){month=target;bind();return}
        grid.animate().cancel();grid.animate().alpha(0f).translationX(-dir*a.d(24).toFloat()).setDuration(130).withEndAction{month=target;bind();grid.translationX=dir*a.d(24).toFloat();grid.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    fun toggle(){open=!open;anim?.cancel();onToggle?.invoke(open)
        if(!motion){visibility=if(open)VISIBLE else GONE;grid.phase=if(open)1f else 0f;grid.invalidate();return}
        val w=(parent as View).width-(parent as View).paddingLeft-(parent as View).paddingRight
        measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));val full=measuredHeight
        val from=if(visibility==VISIBLE)height else 0;val to=if(open)full else 0
        visibility=VISIBLE;layoutParams=layoutParams.apply{height=from}
        anim=ValueAnimator.ofInt(from,to).apply{duration=if(open)460 else 340;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{val h=it.animatedValue as Int;layoutParams=layoutParams.apply{height=h}}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(open)layoutParams=layoutParams.apply{height=ViewGroup.LayoutParams.WRAP_CONTENT} else visibility=GONE}});start()}
        alpha=1f;grid.reveal(open)
    }
    private inner class MonthGrid:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;textSize=13.5f*a.resources.displayMetrics.scaledDensity;fontFeatureSettings="tnum"}
        var phase=0f
        /** A day that counts in the streak: the goal met (or marked by hand), or a day brought back. */
        private fun kept(d:LocalDate)=model.met(d)||model.ledger.mark(d)==ReadingStatistics.Mark.REPAIRED
        /** A tap on a past day: mark it as a reading day by hand (a paper book), or take such a mark back. */
        override fun onTouchEvent(e:android.view.MotionEvent):Boolean{
            if(e.actionMasked==android.view.MotionEvent.ACTION_DOWN)return true
            if(e.actionMasked!=android.view.MotionEvent.ACTION_UP)return true
            val lead=month.atDay(1).dayOfWeek.value-1;val col=(e.x/(cell(width)+gap)).toInt().coerceIn(0,6);val row=(e.y/rowH).toInt()
            val n=row*7+col-lead+1;if(n<1||n>month.lengthOfMonth())return true
            val d=month.atDay(n);if(d.isAfter(today))return true
            val entry=model.days.optJSONObject(d.toString());val byHand=entry?.optBoolean("manual")==true
            if(model.met(d)&&!byHand)return true
            val name="${d.dayOfMonth} ${java.time.format.DateTimeFormatter.ofPattern("MMMM",Locale("ru")).format(d)}"
            if(byHand)a.confirm("Снять отметку с $name?","День перестанет считаться в серии."){mark(d,false)}
            else a.confirm("Отметить $name?","Например, если в этот день вы читали бумажную книгу. День засчитается в серию."){mark(d,true)}
            return true}
        private fun mark(d:LocalDate,on:Boolean){
            val key=d.toString()
            fun apply(days:org.json.JSONObject){val day=days.optJSONObject(key)?:org.json.JSONObject().also{days.put(key,it)}
                if(on){day.put("met",true).put("manual",true)}else{day.remove("met");day.remove("manual");if(day.length()==0)days.remove(key)}}
            (model.source.optJSONObject("days")?:org.json.JSONObject().also{model.source.put("days",it)}).let{apply(it)}
            a.updateStatistics{data->apply(data.optJSONObject("days")?:org.json.JSONObject().also{data.put("days",it)})}
            a.refreshProfile()}
        /** The weeks come in one after another from the top (and leave together, quicker, when the month closes). */
        fun reveal(opening:Boolean){val from=phase;ValueAnimator.ofFloat(from,if(opening)1f else 0f).apply{duration=if(opening)560 else 240;startDelay=if(opening)60 else 0
            interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{phase=it.animatedValue as Float;invalidate()};start()}}
        private val rows get()=(month.atDay(1).dayOfWeek.value-1+month.lengthOfMonth()+6)/7
        private val gap get()=a.d(6).toFloat()
        private fun cell(w:Int)=(w-gap*6)/7f
        private val rowH get()=cell(width)+gap
        override fun onMeasure(w:Int,h:Int){val width=MeasureSpec.getSize(w);setMeasuredDimension(width,(rows*(cell(width)+gap)-gap).toInt().coerceAtLeast(0))}
        private val tile=RectF()
        /** Approved «месяц плитками»: a rounded square a day, in the strip's colours — lit, kept by a freeze, missed;
         *  today fills up from the bottom; the days ahead are only their faint numbers. */
        override fun onDraw(c:Canvas){
            val lead=month.atDay(1).dayOfWeek.value-1;val cw=cell(width);val r=a.d(10).toFloat()
            val orange=if(a.light)0xfff07c12.toInt() else 0xffff8a1e.toInt();val ice=if(a.light)0xff5aaee6.toInt() else 0xff8fd0fa.toInt();val empty=if(a.light)0xffe6e6ea.toInt() else 0xff3a3a3d.toInt()
            val dark=0xff1b1b1d.toInt();val goalMs=model.dailyGoal*60000L
            text.textSize=13.5f*a.resources.displayMetrics.scaledDensity;text.typeface=PolkaFonts.sans(600)
            val step=.14f;val span=1f+step*(rows-1)
            for(row in 0 until rows){val t=((phase*span)-row*step).coerceIn(0f,1f);if(t<=0f)continue
                val dy=(1f-t)*a.d(8);val alpha=(t*255).toInt()
                for(col in 0 until 7){val day=row*7+col-lead+1;if(day<1||day>month.lengthOfMonth())continue;val d=month.atDay(day)
                    val x=col*(cw+gap);val y=row*rowH+dy;tile.set(x,y,x+cw,y+cw)
                    val future=d.isAfter(today);val met=kept(d);val frozen=model.ledger.mark(d)==ReadingStatistics.Mark.FROZEN
                    var ink:Int
                    when{
                        future->ink=(a.muted and 0xffffff) or 0x59000000
                        met->{p.color=orange;p.alpha=alpha;c.drawRoundRect(tile,r,r,p);ink=dark}
                        frozen->{p.color=ice;p.alpha=alpha;c.drawRoundRect(tile,r,r,p);ink=dark}
                        d==today->{p.color=empty;p.alpha=alpha;c.drawRoundRect(tile,r,r,p)
                            val f=(model.ms(d).toFloat()/goalMs).coerceIn(0f,1f)
                            if(f>0f){c.save();c.clipRect(tile.left,tile.bottom-cw*f,tile.right,tile.bottom);p.color=orange;p.alpha=alpha;c.drawRoundRect(tile,r,r,p);c.restore()}
                            ink=if(f>=.55f)dark else a.fg}
                        else->{p.color=empty;p.alpha=alpha;c.drawRoundRect(tile,r,r,p);ink=if(a.light)0xff6e6e73.toInt() else 0xffa4a4a8.toInt()}}
                    text.color=ink;text.alpha=(Color.alpha(ink)*t).toInt()
                    c.drawText("$day",tile.centerX(),tile.centerY()-(text.ascent()+text.descent())/2,text)}
            }
        }
    }
}
