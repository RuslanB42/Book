package app.polka.nativeapp

import org.json.JSONObject
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

/** Date-based aggregates. Unknown completion dates are never invented from import/open timestamps. */
class ReadingStatistics(val source:JSONObject,books:List<JSONObject>,val today:LocalDate=LocalDate.now(),val zone:ZoneId=ZoneId.systemDefault()) {
    data class Finished(val book:JSONObject,val date:LocalDate?)
    val finished=books.distinctBy { it.str("id") }.filter { Library.status(it)=="done" }.map { b ->
        val date=runCatching { val n=b.optLong("completedAt"); if(n>0) Instant.ofEpochMilli(n).atZone(zone).toLocalDate().takeIf { it.year>=1900 && it<=today } else null }.getOrNull()
        Finished(b,date)
    }
    val days=source.optJSONObject("days") ?: JSONObject()
    val entries=days.keys().asSequence().mapNotNull { key -> runCatching { LocalDate.parse(key).takeIf { it<=today && it.year>=1900 }?.let { it to days.getJSONObject(key) } }.getOrNull() }.sortedBy { it.first }.toList()
    fun ms(day:LocalDate)=if(day>today) 0L else max(0L,days.optJSONObject(day.toString())?.optLong("ms") ?: 0L)
    fun chars(day:LocalDate)=if(day>today) 0L else max(0L,days.optJSONObject(day.toString())?.optLong("chars") ?: 0L)
    val first=entries.firstOrNull { ms(it.first)>0 }?.first
    fun start(period:Int)=when(period) { 0->today.withDayOfMonth(1); 1->today.withDayOfYear(1); else->first ?: today }
    fun total(period:Int)=entries.filter { it.first>=start(period) }.sumOf { max(0L,it.second.optLong("ms")) }
    fun average(period:Int)=total(period)/max(1L,ChronoUnit.DAYS.between(start(period),today)+1)
    fun on(day:LocalDate)=finished.filter { it.date==day }.map { it.book }
    fun target(year:Int)=source.optJSONObject("annualGoals")?.optInt(year.toString(),0)?.coerceIn(0,9999) ?: 0
    fun expected(year:Int,target:Int):Int { val begin=LocalDate.of(year,1,1); val end=begin.plusYears(1); val elapsed=ChronoUnit.DAYS.between(begin,today.plusDays(1)).coerceIn(0,ChronoUnit.DAYS.between(begin,end)); return floor(target*elapsed.toDouble()/ChronoUnit.DAYS.between(begin,end)).toInt() }
    val totalMs=total(2)
    val speed=readingSpeed(entries.sumOf { max(0,it.second.optLong("chars")) },totalMs)
    val dailyGoal=source.optInt("goal",10).coerceIn(1,1440)
    fun met(day:LocalDate)=day<=today && (days.optJSONObject(day.toString())?.optBoolean("met")==true || ms(day)>=dailyGoal*60000L)
    /** The streak as the app keeps it (approved): days in a row with the daily goal met. A missed day is covered by a
     *  freeze if there is one (one is earned for every 7 days in a row, up to two are kept); with none left the streak
     *  breaks, but the whole next day it can be brought back by reading twice the goal. Worked out from the days alone. */
    enum class Mark{MET,FROZEN,REPAIRED,MISSED,NONE}
    class Ledger(val count:Int,val best:Int,val freezes:Int,val toNext:Int,val marks:Map<LocalDate,Mark>,val lost:Int,val repairMs:Long){
        fun mark(d:LocalDate)=marks[d]?:Mark.NONE
        val frozeYesterday get()=marks.entries.maxByOrNull{it.key}?.key?.let{t->marks[t.minusDays(1)]==Mark.FROZEN}==true}
    val ledger:Ledger by lazy {
        val goalMs=dailyGoal*60000L
        val begin=entries.firstOrNull { met(it.first) }?.first
        var run=0;var best=0;var freezes=0;var since=0;var lost=0;var repair=0L
        val marks=HashMap<LocalDate,Mark>()
        if(begin!=null){var d:LocalDate=begin
            while(!d.isAfter(today)){
                when{
                    met(d)->{run++;since++;if(since>=7){since=0;if(freezes<2)freezes++};marks[d]=Mark.MET}
                    d==today||run==0->marks[d]=Mark.NONE
                    freezes>0->{freezes--;marks[d]=Mark.FROZEN}
                    ms(d.plusDays(1))>=goalMs*2&&!d.plusDays(1).isAfter(today)->marks[d]=Mark.REPAIRED
                    else->{if(d.plusDays(1)==today){lost=run;repair=ms(today)};marks[d]=Mark.MISSED;run=0;since=0}}
                best=max(best,run);d=d.plusDays(1)}}
        Ledger(run,best,freezes,if(freezes>=2)0 else 7-since,marks,lost,repair)}
    val streak:Int get()=ledger.count
}

/** Preserve unrelated statistics and daily ledgers while changing an annual target. Called inside a transaction. */
internal fun changeAnnualGoal(data:JSONObject,year:Int,count:Int?) {
    require(year in 1900..9999);require(count==null || count in 1..9999)
    val goals=data.optJSONObject("annualGoals") ?: JSONObject().also { data.put("annualGoals",it) }
    if(count==null)goals.remove(year.toString()) else goals.put(year.toString(),count)
}

/** Attribute active time to each local calendar day, even when a heartbeat crosses midnight/DST. */
internal fun creditReading(data:JSONObject,id:String,end:Long,elapsed:Long,chars:Long,zone:ZoneId=ZoneId.systemDefault()) {
    if(elapsed<=0)return
    val days=data.optJSONObject("days") ?: JSONObject().also { data.put("days",it) }
    var at=end-elapsed;var allocated=0L
    while(at<end) {
        val date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();val next=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();val until=minOf(end,next);val slice=until-at
        val amount=if(until==end) chars-allocated else (chars*slice/elapsed);allocated+=amount
        val day=days.optJSONObject(date.toString()) ?: JSONObject().also { days.put(date.toString(),it) }
        day.put("ms",max(0,day.optLong("ms"))+slice).put("chars",max(0,day.optLong("chars"))+max(0,amount))
        if(day.optLong("ms")>=data.optInt("goal",10).coerceAtLeast(1)*60000L)day.put("met",true)
        val byBook=day.optJSONObject("books") ?: JSONObject().also { day.put("books",it) };byBook.put(id,max(0,byBook.optLong(id))+slice)
        at=until
    }
}
