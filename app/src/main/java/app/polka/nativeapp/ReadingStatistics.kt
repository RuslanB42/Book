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
    val first=entries.firstOrNull { ms(it.first)>0 }?.first
    fun start(period:Int)=when(period) { 0->today.withDayOfMonth(1); 1->today.withDayOfYear(1); else->first ?: today }
    fun total(period:Int)=entries.filter { it.first>=start(period) }.sumOf { max(0L,it.second.optLong("ms")) }
    fun average(period:Int)=total(period)/max(1L,ChronoUnit.DAYS.between(start(period),today)+1)
    fun countMonth(month:YearMonth)=finished.count { it.date?.let(YearMonth::from)==month }
    fun countYear(year:Int)=finished.count { it.date?.year==year }
    val undated=finished.filter { it.date==null }
    fun on(day:LocalDate)=finished.filter { it.date==day }.map { it.book }
    fun target(year:Int)=source.optJSONObject("annualGoals")?.optInt(year.toString(),0)?.coerceIn(0,9999) ?: 0
    fun expected(year:Int,target:Int):Int { val begin=LocalDate.of(year,1,1); val end=begin.plusYears(1); val elapsed=ChronoUnit.DAYS.between(begin,today.plusDays(1)).coerceIn(0,ChronoUnit.DAYS.between(begin,end)); return floor(target*elapsed.toDouble()/ChronoUnit.DAYS.between(begin,end)).toInt() }
    val totalMs=total(2)
    val speed=readingSpeed(entries.sumOf { max(0,it.second.optLong("chars")) },totalMs)
    val dailyGoal=source.optInt("goal",10).coerceIn(1,1440)
    fun met(day:LocalDate)=day<=today && (days.optJSONObject(day.toString())?.optBoolean("met")==true || ms(day)>=dailyGoal*60000L)
    val streak:Int get() { var date=if(met(today)) today else today.minusDays(1); var count=0; while(met(date) && count<100000) { count++;date=date.minusDays(1) };return count }
    data class Author(val name:String,val count:Int,val books:List<JSONObject>)
    val favorite:Author? get() {
        val groups=linkedMapOf<String,Pair<String,MutableList<JSONObject>>>()
        finished.forEach { f -> val names=f.book.optJSONArray("authors")?.strings().orEmpty().map { it.trim().replace(Regex("\\s+")," ") }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
            names.forEach { name -> groups.getOrPut(name.lowercase(Locale.ROOT)) { name to mutableListOf() }.second.add(f.book) }
        }
        return groups.values.map { Author(it.first,it.second.size,it.second) }.sortedWith(compareByDescending<Author> { it.count }.thenBy { it.name.lowercase(Locale.ROOT) }).firstOrNull()
    }
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
