package app.polka.nativeapp

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * What one reader counts, as honestly as it can be told from the screen:
 * - time runs only while the book is in front of the reader and they are with it: after the last touch it keeps
 *   running for as long as the page could still be being read (twice its usual time, 1–5 minutes), then stops;
 * - text counts as read only when the page was on the screen long enough for it (not faster than 1 500 words a
 *   minute), so flipping through, searching for a place or paging back and forth again adds nothing;
 * - pages are the reader's own, as they were on the screen;
 * - a sitting («заход») is reading without a break of more than five minutes, in any book, and counts from a minute.
 */
internal class ReadingTracker(private val text:Boolean,private val pdf:Boolean=false){
    class Credit(val elapsed:Long,val chars:Long,val textMs:Long,val pages:Double,val moved:Boolean,
                 val from:Int,val to:Int,val sittingMs:Long,val sittingNew:Boolean,val sittingAdd:Long,val sittingDay:LocalDate?)
    private var lastTick=System.currentTimeMillis()
    private var activeAt=lastTick
    private var base=-1
    private var dwell=0L
    /** Characters a millisecond at the reader's usual pace (184 words a minute until their own is known). */
    var pace=USUAL_WPM*CHARS_PER_WORD/60000.0
    /** The book's statistics record as it was at the opening, for how much of it has been read. */
    var record:JSONObject?=null
    /** The record has been read from the statistics (until then nothing is said about how much of the book is read). */
    var seeded=false

    fun touch(now:Long=System.currentTimeMillis()){activeAt=now}
    /** Forget the time since the last tick (the screen was being prepared, an editor was open…). */
    fun skipTime(now:Long=System.currentTimeMillis()){lastTick=now}
    /** A new place to read from. A small step forward (the next chapter right after the last page of this one) keeps
     *  the page being read, so its text still counts once it has been read. */
    fun rebase(pos:Int,pageSize:Int){if(base<0||pos<base||pos-base>max(pageSize,1)*3/2){base=pos;dwell=0}}
    /** How long the current page has been read so far. */
    fun dwellNow(now:Long=System.currentTimeMillis()):Double=(dwell+(min(now,activeAt+60_000L)-lastTick).coerceAtLeast(0L)).toDouble()
    private fun allowance(pageSize:Int):Long=if(text)(pageSize/pace*2).toLong().coerceIn(60_000L,300_000L) else if(pdf)240_000L else 90_000L

    /** Time and text since the last tick. [pos] is the place in the book (characters, or the page of a comic/PDF),
     *  [pageSize] the characters on the current page (text only). */
    fun tick(now:Long,pos:Int,pageSize:Int,paused:Boolean):Credit{
        val allow=allowance(pageSize)
        val until=min(now,activeAt+allow)
        val elapsed=if(paused)0L else (until-lastTick).coerceIn(0L,allow)
        val start=lastTick;lastTick=now
        // the sitting
        var sNew=false;var sAdd=0L
        if(elapsed>0){
            if(Sitting.end>0&&start-Sitting.end>BREAK){Sitting.ms=0;Sitting.counted=false;Sitting.day=null}
            Sitting.ms+=elapsed;Sitting.end=start+elapsed
            if(Sitting.counted)sAdd=elapsed
            else if(Sitting.ms>=SITTING_MIN){Sitting.counted=true;sNew=true;sAdd=Sitting.ms;Sitting.day=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()}
        }
        // the text
        if(base<0){base=pos;dwell=0}
        var chars=0L;var textMs=0L;var pages=0.0;var from=-1;var to=-1;val moved=pos>base
        val delta=pos-base
        when{
            delta<0->{base=pos;dwell=0}
            delta==0->dwell+=elapsed
            else->{val spent=dwell+elapsed
                // one page (or two, across a chapter's end) read in a plausible time; a jump or a fling is not reading
                val honest=if(text)delta<=MAX_JUMP&&delta<=max(pageSize,1)*5/2&&delta<=spent*MAX_PACE else delta<=2&&spent>=delta*(if(pdf)PDF_PAGE_MS else COMIC_PAGE_MS)
                if(honest){from=base;to=pos
                    if(text){chars=delta.toLong();textMs=spent;pages=delta/max(pageSize,1).toDouble()}else if(pdf)pages=delta.toDouble()}
                    // a comic's page is a picture, not a page of a book: it adds to no count of pages
                base=pos;dwell=0}
        }
        return Credit(elapsed,chars,textMs,pages,moved,from,to,if(Sitting.counted)Sitting.ms else 0L,sNew,sAdd,Sitting.day)
    }
    /** How much of the book has really been read, with what this opening has added. */
    fun share():Double=readShare(record)
    /** Keep the in-memory record in step with what was written. */
    fun noteCredit(c:Credit,total:Int){val r=record?:JSONObject().also{record=it};if(total>0)r.put("total",total)
        if(c.to>c.from&&c.from>=0&&total>0){if(!r.has("cov")&&r.optLong("chars")>0)r.put("pre",(r.optLong("chars").toDouble()/total).coerceAtMost(1.0));r.put("cov",Coverage.mark(r.optString("cov").ifEmpty{null},c.from,c.to,total))}
        r.put("chars",r.optLong("chars")+c.chars)}

    /** The sitting is one for the whole app: closing one book and opening another goes on with it. */
    private object Sitting{var ms=0L;var end=0L;var counted=false;var day:LocalDate?=null}
    companion object{
        const val USUAL_WPM=184
        const val CHARS_PER_WORD=6.4
        /** Faster than 1 500 words a minute is not reading. */
        const val MAX_PACE=1500*CHARS_PER_WORD/60000.0
        const val MAX_JUMP=20000
        const val COMIC_PAGE_MS=1200L
        const val PDF_PAGE_MS=3000L
        const val BREAK=5*60_000L
        const val SITTING_MIN=60_000L
    }
}

/** Which parts of a book were really read: 256 bins over its length, as hex. */
internal object Coverage{
    const val BINS=256
    fun mark(hex:String?,from:Int,to:Int,total:Int):String{
        val bits=decode(hex);if(total<=0||to<=from)return encode(bits)
        val a=(from.toLong()*BINS/total).toInt().coerceIn(0,BINS-1);val b=((to.toLong()*BINS-1)/total).toInt().coerceIn(a,BINS-1)
        for(i in a..b)bits[i/8]=(bits[i/8].toInt() or (1 shl (i%8))).toByte()
        return encode(bits)}
    fun share(hex:String?):Double{if(hex.isNullOrEmpty())return 0.0;val bits=decode(hex);return bits.sumOf{Integer.bitCount(it.toInt() and 0xff)}/BINS.toDouble()}
    private fun decode(hex:String?):ByteArray{val out=ByteArray(BINS/8);if(hex==null||hex.length!=BINS/4)return out
        for(i in out.indices)out[i]=hex.substring(i*2,i*2+2).toIntOrNull(16)?.toByte()?:0;return out}
    private fun encode(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 0xff)}
}

/** Writes one tick into the statistics (inside the caller's transaction). */
internal fun recordReading(data:JSONObject,id:String,title:String,total:Int,c:ReadingTracker.Credit,now:Long,pageChars:Int,zone:ZoneId=ZoneId.systemDefault()){
    if(pageChars>0)data.put("pageChars",pageChars)
    creditReading(data,id,now,c.elapsed,c.chars,zone,c.textMs,c.pages)
    // sittings, on the day they began to count
    if(c.sittingAdd>0&&c.sittingDay!=null){val days=data.optJSONObject("days")?:JSONObject().also{data.put("days",it)}
        val key=c.sittingDay.toString();val day=days.optJSONObject(key)?:JSONObject().also{days.put(key,it)}
        if(c.sittingNew)day.put("sn",day.optInt("sn")+1)
        day.put("sms",day.optLong("sms")+c.sittingAdd)}
    if(c.sittingMs>0){val rec=data.optJSONObject("records")?:JSONObject().also{data.put("records",it)}
        if(c.sittingMs>rec.optLong("longest"))rec.put("longest",c.sittingMs).put("longestAt",now)}
    val books=data.optJSONObject("books")?:JSONObject().also{data.put("books",it)}
    val b=books.optJSONObject(id)?:JSONObject().put("first",now).also{books.put(id,it)}
    b.put("ms",b.optLong("ms")+c.elapsed).put("chars",b.optLong("chars")+c.chars).put("pages",b.optDouble("pages",0.0)+c.pages).put("last",now).put("title",title)
    if(total>0)b.put("total",total)
    if(c.to>c.from&&c.from>=0&&total>0){
        // what was read before the coverage was kept stays as a share of its own
        if(!b.has("cov")&&b.optLong("chars")>c.chars)b.put("pre",((b.optLong("chars")-c.chars).toDouble()/total).coerceAtMost(1.0))
        b.put("cov",Coverage.mark(b.optString("cov").ifEmpty{null},c.from,c.to,total))}
}

/** How much of a book was really read, from its statistics record. */
internal fun readShare(rec:JSONObject?):Double{rec?:return 0.0
    // books read before the coverage was kept: the characters counted then (they may hold re-reading, so at most 100 %)
    if(!rec.has("cov"))return (rec.optLong("chars").toDouble()/max(1,rec.optInt("total"))).coerceIn(0.0,1.0)
    return (Coverage.share(rec.optString("cov"))+rec.optDouble("pre",0.0)).coerceIn(0.0,1.0)}

/** Gives the tracker the book's record (how much of it has been read) and the reader's own pace. */
internal fun MainActivity.seedTracker(t:ReadingTracker,id:String){
    var rec:JSONObject?=null;var wpm=0
    work({val data=library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")}?:return@work
        rec=data.optJSONObject("books")?.optJSONObject(id)?.let{JSONObject(it.toString())}
        wpm=ReadingStatistics(data,emptyList()).speed}){
        val mine=t.record;t.record=rec?:JSONObject()
        // what was counted while the record was being read
        mine?.let{m->t.record!!.put("chars",t.record!!.optLong("chars")+m.optLong("chars"))
            if(m.has("cov")&&m.has("total")){var cov=t.record!!.optString("cov").ifEmpty{null};val s=m.optString("cov");val total=m.optInt("total")
                // the bins of the opening carry over as they are
                if(cov==null)cov=s else{val a=cov;cov=(0 until Coverage.BINS/4).joinToString(""){i->"%x".format(a[i].digitToInt(16) or s[i].digitToInt(16))}}
                t.record!!.put("cov",cov).put("total",total)}}
        if(wpm>0)t.pace=wpm.coerceIn(80,900)*ReadingTracker.CHARS_PER_WORD/60000.0
        t.seeded=true}
}
