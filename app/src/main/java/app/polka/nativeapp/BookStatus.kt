package app.polka.nativeapp

import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «Хочу прочитать», «Читаю», «Прочитано», «Отложено»: one status per book, set by hand or moved forward by reading. */
internal object BookStatus {
    fun name(s:String)=when(s){"reading"->"Читаю";"done"->"Прочитано";"paused"->"Отложено";else->"Хочу прочитать"}
    fun icon(s:String)=when(s){"reading"->"book";"done"->"done";"paused"->"paused";else->"bookmark"}
    private val zone get()=ZoneId.systemDefault()
    fun day(ms:Long):LocalDate?=if(ms>0)Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() else null
    fun millis(d:LocalDate)=d.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
    fun format(d:LocalDate)=d.format(DateTimeFormatter.ofPattern(if(d.year==LocalDate.now().year)"d MMMM" else "d MMMM yyyy",Locale("ru")))
}

/** An icon whose kind can change (the status tag swaps it when the status does). */
internal class StatusIcon(private val a:MainActivity,kind:String,private val ink:Int):View(a){
    var kind=kind;set(v){field=v;invalidate()}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val s=minOf(width,height)/24f;c.save();c.translate((width-24f*s)/2f,(height-24f*s)/2f);c.scale(s,s);IosIcons.draw(c,kind,ink);c.restore()}
}

/** Sets the status by hand. Marking a book read keeps the place in it; the dates that go with the status are set once. */
internal fun MainActivity.setBookStatus(book:JSONObject,state:String){
    val latest=books.firstOrNull{it.str("id")==book.str("id")}?:book
    val was=Library.status(latest);if(was==state)return
    val now=System.currentTimeMillis();val c=latest.copyJson().put("state",state)
    if(state=="done")c.put("completedAt",now)
    if(state=="reading"&&was=="want"&&c.optLong("startedAt")==0L)c.put("startedAt",now)
    saveBook(c)
}

/** The status sheet: four statuses (the current one ticked) and, under them, when the book was started and finished. */
internal fun MainActivity.statusSheet(book:JSONObject){
    val id=book.str("id")
    var first=0L
    work({first=library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")}?.optJSONObject("books")?.optJSONObject(id)?.optLong("first")?:0L}){
        val latest=books.firstOrNull{it.str("id")==id}?:book
        val current=Library.status(latest)
        val box=column()
        var sheet:android.app.AlertDialog?=null
        Library.STATES.forEach{s->
            val row=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(52);setPadding(d(12),0,d(12),0);isClickable=true;isFocusable=true
                contentDescription=BookStatus.name(s)+if(s==current)", выбрано" else ""
                setOnClickListener{sheet?.dismiss();setBookStatus(latest,s)};press(this)}
            row.addView(StatusIcon(this,BookStatus.icon(s),fg),LinearLayout.LayoutParams(d(22),d(22)))
            row.addView(text(BookStatus.name(s),16f).apply{setPadding(d(16),0,d(8),0)},LinearLayout.LayoutParams(0,-2,1f))
            if(s==current)row.addView(StatusIcon(this,"check",fg),LinearLayout.LayoutParams(d(20),d(20)))
            box.addView(row,LinearLayout.LayoutParams(-1,d(52)))
        }
        val started=BookStatus.day(latest.optLong("startedAt").takeIf{it>0}?:first.takeIf{it>0}?:latest.optLong("openedAt"))
        val finished=if(current=="done")BookStatus.day(latest.optLong("completedAt")) else null
        val dates=mutableListOf<Triple<String,LocalDate?,String>>()
        if(current!="want")dates.add(Triple("Начата",started,"startedAt"))
        if(current=="done")dates.add(Triple("Прочитана",finished,"completedAt"))
        if(dates.isNotEmpty()){
            space(box,12)
            val card=column().apply{floatCard(this,24);setPadding(d(16),0,d(16),0)}
            dates.forEachIndexed{i,(label,date,key)->
                if(i>0)card.addView(View(this).apply{setBackgroundColor(if(light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1))
                val r=line().apply{gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true;contentDescription="$label, изменить"
                    setOnClickListener{sheet?.dismiss();bookDateEditor(latest,key,label,date)};press(this)}
                r.addView(text(label,15f,muted),LinearLayout.LayoutParams(0,-2,1f))
                r.addView(text(date?.let{BookStatus.format(it)}?:"Указать",15f))
                r.addView(IconView(this,"next",muted),LinearLayout.LayoutParams(d(16),d(16)).apply{marginStart=d(6)})
                card.addView(r,LinearLayout.LayoutParams(-1,d(48)))
            }
            box.addView(card)
        }
        sheet=dialog().setTitle("Статус").setView(box).show()
    }
}

/** A day picker for one of the book's dates (never in the future). The finish date goes to statistics and the year goal. */
internal fun MainActivity.bookDateEditor(book:JSONObject,key:String,title:String,initial:LocalDate?){
    val today=LocalDate.now();val date=initial?:today;val box=column()
    val captions=line();listOf("День","Месяц","Год").forEach{captions.addView(text(it,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};box.addView(captions)
    val row=line();val day=wheel(1,31,date.dayOfMonth,"День");val month=wheel(1,12,date.monthValue,"Месяц");val year=wheel(1900,today.year,date.year,"Год")
    fun limit(){month.maxValue=if(year.value==today.year)today.monthValue else 12;val ym=YearMonth.of(year.value,month.value);day.maxValue=if(ym==YearMonth.from(today))today.dayOfMonth else ym.lengthOfMonth()}
    year.setOnValueChangedListener{_,_,_->limit()};month.setOnValueChangedListener{_,_,_->limit()};limit()
    listOf(day,month,year).forEach{row.addView(it,LinearLayout.LayoutParams(0,d(144),1f))};box.addView(row)
    dialog().setTitle(title).setView(box).setPositiveButton("Сохранить"){_,_->
        val chosen=LocalDate.of(year.value,month.value,day.value)
        val latest=books.firstOrNull{it.str("id")==book.str("id")}?:return@setPositiveButton
        saveBook(latest.copyJson().put(key,BookStatus.millis(chosen)))}.show()
}
