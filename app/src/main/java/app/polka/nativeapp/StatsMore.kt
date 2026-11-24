package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

private val ruLocale=Locale("ru")
internal const val PAGE_CHARS=1800L

/** Speed over time, how long a page takes, and the reader's own records and tastes, all from what is already kept. */
internal class StatsMore(private val data:JSONObject,private val books:List<JSONObject>,private val model:ReadingStatistics){
    private val today=model.today
    /** Words a minute week by week (Monday to Sunday), the last [count] weeks that had reading. */
    fun weeklySpeed(count:Int=12):List<Pair<LocalDate,Int>>{
        val monday=today.minusDays(today.dayOfWeek.value-1L)
        return (51 downTo 0).map{monday.minusWeeks(it.toLong())}.mapNotNull{w->
            model.speedBetween(w,w.plusDays(6)).takeIf{it>0}?.let{w to it}}.takeLast(count)
    }
    private fun speedBetween(from:LocalDate,to:LocalDate)=model.speedBetween(from,to)
    /** The last 30 days against the 30 before them, in per cent; null while either is unknown. */
    fun trend():Int?{val now=speedBetween(today.minusDays(29),today);val before=speedBetween(today.minusDays(59),today.minusDays(30))
        if(now<=0||before<=0)return null;return ((now-before)*100.0/before).roundToInt()}
    /** Seconds one of the reader's own pages takes at their speed now. */
    fun pageSeconds():Int?{val wpm=model.speed;if(wpm<=0)return null
        return (model.pageChars*60.0/(wpm*ReadingTracker.CHARS_PER_WORD)).roundToInt()}
    /** One sitting on average between two days: reading without a break of over five minutes, from a minute long.
     *  Null while none was counted then (sittings are counted so since the honest count began). */
    fun averageSession(from:LocalDate,to:LocalDate):Long?{var n=0;var ms=0L;var d=from;while(!d.isAfter(to)){val (sn,sms)=model.sittings(d);n+=sn;ms+=sms;d=d.plusDays(1)}
        return if(n>0)ms/n else null}
    fun longestSession():Pair<Long,LocalDate?>?{val r=data.optJSONObject("records")?:return null;val ms=r.optLong("longest");if(ms<60000)return null
        val at=r.optLong("longestAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(model.zone).toLocalDate()};return ms to at}
    /** The day with the most of the reader's own pages. */
    fun bestDay():Pair<Int,LocalDate>?{val best=model.entries.maxByOrNull{model.pages(it.first)}?:return null;val pages=model.pages(best.first).roundToInt();return if(pages>0)pages to best.first else null}
    /**
     * The finished book read the fastest (of books; comics are not among them): of the books really read here (four fifths of the text, at least ten minutes),
     * the days from the start of its reading to its end. A book put aside for over two months starts again where it
     * was taken up again, and the first tenth of its time (a look into it long before) does not stretch the count.
     */
    fun fastestBook():Triple<JSONObject,Int,Long>?{
        val recs=data.optJSONObject("books")
        val byBook=HashMap<String,MutableList<Pair<LocalDate,Long>>>()
        for((d,o) in model.entries){val b=o.optJSONObject("books")?:continue;val it=b.keys();while(it.hasNext()){val k=it.next();val ms=b.optLong(k);if(ms>0)byBook.getOrPut(k){mutableListOf()}.add(d to ms)}}
        return model.finished.mapNotNull{f->val end=f.date?:return@mapNotNull null;val id=f.book.str("id")
            if(readShare(recs?.optJSONObject(id))<.8)return@mapNotNull null
            val days=byBook[id]?.filter{!it.first.isAfter(end)}?.sortedByDescending{it.first}?:return@mapNotNull null
            // the last stretch of reading (no gap over 60 days)
            val stretch=ArrayList<Pair<LocalDate,Long>>();for(p in days){if(stretch.isNotEmpty()&&ChronoUnit.DAYS.between(p.first,stretch.last().first)>60)break;stretch.add(p)}
            val total=stretch.sumOf{it.second};if(total<10*60000L)return@mapNotNull null
            var acc=0L;var start=end;for((d,ms) in stretch){acc+=ms;start=d;if(acc>=total*.9)break}
            Triple(f.book,(ChronoUnit.DAYS.between(start,end)+1).toInt(),total)}
            .minWithOrNull(compareBy<Triple<JSONObject,Int,Long>>{it.second}.thenBy{it.third})}
    private fun readMs(b:JSONObject)=data.optJSONObject("books")?.optJSONObject(b.str("id"))?.optLong("ms")?:0L
    /** Books that count for the taste: finished ones and the ones actually read for a while; comics are not books. */
    private val tasted get()=books.distinctBy{it.str("id")}.filter{!isComic(it)&&(Library.status(it)=="done"||readMs(it)>=10*60000L)}
    class Author(val name:String,val books:List<JSONObject>,val ms:Long)
    fun favouriteAuthor():Author?{
        val by=tasted.groupBy{it.optJSONArray("authors")?.strings()?.firstOrNull()?.trim().orEmpty()}.filterKeys{it.isNotBlank()}
        val best=by.maxByOrNull{(_,list)->list.sumOf{readMs(it)}+list.count{Library.status(it)=="done"}*3_600_000L}?:return null
        if(best.value.size<2&&best.value.sumOf{readMs(it)}<3_600_000L)return null
        return Author(best.key,best.value.sortedByDescending{readMs(it)},best.value.sumOf{readMs(it)})}
    /** The three biggest genres with their shares in per cent (the rest is «Другое»); needs at least three books with a genre. */
    fun genres():List<Pair<String,Int>>{
        val named=tasted.mapNotNull{b->b.optJSONArray("genres")?.strings()?.mapNotNull{Genres.name(it)}?.firstOrNull()}
        if(named.size<3)return emptyList()
        val counts=named.groupingBy{it}.eachCount().entries.sortedByDescending{it.value}
        val top=counts.take(3).map{it.key to (it.value*100.0/named.size).roundToInt()}
        val rest=100-top.sumOf{it.second};return if(rest>0)top+("Другое" to rest) else top}
    /** Decades by the number of books written in them, most first (needs five books with a year). */
    fun decadeCounts():List<Pair<Int,Int>>{
        val years=tasted.map{it.optInt("year")}.filter{it in 1000..today.year};if(years.size<5)return emptyList()
        return years.groupingBy{it/10*10}.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int,Int>>{it.value}.thenByDescending{it.key}).map{it.key to it.value}}
}

/** Russian names for the FB2 genre codes (and EPUB subjects as they are). */
internal object Genres {
    private val exact=mapOf("sf_fantasy" to "Фэнтези","fantasy" to "Фэнтези","sf_horror" to "Ужасы","thriller" to "Триллер","prose_classic" to "Классика","prose_rus_classic" to "Классика",
        "prose_su_classics" to "Классика","literature_19" to "Классика","literature_20" to "Классика","prose_contemporary" to "Современная проза","prose_counter" to "Современная проза",
        "prose_history" to "Историческая проза","prose_military" to "Военная проза","poetry" to "Поэзия","dramaturgy" to "Драматургия","nonf_biography" to "Биографии",
        "nonf_publicism" to "Публицистика","nonfiction" to "Нон-фикшн","religion" to "Религия","reference" to "Справочники","children" to "Детская","humor" to "Юмор")
    private val prefix=listOf("sf" to "Фантастика","det" to "Детектив","love" to "Любовный роман","adv" to "Приключения","antique" to "Античная литература","sci" to "Научпоп",
        "humor" to "Юмор","child" to "Детская","psy" to "Психология","comp" to "Компьютеры","home" to "Дом и семья","business" to "Бизнес","nonf" to "Нон-фикшн","prose" to "Проза","romance" to "Любовный роман")
    /** Every genre to choose from in «Данные книги». */
    val all:List<String> by lazy{(exact.values+prefix.map{it.second}+listOf("Комиксы","Манга")).distinct()}
    fun name(raw:String):String?{val g=raw.trim();if(g.isBlank())return null
        if(g.any{it in 'а'..'я'||it in 'А'..'Я'})return g.replaceFirstChar{it.uppercase()}
        val key=g.lowercase();exact[key]?.let{return it}
        return prefix.firstOrNull{key==it.first||key.startsWith(it.first+"_")}?.second}
}

/** A line chart that is the floor of its card: it runs to both edges, the average is a quiet hairline with its word,
 *  the labels sit inside along the bottom, today's point glows. Holding a finger on it shows the point under it. */
internal class EdgeLine(private val a:MainActivity,private val ink:Int,private val live:Boolean):View(a){
    class Point(val value:Float,val title:String,val sub:String)
    private var points=listOf<Point>();private var labels=listOf<Pair<Int,String>>();private var strong=-1
    private var avg:Float?=null;private var avgWord="";private var zeroBased=false;private var spans=false
    private var shown=FloatArray(0);private var from=FloatArray(0);private var morph=1f;private val reveal=1f
    // what was on the chart before a switch: its labels fade out where they were while the new ones fade in
    private var oldLabels=listOf<Pair<Int,String>>();private var oldStrong=-1;private var oldN=0;private var oldSpans=false
    private var hold=-1
    private val dp=a.resources.displayMetrics.density
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=2.6f*dp;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=ink}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val hair=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=max(1f,dp*.8f)}
    private val dot=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=10.5f*dp;typeface=PolkaFonts.sans(400)}
    private val tipTitle=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=14f*dp;typeface=PolkaFonts.sans(600)}
    private val tipSub=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=12f*dp;typeface=PolkaFonts.sans(400)}
    private val tipBg=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path();private val area=Path();private val tip=RectF()
    private val muted get()=if(a.light)0xff86868b.toInt() else 0xff8a8a8e.toInt()
    private val card get()=if(a.light)Color.WHITE else 0xff242426.toInt()
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    /** New data; with the same number of points the line glides from the old values to the new ones. With [spans] a
     *  label stands for the points from it to the next one (a month over its weeks) and sits in their middle. */
    fun set(points:List<Point>,labels:List<Pair<Int,String>>,strong:Int,avg:Float?,avgWord:String,zeroBased:Boolean,spans:Boolean=false){
        val glide=live&&shown.isNotEmpty()&&isAttachedToWindow
        // the old line exactly as it is drawn (its curves, not straight lines between its points), read at the new
        // points' places, so the new line starts as the very same line and only then turns into the new one
        val old=shown.copyOf();val n=points.size
        from=if(glide)FloatArray(n){j->along(old,(j+.5f)/n)} else FloatArray(n){points[it].value}
        if(glide){oldLabels=this.labels;oldStrong=this.strong;oldN=this.points.size;oldSpans=this.spans}
        val avgFrom=this.avgShown?:avg;val loFrom=lo;val hiFrom=hi
        this.points=points;this.labels=labels;this.strong=strong;this.avg=avg;this.avgWord=avgWord;this.zeroBased=zeroBased;this.spans=spans;hold=-1
        shown=from.copyOf();contentDescription=points.lastOrNull()?.let{"${it.title}, ${it.sub}"}
        morphAnim?.cancel()
        if(glide){avgShown=avgFrom;scaleFrom=floatArrayOf(loFrom,hiFrom);morph=0f
            // the same time and curve as the period pill sliding over, so the pill, the figures and the line arrive together
            morphAnim=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=MotionSpec.SWITCH;interpolator=MotionSpec.move
            addUpdateListener{v->val t=v.animatedValue as Float;morph=t;for(i in shown.indices)shown[i]=from[i]+(points[i].value-from[i])*t
                avgShown=if(avgFrom!=null&&avg!=null)avgFrom+(avg-avgFrom)*t else avg;invalidate()};start()}}
        else{for(i in shown.indices)shown[i]=points[i].value;avgShown=avg;morph=1f;invalidate()}
    }
    private var morphAnim:android.animation.ValueAnimator?=null
    private var avgShown:Float?=null;private var scaleFrom=floatArrayOf(0f,1f)
    private fun x(i:Int)=width*(i+.5f)/max(1,points.size)
    private fun xn(i:Int,n:Int)=width*(i+.5f)/max(1,n)
    /** The value the drawn line has at [f] (0..1 of the width), for [v] spread over the width as the points are: flat
     *  out to the edges, and between two points the same S-curve the path draws (control points at the middle). */
    private fun along(v:FloatArray,f:Float):Float{val m=v.size;if(m==0)return 0f;if(m==1)return v[0]
        val pos=f*m-.5f;if(pos<=0f)return v[0];if(pos>=m-1)return v[m-1]
        val i=pos.toInt().coerceIn(0,m-2);val u=pos-i
        // x along the segment is 1.5t(1−t)+t³ for the path's control points; find t, then y is 3t²−2t³ of the way
        var lo=0f;var hi=1f;repeat(24){val t=(lo+hi)/2;if(1.5f*t*(1-t)+t*t*t<u)lo=t else hi=t}
        val t=(lo+hi)/2;return v[i]+(v[i+1]-v[i])*(3*t*t-2*t*t*t)}
    private var lo=0f;private var hi=1f
    private fun y(v:Float):Float{val top=12f*dp;val bottom=height-26f*dp;return bottom-(v-lo)/max(.0001f,hi-lo)*(bottom-top)}
    override fun onDraw(c:Canvas){
        if(points.isEmpty()||width==0)return
        val vals=points.map{it.value}+listOfNotNull(avg)
        var tlo=if(zeroBased)0f else (vals.minOrNull()?:0f);var thi=vals.maxOrNull()?:1f;val pad=(thi-tlo).coerceAtLeast(1f)*.12f;if(!zeroBased)tlo-=pad;thi+=pad
        if(morph<1f){lo=scaleFrom[0]+(tlo-scaleFrom[0])*morph;hi=scaleFrom[1]+(thi-scaleFrom[1])*morph}else{lo=tlo;hi=thi}
        val w=width.toFloat();val h=height.toFloat()
        // the line: in flat from the left edge, through the points, out flat to the right edge
        path.rewind();path.moveTo(0f,y(shown[0]));path.lineTo(x(0),y(shown[0]))
        for(i in 1 until shown.size){val x0=x(i-1);val y0=y(shown[i-1]);val x1=x(i);val y1=y(shown[i]);val m=(x0+x1)/2;path.cubicTo(m,y0,m,y1,x1,y1)}
        val ey=y(shown.last());path.lineTo(w,ey)
        area.set(path);area.lineTo(w,h);area.lineTo(0f,h);area.close()
        c.save();c.clipRect(0f,-h,w*reveal+1,h*2)
        fill.shader=LinearGradient(0f,12f*dp,0f,h,(ink and 0xffffff) or 0x4d000000,ink and 0xffffff,Shader.TileMode.CLAMP);c.drawPath(area,fill)
        c.drawPath(path,line);c.restore()
        // the average: a hairline and its word on the right, on the side away from the line
        avgShown?.let{v->val ay=y(v);hair.color=(a.fg and 0xffffff) or 0x29000000;c.drawLine(0f,ay,w,ay,hair)
            if(avgWord.isNotBlank()){text.color=muted;text.textAlign=Paint.Align.RIGHT
                // the line's height across the word's width, from the points that fall under it
                val tw=text.measureText(avgWord);val x0=w-18f*dp-tw;val under=shown.indices.filter{x(it)>=x0-x(0)}.map{y(shown[it])}+ey
                val clearAbove=under.all{it>ay-2f*dp||it<ay-22f*dp};val clearBelow=under.all{it<ay+2f*dp||it>ay+22f*dp}&&ay+15f*dp<h-24f*dp
                when{clearAbove->c.drawText(avgWord,w-18f*dp,ay-7f*dp,text);clearBelow->c.drawText(avgWord,w-18f*dp,ay+15f*dp,text)}}}
        // labels along the bottom; on a switch the old ones fade out where they were as the new ones fade in
        if(morph<1f&&oldN>0)drawLabels(c,oldLabels,oldStrong,oldN,oldSpans,(1f-morph/.45f).coerceIn(0f,1f))
        drawLabels(c,labels,strong,points.size,spans,if(morph<1f&&oldN>0)((morph-.35f)/.65f).coerceIn(0f,1f) else 1f)
        if(reveal<1f)return
        // today's point travels from where it was to where it is now, along the line
        if(hold<0){val ex=if(morph<1f&&oldN>0)xn(oldN-1,oldN)+(x(shown.size-1)-xn(oldN-1,oldN))*morph else x(shown.size-1);val ey=y(along(shown,ex/w));dot.color=(ink and 0xffffff) or 0x38000000;c.drawCircle(ex,ey,10f*dp,dot);dot.color=ink;c.drawCircle(ex,ey,5f*dp,dot);return}
        // held: the guide, the point, and its bubble above it
        val hx=x(hold);val hy=y(shown[hold]);hair.color=(a.fg and 0xffffff) or 0x59000000;c.drawLine(hx,0f,hx,h,hair)
        dot.color=card;c.drawCircle(hx,hy,9f*dp,dot);dot.color=ink;c.drawCircle(hx,hy,6f*dp,dot)
        val p=points[hold];tipTitle.color=a.fg;tipSub.color=if(a.light)0xff6e6e73.toInt() else 0xffb4b4b8.toInt()
        val tw=max(tipTitle.measureText(p.title),tipSub.measureText(p.sub))+22f*dp;val th=48f*dp
        val tx=(hx-tw/2).coerceIn(10f*dp,w-10f*dp-tw);val ty=hy-14f*dp-th
        tip.set(tx,ty,tx+tw,ty+th);tipBg.color=if(a.light)0xfff0f0f2.toInt() else 0xff3a3a3d.toInt();tipBg.setShadowLayer(18f*dp,0f,5f*dp,if(a.light)0x26000000 else 0x40000000);c.drawRoundRect(tip,12f*dp,12f*dp,tipBg)
        tipTitle.textAlign=Paint.Align.LEFT;c.drawText(p.title,tx+11f*dp,ty+21f*dp,tipTitle);c.drawText(p.sub,tx+11f*dp,ty+39f*dp,tipSub)
    }
    private fun drawLabels(c:Canvas,labels:List<Pair<Int,String>>,strong:Int,n:Int,spans:Boolean,alpha:Float){if(alpha<=0f)return
        val w=width.toFloat();val h=height.toFloat();text.textAlign=Paint.Align.CENTER
        // a span's label keeps inside the card's own padding, so both edges are even
        val edge=(if(spans)18f else 4f)*dp
        labels.forEachIndexed{k,(i,s)->if(i<0||i>=n)return@forEachIndexed;val on=i==strong;text.color=if(on)a.fg else muted;text.alpha=(text.alpha*alpha).toInt();text.typeface=PolkaFonts.sans(if(on)600 else 400)
            val at=if(spans){val last=(labels.getOrNull(k+1)?.first?:n).coerceAtMost(n)-1;(xn(i,n)+xn(maxOf(i,last),n))/2} else xn(i,n)
            val tw=text.measureText(s);val cx=at.coerceIn(tw/2+edge,w-tw/2-edge);c.drawText(s,cx,h-9f*dp,text)}
        text.typeface=PolkaFonts.sans(400)}
    // a hold (not a tap) picks the point under the finger; moving the finger moves along the line; letting go clears it
    private var downX=0f;private var downY=0f
    private val slop=ViewConfiguration.get(a).scaledTouchSlop
    private val start=Runnable{if(points.isEmpty())return@Runnable;parent?.requestDisallowInterceptTouchEvent(true);pick(downX,true)}
    private fun pick(px:Float,first:Boolean=false){val i=points.indices.minByOrNull{abs(x(it)-px)}?:return;if(i!=hold){hold=i;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);announceForAccessibility("${points[i].title}, ${points[i].sub}");invalidate()}}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;removeCallbacks(start);postDelayed(start,220);return true}
            MotionEvent.ACTION_MOVE->{if(hold<0){if(abs(e.x-downX)>slop||abs(e.y-downY)>slop)removeCallbacks(start);return true};pick(e.x);return true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{removeCallbacks(start);if(hold>=0){hold=-1;parent?.requestDisallowInterceptTouchEvent(false);invalidate()};return true}}
        return super.onTouchEvent(e)}
    override fun onDetachedFromWindow(){removeCallbacks(start);super.onDetachedFromWindow()}
}

/** Line icons for «Рекорды»: a bolt (fastest), an hourglass (longest sitting), lines of a page (most pages in a day). */
internal class RecordIcon(private val a:MainActivity,private val kind:String,private val ink:Int):View(a){
    override fun onDraw(c:Canvas){val s=min(width,height)/24f;c.save();c.translate(width/2f-12f*s,height/2f-12f*s);c.scale(s,s)
        IosIcons.draw(c,when(kind){"bolt"->"bolt";"hour"->"hourglass";else->"sheet"},ink);c.restore()}
}

/** The streak's week as one strip of seven segments (approved): lit, kept by a freeze, missed; today's fills up. */
internal class WeekStrip(private val a:MainActivity,private val names:List<String>,private val marks:IntArray,private val today:Float,private val live:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11.5f*a.resources.displayMetrics.density;textAlign=Paint.Align.CENTER}
    private val dp=a.resources.displayMetrics.density;private val r=RectF()
    private val grow=1f
    init{contentDescription="Неделя: "+names.indices.joinToString(", "){names[it]+" "+when(marks[it]){1->"цель выполнена";2->"спасён заморозкой";4->"сегодня";else->"пропуск"}}}
    override fun onDraw(c:Canvas){val gap=5f*dp;val n=names.size;val w=(width-gap*(n-1))/n;val h=10f*dp
        val orange=if(a.light)0xfff07c12.toInt() else 0xffff8a1e.toInt();val ice=if(a.light)0xff5aaee6.toInt() else 0xff8fd0fa.toInt();val empty=if(a.light)0xffe6e6ea.toInt() else 0xff3a3a3d.toInt()
        for(i in 0 until n){val x=i*(w+gap);r.set(x,0f,x+w,h)
            p.color=empty;c.drawRoundRect(r,h/2,h/2,p)
            val f=when(marks[i]){1,2->1f;4->today;else->0f}*grow
            if(f>0f){p.color=if(marks[i]==2)ice else orange;c.save();c.clipRect(x,0f,x+w*f,h);c.drawRoundRect(r,h/2,h/2,p);c.restore()}
            val on=i==n-1;t.color=if(on)a.fg else if(a.light)0xff86868b.toInt() else 0xff8a8a8e.toInt();t.typeface=PolkaFonts.sans(if(on)600 else 400)
            c.drawText(names[i],x+w/2,height-4f*dp,t)}}
}

internal fun pagesWord(n:Int)=if(n%100 in 11..14)"страниц" else when(n%10){1->"страница";2,3,4->"страницы";else->"страниц"}
internal fun booksWord(n:Int)=if(n%100 in 11..14)"книг" else when(n%10){1->"книга";2,3,4->"книги";else->"книг"}
internal fun daysWordRu(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
internal fun dayMonth(d:LocalDate)=d.format(DateTimeFormatter.ofPattern("d MMMM",ruLocale))
internal fun pageTime(sec:Int)=when{sec<60->"$sec с";sec%60==0->"${sec/60} мин";else->"${sec/60} мин ${sec%60} с"}
