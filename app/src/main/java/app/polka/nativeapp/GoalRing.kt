package app.polka.nativeapp

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** The year's three numbers as equal quiet tiles (approved): books (of the goal), hours read,
 *  the average rating. [big] is the full-screen size. */
internal fun MainActivity.yearTiles(list:List<YearBook>,goal:Int,big:Boolean):LinearLayout{
    val row=line().apply{gravity=Gravity.TOP}
    val num=if(big)26f else 22f;val unit=if(big)15f else 13f
    fun tile(build:(LinearLayout)->Unit){val t=column().apply{background=shape(if(light)0x0a000000 else (if(big)0xff242426.toInt() else 0x0affffff),if(big)18 else 16);setPadding(d(12),d(12),d(12),d(13))}
        build(t);row.addView(t,LinearLayout.LayoutParams(0,-1,1f).apply{if(row.childCount>0)marginStart=d(8)})}
    fun value(main:String,tail:String,lead:String="")=android.text.SpannableStringBuilder().apply{
        if(lead.isNotEmpty()){append(lead);setSpan(RoundStarSpan(StarRow.starYellow(this@yearTiles),.72f),0,length,0);append(" ")}
        append(main);if(tail.isNotEmpty()){val st=length;append(" $tail");setSpan(android.text.style.ForegroundColorSpan(if(light)0xff9a9aa0.toInt() else 0xff6e6e73.toInt()),st,length,0);setSpan(android.text.style.AbsoluteSizeSpan(unit.toInt(),true),st,length,0)}}
    fun caption(t:String)=text(t,if(big)12f else 11.5f,muted).apply{setPadding(0,d(6),0,0)}
    val done=list.size
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=value("$done",if(goal>0)"/$goal" else "");maxLines=1})
        t.addView(caption(booksWord(done)))}
    val ms=list.sumOf{it.ms};val hours=ms/3_600_000L
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=if(hours>0)value("$hours","ч") else value("${ms/60_000L}","мин");maxLines=1});t.addView(caption("за чтением"))}
    val rated=list.map{Rating.halves(it.book)/2.0}.filter{it>0}
    tile{t->t.addView(text("",num,fg,true,serif=true).apply{text=if(rated.isEmpty())"—" else value(String.format(java.util.Locale("ru"),"%.1f",rated.average()),"","★");maxLines=1});t.addView(caption("оценка"))}
    row.contentDescription="$done ${booksWord(done)}"+(if(goal>0)" из $goal" else "")+", за чтением ${if(hours>0)"$hours ч" else "${ms/60_000L} мин"}"+if(rated.isNotEmpty())", средняя оценка ${String.format(java.util.Locale("ru"),"%.1f",rated.average())}" else ""
    return row
}

/** A book of the year: its cover clean, and under it the stars it got and the hours it took. */
internal fun MainActivity.yearBookCell(yb:YearBook,w:Int):LinearLayout{
    val cell=column().apply{clipChildren=false;clipToPadding=false}
    val cv=CoverView(this,yb.book,cover(yb.book)).apply{isClickable=true;contentDescription=yb.book.str("title");setOnClickListener{detail(yb.book,this)}}
    cell.addView(cv,LinearLayout.LayoutParams(w,(w*1.5f).toInt()))
    val r=Rating.halves(yb.book)
    val stars=text("",11.5f,StarRow.starYellow(this),true).apply{maxLines=1;text=if(r>0)android.text.SpannableStringBuilder("★ ${Rating.text(r)}").apply{setSpan(RoundStarSpan(StarRow.starYellow(this@yearBookCell),.95f),0,1,0)} else ""}
    val h=yb.ms/3_600_000L
    val time=text(if(h>0)"$h ч" else if(yb.ms>=60_000L)"${yb.ms/60_000L} мин" else "",11.5f,if(light)0xff55555a.toInt() else 0xffcfcfd4.toInt(),true).apply{maxLines=1}
    // the stars and the time share one line under the cover; when both do not fit («★ 4,5» and «23 мин» under a
    // narrow cover) the time goes under the stars instead of the stars breaking in two
    val fits=stars.paint.measureText(stars.text.toString())+d(6)+time.paint.measureText(time.text.toString())<=w
    val under=if(fits)line().apply{gravity=Gravity.CENTER_VERTICAL} else column()
    under.addView(stars,if(fits)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-2,-2))
    under.addView(time,LinearLayout.LayoutParams(-2,-2).apply{if(!fits)topMargin=d(2)})
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
        books.chunked(perRow).forEach{chunk->val r=line().apply{clipChildren=false;gravity=Gravity.TOP};chunk.forEach{yb->r.addView(yearBookCell(yb,d(cw)),LinearLayout.LayoutParams(-2,-2).apply{if(r.childCount>0)marginStart=d(gap)})}
            grid.addView(r,LinearLayout.LayoutParams(-2,-2).apply{if(grid.childCount>0)topMargin=d(14)})}
        row.addView(grid,LinearLayout.LayoutParams(0,-2,1f))
        page.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=d(26)})
        rails.rows.add(row to left)
        row.contentDescription="${names[m-1]}: ${books.size} ${booksWord(books.size)}"}
    return page
}

/** The app's rounded star (as in the ratings everywhere) set inline in text, in place of the font's sharp «★». */
internal class RoundStarSpan(private val color:Int,private val scale:Float):android.text.style.ReplacementSpan(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun getSize(paint:Paint,text:CharSequence?,start:Int,end:Int,fm:Paint.FontMetricsInt?)=(paint.textSize*scale).roundToInt()
    override fun draw(c:Canvas,text:CharSequence?,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint){
        val size=paint.textSize*scale;p.color=color
        // sits on the baseline like a capital letter
        StarRow.drawStar(c,x,y-size*.92f,size,p)}
}
