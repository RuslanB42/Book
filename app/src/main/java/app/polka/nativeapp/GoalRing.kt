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
        left<=0->GoalWords(null,true,if(left<0)"на ${-left} больше цели" else "цель выполнена","")
        year<today.year->GoalWords(null,false,"не хватило $left","")
        year==today.year->{val per=left.toDouble()/(13-today.monthValue)
            GoalWords(null,true,"ещё $left до конца года",if(per<1)"меньше книги в месяц" else "по ${per.roundToInt()} в месяц")}
        else->GoalWords(null,true,"ещё $left","")}}
internal fun MainActivity.goalChip(t:String,good:Boolean)=text(t,12.5f,if(good)(if(light)0xff007a48.toInt() else 0xff7bdca6.toInt()) else (if(light)0xffb0601c.toInt() else 0xffffb066.toInt()),true).apply{gravity=Gravity.CENTER;setPadding(d(10),0,d(10),0)
    background=shape(if(good)(if(light)0x1f009a5c else 0x2437c080) else (if(light)0x1fe8640c else 0x1fff8a1e),13)}

/** The covers of the year's books, newest first, overlapping a little; what does not fit is «+N». */
internal fun MainActivity.goalShelf(list:List<YearBook>,fit:Int):LinearLayout{
    val row=line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
    val shown=list.asReversed().take(fit)
    // the covers as on the full-screen tier list: sharp, a soft shadow, side by side
    shown.forEachIndexed{i,yb->row.addView(CoverView(this,yb.book,cover(yb.book)).apply{softShadow=true},LinearLayout.LayoutParams(d(40),d(60)).apply{if(i>0)marginStart=d(8)})}
    val more=list.size-shown.size
    if(more>0)row.addView(text("+$more",12.5f,if(light)0xff55555a.toInt() else 0xffcfcfd4.toInt(),true).apply{gravity=Gravity.CENTER
        background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0f000000 else 0x14ffffff)}},LinearLayout.LayoutParams(d(32),d(32)).apply{marginStart=d(8)})
    return row
}

/** The year's three numbers as equal quiet tiles (approved): books (of the goal, with its thin bar), hours read,
 *  the average rating. [big] is the full-screen size. */
internal fun MainActivity.yearTiles(list:List<YearBook>,goal:Int,big:Boolean):LinearLayout{
    val row=line().apply{gravity=Gravity.TOP}
    val num=if(big)26f else 22f;val unit=if(big)15f else 13f
    fun tile(build:(LinearLayout)->Unit){val t=column().apply{background=shape(if(light)0x0a000000 else (if(big)0xff242426.toInt() else 0x0affffff),if(big)18 else 16);setPadding(d(12),d(12),d(12),d(13))}
        build(t);row.addView(t,LinearLayout.LayoutParams(0,-1,1f).apply{if(row.childCount>0)marginStart=d(8)})}
    fun value(main:String,tail:String,lead:String="")=android.text.SpannableStringBuilder().apply{
        if(lead.isNotEmpty()){append(lead);setSpan(android.text.style.ForegroundColorSpan(StarRow.starYellow(this@yearTiles)),0,length,0);setSpan(android.text.style.RelativeSizeSpan(.78f),0,length,0);append(" ")}
        append(main);if(tail.isNotEmpty()){val st=length;append(" $tail");setSpan(android.text.style.ForegroundColorSpan(if(light)0xff9a9aa0.toInt() else 0xff6e6e73.toInt()),st,length,0);setSpan(android.text.style.AbsoluteSizeSpan(unit.toInt(),true),st,length,0)}}
    fun caption(t:String)=text(t,if(big)12f else 11.5f,muted).apply{setPadding(0,d(6),0,0)}
    val done=list.size
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=value("$done",if(goal>0)"/$goal" else "");maxLines=1});t.addView(caption(booksWord(done)))
        if(goal>0)t.addView(View(this).apply{background=object:android.graphics.drawable.Drawable(){
            private val pp=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c:Canvas){val b=bounds;val r=b.height()/2f;pp.color=if(light)0x14000000 else 0x14ffffff;c.drawRoundRect(RectF(b),r,r,pp)
                val f=(done.toFloat()/goal).coerceIn(0f,1f);if(f>0f){pp.color=if(light)0xff009a5c.toInt() else 0xff4fd6a2.toInt();c.drawRoundRect(RectF(b.left.toFloat(),b.top.toFloat(),b.left+max(b.height().toFloat(),b.width()*f),b.bottom.toFloat()),r,r,pp)}}
            override fun setAlpha(x:Int){};override fun setColorFilter(x:ColorFilter?){};@Deprecated("") override fun getOpacity()=PixelFormat.TRANSLUCENT}},LinearLayout.LayoutParams(-1,d(4)).apply{topMargin=d(if(big)14 else 10)})}
    val ms=list.sumOf{it.ms};val hours=ms/3_600_000L
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=if(hours>0)value("$hours","ч") else value("${ms/60_000L}","мин");maxLines=1});t.addView(caption("за чтением"))}
    val rated=list.map{it.book.optInt("rating")}.filter{it>0}
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=if(rated.isEmpty())"—" else value(String.format(java.util.Locale("ru"),"%.1f",rated.average()),"","★");maxLines=1});t.addView(caption("оценка"))}
    row.contentDescription="$done ${booksWord(done)}"+(if(goal>0)" из $goal" else "")+", за чтением ${if(hours>0)"$hours ч" else "${ms/60_000L} мин"}"+if(rated.isNotEmpty())", средняя оценка ${String.format(java.util.Locale("ru"),"%.1f",rated.average())}" else ""
    return row
}

/** A book of the year: its cover clean, and under it the stars it got and the hours it took. */
internal fun MainActivity.yearBookCell(yb:YearBook,w:Int):LinearLayout{
    val cell=column().apply{clipChildren=false;clipToPadding=false}
    val cv=CoverView(this,yb.book,cover(yb.book)).apply{softShadow=true;isClickable=true;contentDescription=yb.book.str("title");setOnClickListener{detail(yb.book,this)}}
    cell.addView(cv,LinearLayout.LayoutParams(w,(w*1.5f).toInt()))
    val under=line().apply{gravity=Gravity.CENTER_VERTICAL}
    val r=yb.book.optInt("rating")
    under.addView(text(if(r>0)"★ $r" else "",11.5f,StarRow.starYellow(this),true),LinearLayout.LayoutParams(0,-2,1f))
    val h=yb.ms/3_600_000L
    under.addView(text(if(h>0)"$h ч" else if(yb.ms>=60_000L)"${yb.ms/60_000L} мин" else "",11.5f,if(light)0xff55555a.toInt() else 0xffcfcfd4.toInt(),true))
    cell.addView(under,LinearLayout.LayoutParams(w,-2).apply{topMargin=d(6)})
    return cell
}

/** The month names of the year page stay at the top while their books scroll under them. */
internal class YearRails(private val a:MainActivity){
    val rows=mutableListOf<Pair<View,View>>()
    private val at=IntArray(2)
    fun stick(scroll:View){scroll.getLocationOnScreen(at);val top=at[1]+a.d(8)
        rows.forEach{(row,label)->row.getLocationOnScreen(at);val room=row.height-label.height-a.d(22);label.translationY=(top-at[1]).toFloat().coerceIn(0f,max(0,room).toFloat())}}
    fun reset(){rows.forEach{it.second.translationY=0f}}
}

/** The full-screen year (approved «Мой год в книгах, месяцы слева»): the year large, the three numbers as tiles,
 *  then the months from this one back to January — the month, its hours and books on the left, its covers on the
 *  right with stars and hours; an empty month is one quiet line. */
internal fun MainActivity.goalYearPage(model:ReadingStatistics,list:List<YearBook>,goal:Int,year:Int,today:LocalDate,motion:Boolean):LinearLayout{
    val page=column().apply{setPadding(d(20),0,d(20),0);clipChildren=false}
    val rails=YearRails(this);page.tag=rails
    page.addView(text("МОЙ ГОД В КНИГАХ",13f,if(light)0xff6e6e73.toInt() else 0xffa9a9ae.toInt(),true).apply{letterSpacing=.16f},LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(18)})
    page.addView(text("$year",84f,fg,true,serif=true).apply{includeFontPadding=false;maxLines=1},LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(2)})
    page.addView(yearTiles(list,goal,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(20)})
    val last=if(year==today.year)today.monthValue else 12
    val names=listOf("Январь","Февраль","Март","Апрель","Май","Июнь","Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь")
    val railW=74;val gap=9
    val cw=66;val perRow=max(1,(resources.configuration.screenWidthDp-40-railW-14+gap)/(cw+gap))
    val byMonth=list.groupBy{it.end.monthValue}
    space(page,26)
    for(m in last downTo 1){val books=byMonth[m].orEmpty().sortedByDescending{it.end}
        val row=line().apply{gravity=Gravity.TOP;clipChildren=false}
        val left=column()
        left.addView(text(names[m-1],15f,if(books.isEmpty())(if(light)0xffa8a8ad.toInt() else 0xff5c5c62.toInt()) else fg,true,serif=true).apply{maxLines=1})
        row.addView(left,LinearLayout.LayoutParams(d(railW),-2).apply{marginEnd=d(14)})
        if(books.isEmpty()){row.addView(text("без книг",12.5f,if(light)0xffa8a8ad.toInt() else 0xff5c5c62.toInt()),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(2)})
            page.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(14)});continue}
        val ms=books.sumOf{it.ms};val h=ms/3_600_000L
        left.addView(text(if(h>0)"$h ч" else "${ms/60_000L} мин",12f,muted).apply{setPadding(0,d(4),0,0)})
        left.addView(text("${books.size} ${booksWord(books.size)}",12f,if(light)0xff9a9aa0.toInt() else 0xff6e6e73.toInt()).apply{setPadding(0,d(1),0,0)})
        val grid=column().apply{clipChildren=false}
        books.chunked(perRow).forEach{chunk->val r=line().apply{clipChildren=false};chunk.forEach{yb->r.addView(yearBookCell(yb,d(cw)),LinearLayout.LayoutParams(-2,-2).apply{if(r.childCount>0)marginStart=d(gap)})}
            grid.addView(r,LinearLayout.LayoutParams(-2,-2).apply{if(grid.childCount>0)topMargin=d(14)})}
        row.addView(grid,LinearLayout.LayoutParams(0,-2,1f))
        page.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(26)})
        rails.rows.add(row to left)
        row.contentDescription="${names[m-1]}: ${books.size} ${booksWord(books.size)}"}
    return page
}
