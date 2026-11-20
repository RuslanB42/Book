package app.polka.nativeapp

import android.graphics.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

/** One finished book of the year: when it was first opened, when it was finished, and the time spent on it. */
internal data class YearBook(val book:JSONObject,val start:LocalDate,val end:LocalDate,val ms:Long)

internal fun yearBooks(model:ReadingStatistics,data:JSONObject,year:Int):List<YearBook>{
    val records=data.optJSONObject("books")
    return model.finished.filter{it.date?.year==year}.map{f->val id=f.book.str("id");val r=records?.optJSONObject(id)
        val first=f.book.optLong("startedAt").takeIf{it>0}?:r?.optLong("first")?:0L
        val start=if(first>0)Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate() else f.date!!
        YearBook(f.book,if(start.isAfter(f.date!!))f.date else start,f.date,r?.optLong("ms")?:0L)}.sortedBy{it.end}
}

