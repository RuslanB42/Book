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
            var chars=0L;var ms=0L;for(d in 0..6){val day=w.plusDays(d.toLong());chars+=model.chars(day);ms+=model.ms(day)}
            readingSpeed(chars,ms).takeIf{it>0}?.let{w to it}}.takeLast(count)
    }
    private fun speedBetween(from:LocalDate,to:LocalDate):Int{var chars=0L;var ms=0L;var d=from;while(!d.isAfter(to)){chars+=model.chars(d);ms+=model.ms(d);d=d.plusDays(1)};return readingSpeed(chars,ms)}
    /** The last 30 days against the 30 before them, in per cent; null while either is unknown. */
    fun trend():Int?{val now=speedBetween(today.minusDays(29),today);val before=speedBetween(today.minusDays(59),today.minusDays(30))
        if(now<=0||before<=0)return null;return ((now-before)*100.0/before).roundToInt()}
    /** Seconds one page (1 800 characters) takes at the reader's speed. */
    fun pageSeconds():Int?{val chars=model.entries.sumOf{max(0L,it.second.optLong("chars"))};val ms=model.totalMs;if(readingSpeed(chars,ms)<=0)return null
        return (PAGE_CHARS*ms/1000.0/chars).roundToInt()}
    /** One sitting on average: reading time over the number of openings, from the per-book records. */
    fun averageSession():Long?{val recs=data.optJSONObject("books")?:return null;var ms=0L;var n=0L
        recs.keys().forEach{k->val r=recs.optJSONObject(k)?:return@forEach;if(r.optInt("sessions")>0&&r.optLong("ms")>0){ms+=r.optLong("ms");n+=r.optInt("sessions")}}
        return if(n>0&&ms>=60000)ms/n else null}
    fun longestSession():Pair<Long,LocalDate?>?{val r=data.optJSONObject("records")?:return null;val ms=r.optLong("session");if(ms<60000)return null
        val at=r.optLong("sessionAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(model.zone).toLocalDate()};return ms to at}
    fun bestDay():Pair<Int,LocalDate>?{val best=model.entries.maxByOrNull{it.second.optLong("chars")}?:return null;val pages=(best.second.optLong("chars")/PAGE_CHARS).toInt();return if(pages>0)pages to best.first else null}
    private fun readMs(b:JSONObject)=data.optJSONObject("books")?.optJSONObject(b.str("id"))?.optLong("ms")?:0L
    /** The finished book that took the fewest days from the start to the end. */
    fun fastestBook():Triple<JSONObject,Int,Long>?{
        return model.finished.mapNotNull{f->val start=f.book.optLong("startedAt");val end=f.book.optLong("completedAt");if(start<=0||end<start)return@mapNotNull null
            val days=(ChronoUnit.DAYS.between(Instant.ofEpochMilli(start).atZone(model.zone).toLocalDate(),Instant.ofEpochMilli(end).atZone(model.zone).toLocalDate())+1).toInt()
            Triple(f.book,days,readMs(f.book))}.minWithOrNull(compareBy<Triple<JSONObject,Int,Long>>{it.second}.thenBy{it.third})}
    /** Books that count for the taste: finished ones and the ones actually read for a while. */
    private val tasted get()=books.distinctBy{it.str("id")}.filter{Library.status(it)=="done"||readMs(it)>=10*60000L}
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
    /** Books by the decade they were written in (needs five books with a year): the decades shown and the favourite one. */
    fun decades():Pair<List<Pair<Int,Int>>,Int>?{
        val years=tasted.map{it.optInt("year")}.filter{it in 1000..today.year};if(years.size<5)return null
        val byDecade=years.groupingBy{it/10*10}.eachCount();val top=byDecade.maxByOrNull{it.value}!!.key
        val last=byDecade.keys.maxOrNull()!!;val first=max(byDecade.keys.minOrNull()!!,last-70)
        val shown=(first..last step 10).map{d->d to if(d==first)byDecade.filterKeys{it<=d}.values.sum() else (byDecade[d]?:0)}
        return shown to top}
}

/** Russian names for the FB2 genre codes (and EPUB subjects as they are). */
internal object Genres {
    private val exact=mapOf("sf_fantasy" to "Фэнтези","fantasy" to "Фэнтези","sf_horror" to "Ужасы","thriller" to "Триллер","prose_classic" to "Классика","prose_rus_classic" to "Классика",
        "prose_su_classics" to "Классика","literature_19" to "Классика","literature_20" to "Классика","prose_contemporary" to "Современная проза","prose_counter" to "Современная проза",
        "prose_history" to "Историческая проза","prose_military" to "Военная проза","poetry" to "Поэзия","dramaturgy" to "Драматургия","nonf_biography" to "Биографии",
        "nonf_publicism" to "Публицистика","nonfiction" to "Нон-фикшн","religion" to "Религия","reference" to "Справочники","children" to "Детская","humor" to "Юмор")
    private val prefix=listOf("sf" to "Фантастика","det" to "Детектив","love" to "Любовный роман","adv" to "Приключения","antique" to "Античная литература","sci" to "Научпоп",
        "humor" to "Юмор","child" to "Детская","psy" to "Психология","comp" to "Компьютеры","home" to "Дом и семья","business" to "Бизнес","nonf" to "Нон-фикшн","prose" to "Проза","romance" to "Любовный роман")
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
    private var avg:Float?=null;private var avgWord="";private var zeroBased=false
    private var shown=FloatArray(0);private var from=FloatArray(0);private var morph=1f;private var reveal=if(live)0f else 1f
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
    /** New data; with the same number of points the line glides from the old values to the new ones. */
    fun set(points:List<Point>,labels:List<Pair<Int,String>>,strong:Int,avg:Float?,avgWord:String,zeroBased:Boolean){
        val glide=live&&shown.size==points.size&&isAttachedToWindow&&reveal>=1f
        from=if(glide)shown.copyOf() else FloatArray(points.size){points[it].value}
        this.points=points;this.labels=labels;this.strong=strong;this.avg=avg;this.avgWord=avgWord;this.zeroBased=zeroBased;hold=-1
        shown=from.copyOf();contentDescription=points.lastOrNull()?.let{"${it.title}, ${it.sub}"}
        if(glide){android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{v->val t=v.animatedValue as Float;for(i in shown.indices)shown[i]=from[i]+(points[i].value-from[i])*t;invalidate()};start()}}
        else{for(i in shown.indices)shown[i]=points[i].value;invalidate()}
    }
    private var revealed=false
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(live&&!revealed){revealed=true;reveal=0f
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=200;duration=900;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{reveal=it.animatedValue as Float;invalidate()};start()}}
        else reveal=1f}
    private fun x(i:Int)=width*(i+.5f)/max(1,points.size)
    private var lo=0f;private var hi=1f
    private fun y(v:Float):Float{val top=12f*dp;val bottom=height-26f*dp;return bottom-(v-lo)/max(.0001f,hi-lo)*(bottom-top)}
    override fun onDraw(c:Canvas){
        if(points.isEmpty()||width==0)return
        val vals=shown.toList()+listOfNotNull(avg)
        lo=if(zeroBased)0f else (vals.minOrNull()?:0f);hi=vals.maxOrNull()?:1f;val pad=(hi-lo).coerceAtLeast(1f)*.12f;if(!zeroBased)lo-=pad;hi+=pad
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
        avg?.let{v->val ay=y(v);hair.color=(a.fg and 0xffffff) or 0x29000000;c.drawLine(0f,ay,w,ay,hair)
            if(avgWord.isNotBlank()){text.color=muted;text.textAlign=Paint.Align.RIGHT
                // the line's height across the word's width, from the points that fall under it
                val tw=text.measureText(avgWord);val x0=w-18f*dp-tw;val under=shown.indices.filter{x(it)>=x0-x(0)}.map{y(shown[it])}+ey
                val clearAbove=under.all{it>ay-2f*dp||it<ay-22f*dp};val clearBelow=under.all{it<ay+2f*dp||it>ay+22f*dp}&&ay+15f*dp<h-24f*dp
                when{clearAbove->c.drawText(avgWord,w-18f*dp,ay-7f*dp,text);clearBelow->c.drawText(avgWord,w-18f*dp,ay+15f*dp,text)}}}
        // labels along the bottom
        text.textAlign=Paint.Align.CENTER
        labels.forEach{(i,s)->if(i !in points.indices)return@forEach;val on=i==strong;text.color=if(on)a.fg else muted;text.typeface=PolkaFonts.sans(if(on)600 else 400)
            val tw=text.measureText(s);val cx=x(i).coerceIn(tw/2+4f*dp,w-tw/2-4f*dp);c.drawText(s,cx,h-9f*dp,text)}
        text.typeface=PolkaFonts.sans(400)
        if(reveal<1f)return
        if(hold<0){val ex=x(shown.size-1);dot.color=(ink and 0xffffff) or 0x38000000;c.drawCircle(ex,ey,10f*dp,dot);dot.color=ink;c.drawCircle(ex,ey,5f*dp,dot);return}
        // held: the guide, the point, and its bubble above it
        val hx=x(hold);val hy=y(shown[hold]);hair.color=(a.fg and 0xffffff) or 0x59000000;c.drawLine(hx,0f,hx,h,hair)
        dot.color=card;c.drawCircle(hx,hy,9f*dp,dot);dot.color=ink;c.drawCircle(hx,hy,6f*dp,dot)
        val p=points[hold];tipTitle.color=a.fg;tipSub.color=if(a.light)0xff6e6e73.toInt() else 0xffb4b4b8.toInt()
        val tw=max(tipTitle.measureText(p.title),tipSub.measureText(p.sub))+22f*dp;val th=48f*dp
        val tx=(hx-tw/2).coerceIn(10f*dp,w-10f*dp-tw);val ty=hy-14f*dp-th
        tip.set(tx,ty,tx+tw,ty+th);tipBg.color=if(a.light)0xfff0f0f2.toInt() else 0xff3a3a3d.toInt();tipBg.setShadowLayer(14f*dp,0f,5f*dp,0x55000000);c.drawRoundRect(tip,12f*dp,12f*dp,tipBg)
        tipTitle.textAlign=Paint.Align.LEFT;c.drawText(p.title,tx+11f*dp,ty+21f*dp,tipTitle);c.drawText(p.sub,tx+11f*dp,ty+39f*dp,tipSub)
    }
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
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=ink}
    private val path=Path()
    override fun onDraw(c:Canvas){val s=min(width,height)/24f;c.save();c.translate(width/2f-12f*s,height/2f-12f*s);c.scale(s,s);p.strokeWidth=2f
        path.rewind()
        when(kind){
            "bolt"->{path.moveTo(13f,3f);path.lineTo(5f,14f);path.lineTo(11f,14f);path.lineTo(10f,21f);path.lineTo(18f,10f);path.lineTo(12f,10f);path.close()}
            "hour"->{path.moveTo(7f,3.5f);path.lineTo(17f,3.5f);path.moveTo(7f,20.5f);path.lineTo(17f,20.5f)
                path.moveTo(8f,3.5f);path.cubicTo(8f,8f,16f,8f,16f,12f);path.cubicTo(16f,16f,8f,16f,8f,20.5f)
                path.moveTo(16f,3.5f);path.cubicTo(16f,8f,8f,8f,8f,12f);path.cubicTo(8f,16f,16f,16f,16f,20.5f)}
            else->{path.addRoundRect(RectF(4f,4f,20f,20f),3f,3f,Path.Direction.CW);path.moveTo(8f,9f);path.lineTo(16f,9f);path.moveTo(8f,12f);path.lineTo(16f,12f);path.moveTo(8f,15f);path.lineTo(13f,15f)}}
        c.drawPath(path,p);c.restore()}
}

/** The streak's week as one strip of seven segments (approved): lit, kept by a freeze, missed; today's fills up. */
internal class WeekStrip(private val a:MainActivity,private val names:List<String>,private val marks:IntArray,private val today:Float,private val live:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11.5f*a.resources.displayMetrics.density;textAlign=Paint.Align.CENTER}
    private val dp=a.resources.displayMetrics.density;private val r=RectF()
    private var grow=if(live)0f else 1f
    init{contentDescription="Неделя: "+names.indices.joinToString(", "){names[it]+" "+when(marks[it]){1->"цель выполнена";2->"спасён заморозкой";4->"сегодня";else->"пропуск"}}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(grow<1f)android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=260;duration=700;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{grow=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){val gap=5f*dp;val n=names.size;val w=(width-gap*(n-1))/n;val h=10f*dp
        val orange=if(a.light)0xfff07c12.toInt() else 0xffff8a1e.toInt();val ice=if(a.light)0xff5aaee6.toInt() else 0xff8fd0fa.toInt();val empty=if(a.light)0xffe6e6ea.toInt() else 0xff3a3a3d.toInt()
        for(i in 0 until n){val x=i*(w+gap);r.set(x,0f,x+w,h)
            p.color=empty;c.drawRoundRect(r,h/2,h/2,p)
            val f=when(marks[i]){1,2->1f;4->today;else->0f}*grow
            if(f>0f){p.color=if(marks[i]==2)ice else orange;c.save();c.clipRect(x,0f,x+w*f,h);c.drawRoundRect(r,h/2,h/2,p);c.restore()}
            val on=i==n-1;t.color=if(on)a.fg else if(a.light)0xff86868b.toInt() else 0xff8a8a8e.toInt();t.typeface=PolkaFonts.sans(if(on)600 else 400)
            c.drawText(names[i],x+w/2,height-4f*dp,t)}}
}

/** The author's mark: initials in the book serif on a soft colour of their own (the same author, the same colour). */
internal class AuthorMark(private val a:MainActivity,name:String):View(a){
    private val letters=name.split(' ','-').filter{it.isNotBlank()}.let{w->((w.firstOrNull()?.take(1)?:"")+(if(w.size>1)w.last().take(1) else "")).uppercase()}
    private val hue=(abs(name.hashCode())%360).toFloat()
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=PolkaFonts.serif(a,true)}
    init{contentDescription=name}
    override fun onDraw(c:Canvas){val s=min(width,height).toFloat();val cx=width/2f;val cy=height/2f
        val top=Color.HSVToColor(floatArrayOf(hue,.45f,if(a.light).78f else .72f));val bottom=Color.HSVToColor(floatArrayOf((hue+20)%360,.6f,if(a.light).6f else .42f))
        p.shader=LinearGradient(cx-s/2,cy-s/2,cx+s/2,cy+s/2,top,bottom,Shader.TileMode.CLAMP);c.drawCircle(cx,cy,s/2,p);p.shader=null
        t.textSize=s*.38f;t.color=Color.HSVToColor(floatArrayOf(hue,.08f,1f));c.drawText(letters,cx,cy-(t.descent()+t.ascent())/2,t)}
}

internal fun pagesWord(n:Int)=if(n%100 in 11..14)"страниц" else when(n%10){1->"страница";2,3,4->"страницы";else->"страниц"}
internal fun booksWord(n:Int)=if(n%100 in 11..14)"книг" else when(n%10){1->"книга";2,3,4->"книги";else->"книг"}
internal fun daysWordRu(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
internal fun dayMonth(d:LocalDate)=d.format(DateTimeFormatter.ofPattern("d MMMM",ruLocale))
internal fun pageTime(sec:Int)=when{sec<60->"$sec с";sec%60==0->"${sec/60} мин";else->"${sec/60} мин ${sec%60} с"}
