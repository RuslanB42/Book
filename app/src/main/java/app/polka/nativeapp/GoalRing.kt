package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** «Цель на год», approved variant «Кольцо»: a progress ring with the count in its middle. Without a goal the
 *  ring stays an empty track and the middle shows how many books were read. */
internal class GoalRing(private val a:MainActivity,private val done:Int,private val goal:Int,private val big:Boolean,motion:Boolean):View(a){
    private val target=if(goal>0)(done.toFloat()/goal).coerceIn(0f,1f) else 0f
    private var shown=if(motion)0f else target
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val d=a.resources.displayMetrics.density
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        if(motion&&target>0f)addOnAttachStateChangeListener(object:OnAttachStateChangeListener{var ran=false
            override fun onViewAttachedToWindow(v:View){if(ran)return;ran=true
                ValueAnimator.ofFloat(0f,target).apply{startDelay=160;duration=900;interpolator=PathInterpolator(.2f,.8f,.2f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
            override fun onViewDetachedFromWindow(v:View){}})}
    override fun onDraw(c:Canvas){val s=minOf(width,height).toFloat();val sw=(if(big)18 else 12)*d;val cx=width/2f;val cy=height/2f;val r=(s-sw)/2f
        val box=RectF(cx-r,cy-r,cx+r,cy+r)
        p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=sw;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawCircle(cx,cy,r,p)
        if(shown>0f){p.color=Color.WHITE
            val g=SweepGradient(cx,cy,intArrayOf(goalGreen2(a),goalGreen(a),goalGreen2(a)),floatArrayOf(0f,.5f,1f));val m=Matrix();m.setRotate(-90f,cx,cy);g.setLocalMatrix(m);p.shader=g
            c.drawArc(box,-90f,360f*shown,false,p);p.shader=null}
        p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER
        p.typeface=PolkaFonts.display(600);p.textSize=(if(big)64 else 36)*d;p.color=a.fg
        c.drawText("$done",cx,cy+(if(big)6 else 4)*d,p)
        p.typeface=PolkaFonts.sans(400);p.textSize=(if(big)15 else 12)*d;p.color=if(a.light)0xff86868b.toInt() else 0xff8a8a8e.toInt()
        c.drawText(if(goal>0)"из ${ofBooks(goal)}" else MainActivity.countBooks(done).substringAfter(' '),cx,cy+(if(big)32 else 22)*d,p)}
}

/** What the ring says in words: the chip against the plan and up to two short lines (how many are left, how many a
 *  month). */
internal class GoalWords(val chip:String?,val good:Boolean,val first:String,val second:String)
internal fun goalWords(model:ReadingStatistics,year:Int,today:LocalDate,done:Int,target:Int):GoalWords{val left=target-done
    return when{
        target<=0->GoalWords(null,true,if(year>=today.year)"цель не задана" else "",if(year>=today.year)"Настройки → «Чтение»" else "")
        left<=0->GoalWords("цель выполнена",true,if(left<0)"на ${-left} больше" else "","")
        year<today.year->GoalWords("не хватило $left",false,"","")
        year==today.year->{val behind=model.expected(year,target)-done;val per=left.toDouble()/(13-today.monthValue)
            GoalWords(if(behind>0)"отстаёте на $behind" else "по плану",behind<=0,"ещё $left до конца года",if(per<1)"меньше книги в месяц" else "по ${per.roundToInt()} в месяц")}
        else->GoalWords(null,true,"ещё $left","")}}
internal fun MainActivity.goalChip(t:String,good:Boolean)=text(t,12.5f,if(good)(if(light)0xff007a48.toInt() else 0xff7bdca6.toInt()) else (if(light)0xffb0601c.toInt() else 0xffffb066.toInt()),true).apply{gravity=Gravity.CENTER;setPadding(d(10),0,d(10),0)
    background=shape(if(good)(if(light)0x1f009a5c else 0x2437c080) else (if(light)0x1fe8640c else 0x1fff8a1e),13)}

/** The covers of the year's books, newest first, overlapping a little; what does not fit is «+N». */
internal fun MainActivity.goalShelf(list:List<YearBook>,fit:Int):LinearLayout{
    val row=line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
    val shown=list.asReversed().take(fit)
    shown.forEachIndexed{i,yb->row.addView(MiniCover(this,yb.book).apply{elevation=d(2).toFloat();translationZ=-i.toFloat()},LinearLayout.LayoutParams(d(34),d(51)).apply{if(i>0)marginStart=-d(12)})}
    val more=list.size-shown.size
    if(more>0)row.addView(text("+$more",12.5f,if(light)0xff55555a.toInt() else 0xffcfcfd4.toInt(),true).apply{gravity=Gravity.CENTER
        background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0f000000 else 0x14ffffff)}},LinearLayout.LayoutParams(d(32),d(32)).apply{marginStart=d(8)})
    return row
}

/** The full-screen year (variant «Кольцо»): the big ring, the chip and the words, the year's results in three tiles
 *  and the books month by month, newest first. */
internal fun MainActivity.goalYearPage(model:ReadingStatistics,list:List<YearBook>,goal:Int,year:Int,today:LocalDate,motion:Boolean):LinearLayout{
    val page=column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(d(18),0,d(18),0)}
    page.addView(GoalRing(this,list.size,goal,true,motion),LinearLayout.LayoutParams(d(210),d(210)).apply{topMargin=d(14)})
    val w=goalWords(model,year,today,list.size,goal)
    w.chip?.let{page.addView(goalChip(it,w.good),LinearLayout.LayoutParams(-2,d(26)).apply{topMargin=d(16)})}
    val words=listOf(w.first,w.second).filter{it.isNotBlank()}.joinToString(", ")
    if(words.isNotBlank())page.addView(text(words,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(8)})
    if(list.isEmpty())return page
    // the year's results
    val tiles=line().apply{gravity=Gravity.TOP}
    fun tile(value:String,caption:String){val t=column().apply{background=shape(if(light)0x0a000000 else 0x0dffffff,18);setPadding(d(12),d(12),d(12),d(12))}
        t.addView(text(value,20f,fg,true,serif=true));t.addView(text(caption,12f,muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(3)})
        tiles.addView(t,LinearLayout.LayoutParams(0,-1,1f).apply{if(tiles.childCount>0)marginStart=d(10)})}
    val ms=list.sumOf{it.ms};val hours=ms/3_600_000L
    tile(if(hours>0)"$hours ч" else "${max(1L,ms/60_000L)} мин","за чтением")
    val rated=list.map{it.book.optInt("rating")}.filter{it>0}
    if(rated.isNotEmpty())tile(String.format(java.util.Locale("ru"),"%.1f",rated.average()),"средняя оценка")
    val days=list.map{(java.time.temporal.ChronoUnit.DAYS.between(it.start,it.end)+1).toInt().coerceAtLeast(1)}.average().roundToInt()
    tile(daysWord(days),"на книгу")
    page.addView(tiles,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(22)})
    page.addView(text("По месяцам",13f,muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(24);bottomMargin=d(4);leftMargin=d(2)})
    val months=listOf("янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек")
    list.groupBy{it.end.monthValue}.toSortedMap(compareByDescending{it}).forEach{(m,books)->
        if(page.childCount>0&&page.getChildAt(page.childCount-1).tag=="month")page.addView(View(this).apply{setBackgroundColor(if(light)0x0d000000 else 0x0dffffff)},LinearLayout.LayoutParams(-1,1))
        val row=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(58);tag="month"}
        row.addView(text(months[m-1],13.5f,fg,true),LinearLayout.LayoutParams(d(56),-2))
        val covers=line().apply{clipChildren=false}
        books.sortedByDescending{it.end}.take(6).forEach{yb->covers.addView(MiniCover(this,yb.book).apply{elevation=d(2).toFloat()},LinearLayout.LayoutParams(d(26),d(39)).apply{if(covers.childCount>0)marginStart=d(5)})}
        row.addView(covers,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text("${books.size}",13f,muted))
        page.addView(row,LinearLayout.LayoutParams(-1,-2))}
    return page
}
