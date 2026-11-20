package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

internal val statBlue=0xff79beff.toInt()
internal val statMint=0xff65e5b5.toInt()
internal val statAmber=0xffffbc60.toInt()
internal val statViolet=0xffc7a3ff.toInt()
internal val statCyan=0xff60e0ee.toInt()
private val weekNames=listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")
private val ru=Locale("ru")

/**
 * Only the requested reading metrics. Every card follows one template: an icon tile and a quiet title,
 * a big numeral with a grey caption, a thin scale underneath, and a soft shadow that lets the card float.
 */
internal class StatisticsScreen(private val a:MainActivity,private val data:JSONObject,range:Int,private val animate:Boolean) {
    private val model=ReadingStatistics(data,a.books)
    private val more=StatsMore(data,a.books,model)
    private val today=model.today
    private val content=a.column().apply{clipChildren=false;clipToPadding=false}
    /** The page scrolls under the status bar and fades into it (no hard cut at the top edge). */
    private var scrimTop=0
    private val scrimPaint=Paint()
    private val scroll=object:ScrollView(a){
        override fun draw(c:Canvas){super.draw(c);if(scrimTop<=0)return;val fade=a.d(22).toFloat();val h=scrimTop+fade
            scrimPaint.shader=LinearGradient(0f,scrimTop.toFloat(),0f,h,a.bg,a.bg and 0x00ffffff,Shader.TileMode.CLAMP)
            c.save();c.translate(scrollX.toFloat(),scrollY.toFloat());scrimPaint.color=Color.WHITE
            c.drawRect(0f,scrimTop.toFloat(),width.toFloat(),h,scrimPaint);scrimPaint.shader=null;scrimPaint.color=a.bg;c.drawRect(0f,0f,width.toFloat(),scrimTop.toFloat(),scrimPaint);c.restore()}}
    private var period=range.coerceIn(0,2)
    private var goalYear=(a.statisticsGoalYear ?: today.year).coerceAtMost(today.year+1)
    private val compact=a.resources.configuration.screenWidthDp<340 || a.resources.configuration.fontScale>1.15f
    private val light get()=a.light
    private val cardColor get()=if(light)Color.WHITE else 0xff242426.toInt()
    private val titleInk get()=if(light)0xff5d5d62.toInt() else 0xffb4b4b8.toInt()
    private val captionInk get()=if(light)0xff86868b.toInt() else 0xff8a8a8e.toInt()
    private val quiet get()=if(light)0xffeeeeec.toInt() else 0xff2f2f31.toInt()
    private val thumb get()=if(light)Color.WHITE else 0xff48484c.toInt()
    private val strong get()=if(light)0xff161617.toInt() else 0xfff3f2ef.toInt()
    private val onStrong get()=if(light)Color.WHITE else 0xff161617.toInt()
    private val blue get()=if(light)0xff3f6fe0.toInt() else 0xff6a94f8.toInt()
    private val mint get()=if(light)0xff1f9d73.toInt() else 0xff4fd6a2.toInt()
    private val violet get()=if(light)0xff7a56d8.toInt() else 0xffa98bf7.toInt()
    private val rose get()=if(light)0xffd0457f.toInt() else 0xfff28bb5.toInt()
    private val amber get()=a.accentFill
    private fun label(s:String,size:Float=14f,color:Int=captionInk,bold:Boolean=false)=a.text(s,size,color,bold)
    private fun space(box:LinearLayout,n:Int=12)=a.space(box,n)
    private fun pill(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=a.d(radius).toFloat()}
    /** White (dark: graphite) card with a hairline edge and a soft, wide shadow underneath so it hovers over the page. */
    private fun surface(glow:Int?=null,fx:Float=.5f,fy:Float=.5f,reach:Float=.8f)=a.column().apply {
        tag="statistics-card"
        val radius=a.d(24).toFloat()
        // flat grey card like everywhere else in the app: the colour lives only in the data, not in the card
        background=WidgetCardDrawable(a,radius,null,fx,fy,reach)
        setPadding(a.d(18),a.d(18),a.d(18),a.d(18));clipChildren=false;clipToPadding=false
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,radius)}}
    }
    private fun tile(kind:String,color:Int):View=FrameLayout(a).apply{
        background=pill((color and 0xffffff) or ((if(light)0x26 else 0x33) shl 24),11);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(IconView(a,kind,color),FrameLayout.LayoutParams(a.d(17),a.d(17),Gravity.CENTER))
    }
    private fun header(box:LinearLayout,title:String,kind:String,color:Int,stacked:Boolean=false) {
        if(stacked){box.addView(tile(kind,color),LinearLayout.LayoutParams(a.d(34),a.d(34)));space(box,8);box.addView(label(title,14.5f,titleInk));return}
        val row=a.line();row.addView(tile(kind,color),LinearLayout.LayoutParams(a.d(34),a.d(34)).apply{rightMargin=a.d(12)})
        row.addView(label(title,15f,titleInk),LinearLayout.LayoutParams(0,-2,1f));box.addView(row)
    }
    private val motionOn get()=animate&&a.prefs.str("motion")!="reduced"
    /** Ambient and touch motion (sparks, pulses, chart glides, menus) — alive even when the screen is rebuilt in place
     *  without its entrance (after a theme or goal change in the settings); only the entrance follows [animate]. */
    private val live get()=a.prefs.str("motion")!="reduced"
    /** Big numeral that counts up once when its card first appears (plain text when motion is reduced). */
    private fun count(end:Int,size:Float,play:Boolean=true,format:(Int)->String={it.toString()}):View=Odometer(a,format(end),size,a.fg,true).apply{
        if(play&&motionOn&&end>0)addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{var done=false
            override fun onViewAttachedToWindow(v:View){if(done)return;done=true;set(format(end),format(0),rollAll=false,delay=200)}
            override fun onViewDetachedFromWindow(v:View){}})
    }
    /** Small green «what changed» chip that pops in after the numbers have rolled. */
    private fun delta(s:String):View=label(s,12f,mint,true).apply{
        background=pill((mint and 0xffffff) or ((if(light)0x1c else 0x24) shl 24),12);setPadding(a.d(9),a.d(4),a.d(9),a.d(4))
        if(motionOn){alpha=0f;addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{var done=false
            override fun onViewAttachedToWindow(v:View){if(done)return;done=true;translationY=a.d(6).toFloat();scaleX=.9f;scaleY=.9f;animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(820).setDuration(460).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()}
            override fun onViewDetachedFromWindow(v:View){animate().cancel();alpha=1f;translationY=0f;scaleX=1f;scaleY=1f}})}
    }
    private val entrance=mutableListOf<View>()
    private fun action(s:String,primary:Boolean=false,click:()->Unit)=a.button(s,primary,click).apply {
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33808080),pill(if(primary)strong else quiet,26),null)
        setTextColor(if(primary)onStrong else a.fg)
    }
    private fun segment(labels:List<String>,selected:Int,onSelect:(Int)->Unit):LinearLayout {
        val box=SlidingSegment(a,selected,labels.size,quiet,thumb,24)
        fun update(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply {isSelected=i==index;setTextColor(if(isSelected)a.fg else captionInk)}}
        labels.forEachIndexed{i,s->box.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(34);setOnClickListener{box.choose(i);update(i);onSelect(i)}},LinearLayout.LayoutParams(0,-2,1f))}
        update(selected);return box
    }
    private fun add(card:View){entrance.add(card);if(deferred){pending.add(card);return};content.addView(card,LinearLayout.LayoutParams(-1,-2));space(content,12)}
    /** Small caps label at the top of a widget («СЕРИЯ», «СКОРОСТЬ»…). */
    private fun kicker(s:String)=label(s.uppercase(),11.5f,if(light)0xff6e6e73.toInt() else 0xffa9a9ae.toInt(),true).apply{letterSpacing=.06f}
    /** «Добрый вечер» and today's date between Back and Settings. */
    private fun greeting():View{val h=java.time.LocalTime.now().hour
        val hello=when(h){in 5..11->"Доброе утро";in 12..16->"Добрый день";in 17..22->"Добрый вечер";else->"Доброй ночи"}
        val date=today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE",ru))
        return a.column().apply{setPadding(a.d(12),0,a.d(8),0);addView(label(date,12.5f));addView(a.text(hello,26f,a.fg,true,serif=true).apply{maxLines=1})}}
    private fun daysWord(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    fun show() {
        val extra=max(0,(a.resources.configuration.screenWidthDp-640)/2)
        scroll.clipToPadding=false;a.followInsets(content) { i->scroll.setPadding(0,i.top,0,0);content.setPadding(a.d(16+extra)+i.left,a.d(8),a.d(16+extra)+i.right,a.d(40)+i.bottom) }
        scroll.setBackgroundColor(a.bg);scroll.elevation=a.d(20).toFloat();scroll.clipChildren=false;scroll.addView(content);a.root.addView(scroll,FrameLayout.LayoutParams(-1,-1));a.profileOverlay=scroll
        val head=a.line();head.addView(a.icon("back","Назад"){a.dismissProfile()},LinearLayout.LayoutParams(a.d(44),a.d(44)));head.addView(greeting(),LinearLayout.LayoutParams(0,-2,1f));head.addView(a.icon("settings","Настройки"){a.settings()},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(head)
        space(content,8)
        streak();speed();readingTime();books();taste();records();tiers()
        if(!motionOn)return
        // a simple, calm entrance: the whole page fades in, everything on it already in place
        // starts after the page's first (heavier) frame has been drawn, so no part of it is skipped; the still page
        // is cached in a layer while it fades and rises, which keeps every frame light
        scroll.alpha=0f;scroll.translationY=a.d(18).toFloat();scroll.scaleX=.985f;scroll.scaleY=.985f
        val ch=android.view.Choreographer.getInstance()
        ch.postFrameCallback{ch.postFrameCallback{scroll.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).withLayer().start()}}
    }
    private var deferred=false
    private val shadow=HashMap<View,Float>()
    private val pending=mutableListOf<View>()
    /** One wave from top to bottom: each card glides up and settles with a long, soft deceleration. */
    private fun reveal(){val ease=PathInterpolator(.05f,.7f,.1f,1f)
        // the fade is short (a card below full opacity is drawn through an offscreen buffer every frame); the glide
        // and the slight growth go on with a long, soft deceleration. No cached layers: the cards' content is moving.
        entrance.forEachIndexed{i,v->v.pivotX=v.width/2f;val y0=v.translationY;val s0=v.scaleX
            ValueAnimator.ofFloat(0f,1f).apply{startDelay=i*90L;duration=760;interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener{val t=it.animatedValue as Float;val e=ease.getInterpolation(t)
                    v.alpha=(t/.32f).coerceIn(0f,1f);v.translationY=y0*(1-e);val sc=s0+(1-s0)*e;v.scaleX=sc;v.scaleY=sc}
                // settled: its shadow fades back in
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){val el=shadow[v]?:return
                    ValueAnimator.ofFloat(0f,el).apply{duration=420;addUpdateListener{v.elevation=it.animatedValue as Float};start()}}});start()}}}
    // ───────────────────────── glass cards ─────────────────────────
    /** A big light numeral (counts up once when it appears); trailing words are set smaller and grey. */
    /** A count starts while its own card rises, not before (cards rise one after another). */
    private val countDelay get()=entrance.size*90L
    /** A counting numeral drawn straight onto the canvas: no text layout is rebuilt while it counts, so the count costs
     *  nothing but a redraw. The words after it are set smaller and grey, like [big]. */
    private fun bigCount(n:Int,unit:String,size:Float,unitScale:Float):View=CountNumber(a,n,unit,size,unitScale,captionInk,0,false)
    private fun big(text:String,size:Float,countTo:Int?=null,unitScale:Float=.42f,firstOnly:Boolean=false):TextView{
        val tv=a.text("",size,a.fg).apply{typeface=PolkaFonts.display();letterSpacing=-.01f;includeFontPadding=false;fontFeatureSettings="lnum, tnum"}
        fun styled(t:String):CharSequence{val sp=android.text.SpannableString(t);var i=0
            if(firstOnly){val k=t.indexOfFirst{!it.isDigit()};if(k>0){sp.setSpan(SansSpan(),k,t.length,0);sp.setSpan(android.text.style.RelativeSizeSpan(unitScale),k,t.length,0);sp.setSpan(android.text.style.ForegroundColorSpan(captionInk),k,t.length,0)};return sp}
            while(i<t.length){if(!t[i].isDigit()&&t[i]!=' '){var j=i;while(j<t.length&&!t[j].isDigit())j++
                sp.setSpan(SansSpan(),i,j,0);sp.setSpan(android.text.style.RelativeSizeSpan(unitScale),i,j,0);sp.setSpan(android.text.style.ForegroundColorSpan(captionInk),i,j,0);i=j}else i++};return sp}
        tv.text=styled(text)
        val myDelay=countDelay
        if(countTo!=null&&countTo>0&&motionOn){val fmt=text.replaceFirst(countTo.toString(),"%d")
            tv.minWidth=ceil(android.text.Layout.getDesiredWidth(styled(text),tv.paint)).toInt();tv.text=styled(String.format(fmt,0))
            tv.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{var done=false
                override fun onViewAttachedToWindow(v:View){if(done)return;done=true;android.animation.ValueAnimator.ofInt(0,countTo).apply{duration=720;startDelay=myDelay;interpolator=PathInterpolator(.3f,.3f,.6f,.8f);
                    // fixed width while counting: a changing wrap-content text re-laid out the whole page on every frame
                    addListener(object:android.animation.AnimatorListenerAdapter(){
                        override fun onAnimationStart(x:android.animation.Animator){tv.layoutParams?.let{lp->lp.width=max(tv.width,tv.minWidth+a.d(2));tv.layoutParams=lp}}});addUpdateListener{tv.text=styled(String.format(fmt,it.animatedValue as Int))};start()}}
                override fun onViewDetachedFromWindow(v:View){}})}
        return tv}
    /** Small glass chip (year, period). */
    private fun chip(s:String,click:()->Unit)=label(s,12f,if(light)0xff3a3a3d.toInt() else 0xffdddddd.toInt()).apply{
        background=GradientDrawable().apply{setColor(if(light)0x0a000000 else 0x12ffffff);cornerRadius=a.d(12).toFloat();setStroke(a.d(1),if(light)0x14000000 else 0x1affffff)}
        setPadding(a.d(10),a.d(5),a.d(10),a.d(5));isClickable=true;setOnClickListener{click()};a.press(this)}
    private fun bestStreak():Int=max(model.ledger.best,model.streak)

    private fun minutesAcc(n:Int)=if(n%100 in 11..14)"минут" else when(n%10){1->"минуту";2,3,4->"минуты";else->"минут"}

    /** Streak: the flame, the days in a row and the record, the freezes in the corner; the week as one strip of seven
     *  segments (today's fills up); one plain line at the foot, with «Читать» while today still matters. */
    private fun streak(){
        val box=surface(0xfff9601f.toInt(),.14f,.62f,.75f).apply{clipToOutline=true}
        val ledger=model.ledger;val n=ledger.count;val best=bestStreak();val met=model.met(today);val goal=model.dailyGoal
        val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(StreakFlameView(a,n>0,live),LinearLayout.LayoutParams(a.d(76),a.d(96)).apply{leftMargin=-a.d(6);topMargin=-a.d(6);bottomMargin=-a.d(6);rightMargin=a.d(10)})
        val col=a.column()
        col.addView(kicker("Серия"));space(col,4)
        col.addView(bigCount(n," ${daysWord(n)} подряд",42f,.38f))
        space(col,4);col.addView(label(when{ledger.lost>0->"было ${ledger.lost}, рекорд $best ${daysWord(best)}";n>0||best>0->"рекорд $best ${daysWord(best)}";else->"серии пока нет"},12.5f))
        top.addView(col,LinearLayout.LayoutParams(0,-2,1f))
        // the freezes in the corner (approved: no arrow; the card itself opens the month)
        val ice=a.freezeRow(ledger,24).apply{isClickable=true;contentDescription="Заморозки: ${ledger.freezes} из 2";setOnClickListener{a.freezeSheet(ledger)};a.press(this);setPadding(a.d(4),a.d(4),0,a.d(4))}
        top.addView(ice,LinearLayout.LayoutParams(-2,-2).apply{gravity=Gravity.TOP})
        box.addView(top);space(box,16)
        // approved «неделя полосой»: seven segments, today's fills up with the minutes read
        val dates=(6 downTo 0).map{today.minusDays(it.toLong())}
        val todayFraction=(model.ms(today).toFloat()/(goal*60000L)).coerceIn(0f,1f)
        val marks=IntArray(7){i->val d=dates[i];if(d==today)(if(met)1 else 4) else when(ledger.mark(d)){ReadingStatistics.Mark.MET,ReadingStatistics.Mark.REPAIRED->1;ReadingStatistics.Mark.FROZEN->2;else->if(model.met(d))1 else 3}}
        box.addView(WeekStrip(a,dates.map{weekNames[it.dayOfWeek.value-1].lowercase()},marks,todayFraction,live),LinearLayout.LayoutParams(-1,a.d(34)))
        space(box,14)
        // one line at the foot, plain text, with «Читать» whenever reading today still matters
        val minutes=(model.ms(today)/60000L).toInt();val left=ceil(max(0L,goal*60000L-model.ms(today))/60000.0).toInt()
        val evening=java.time.LocalTime.now().hour>=18
        val (title,sub)=when{
            ledger.lost>0->{val want=goal*2;val got=min(want,(ledger.repairMs/60000L).toInt());"Вернуть серию ${ledger.lost} ${daysWord(ledger.lost).substringAfter(' ')}" to "сегодня двойная норма, $got из $want мин"}
            met->"Цель на сегодня выполнена" to null
            ledger.frozeYesterday->"Вчера серию сохранила заморозка" to "сегодня ещё $left ${minutesAcc(left)}"
            evening&&n>0->"Ещё $left ${minutesAcc(left)} до цели" to (if(ledger.freezes>0)"если не успеете, выручит заморозка" else "иначе серия прервётся")
            else->"Сегодня ${min(minutes,goal)} из $goal мин" to null}
        val foot=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(36)}
        val words=a.column();words.addView(a.text(title,14f,a.fg,sub!=null||met));sub?.let{words.addView(label(it,12.5f).apply{setPadding(0,a.d(2),0,0)})}
        foot.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        val reading=a.books.filter{Library.status(it)=="reading"&&!it.optBoolean("noFile")}.maxByOrNull{it.optLong("openedAt")}
        if(!met&&reading!=null)foot.addView(a.text("Читать",13.5f,a.bg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(16),0,a.d(16),0);background=a.shape(a.fg,18);isClickable=true;setOnClickListener{a.openBook(reading)};a.press(this)},LinearLayout.LayoutParams(-2,a.d(36)).apply{marginStart=a.d(10)})
        box.addView(foot,LinearLayout.LayoutParams(-1,-2))
        val month=StreakMonth(a,model,today,live)
        box.addView(month,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18);leftMargin=-a.d(6);rightMargin=-a.d(6);bottomMargin=-a.d(6)})
        box.contentDescription="Серия чтения: $n ${daysWord(n)}, рекорд $best, заморозок ${ledger.freezes}"
        box.isClickable=true;box.setOnClickListener{month.toggle()};a.press(box)
        add(box)
    }

    /** A chart that is the floor of its card: it runs to the card's edges at the sides and the bottom. */
    private fun floorChart(box:LinearLayout,chart:View,top:Int=12){box.clipToOutline=true
        box.addView(chart,LinearLayout.LayoutParams(-1,a.d(124)).apply{topMargin=a.d(top);leftMargin=-a.d(18);rightMargin=-a.d(18);bottomMargin=-a.d(18)})}
    private fun headRow(box:LinearLayout,title:String,right:View?){val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL};row.addView(kicker(title),LinearLayout.LayoutParams(0,-2,1f));right?.let{row.addView(it)};box.addView(row)}
    private fun shortDate(d:LocalDate)=if(d==today)"сегодня" else d.format(DateTimeFormatter.ofPattern("EE, d MMMM",ru)).replace(".","")

    /** Speed (approved «Линия»): the number, a page's time in plain words, and the weeks as the card's floor; the
     *  month's change sits small in the corner; holding the line shows a week. */
    private fun speed(){
        val box=surface()
        val trend=more.trend()?.takeIf{abs(it)>=1}
        headRow(box,"Скорость",trend?.let{t->label("${if(t>0)"+" else "−"}${abs(t)}% за месяц",11.5f,if(t>0)mint else rose,true)})
        space(box,10)
        box.addView(if(model.speed>0)bigCount(model.speed," слов в минуту",40f,.4f) else big("—",40f))
        space(box,4)
        box.addView(label(more.pageSeconds()?.let{"страница в среднем за ${pageTime(it)}"}?:"появится после пары минут чтения",12.5f))
        val weeks=more.weeklySpeed()
        if(weeks.size>=2){val chart=EdgeLine(a,blue,live)
            val fmt=DateTimeFormatter.ofPattern("d MMM",ru)
            val pts=weeks.mapIndexed{i,(w,v)->EdgeLine.Point(v.toFloat(),"$v слов в минуту",if(i==weeks.size-1&&!w.plusDays(6).isBefore(today))"эта неделя" else "${w.format(fmt).replace(".","")} – ${w.plusDays(6).format(fmt).replace(".","")}")}
            // a month's name under the first week that starts in it
            val labels=weeks.mapIndexedNotNull{i,(w,_)->if(i==0||w.monthValue!=weeks[i-1].first.monthValue)i to w.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE,ru).trimEnd('.').take(3) else null}
            chart.set(pts,labels,-1,200f,"средний читатель",false);floorChart(box,chart)}
        box.contentDescription=if(model.speed>0)"Скорость чтения: ${model.speed} слов в минуту" else "Скорость чтения появится после пары минут чтения"
        add(box)
    }

    /** Reading time: the total, the averages in one line (minutes and pages a day, a usual sitting), and the same floor
     *  line as speed; Week / Month / Year; holding the line shows a day (a month in the year). */
    private fun readingTime(){
        val box=surface()
        val names=listOf("Неделя","Месяц","Год")
        lateinit var bind:(Boolean)->Unit
        headRow(box,"Время чтения",PeriodPills(a,names,period){i->period=i;a.profileRange=i;bind(false)})
        space(box,10)
        val left=a.column();box.addView(left)
        val chart=EdgeLine(a,violet,live)
        fun minutes(ms:Long)=(ms/60000.0).toFloat()
        val session=more.averageSession()
        bind={first->
            val pts:List<EdgeLine.Point>;val labels:List<Pair<Int,String>>;val strong:Int;val total:Long;val avg:Float;val perPages:Int;val per:String
            when(period){
                0->{val days=(6 downTo 0).map{today.minusDays(it.toLong())}
                    pts=days.map{d->val pg=(model.chars(d)/PAGE_CHARS).toInt();EdgeLine.Point(minutes(model.ms(d)),duration(model.ms(d))+if(pg>0)", $pg стр." else "",shortDate(d))}
                    labels=days.mapIndexed{i,d->i to weekNames[d.dayOfWeek.value-1].lowercase()};strong=6
                    total=days.sumOf{model.ms(it)};avg=minutes(total)/7;perPages=(days.sumOf{model.chars(it)}/PAGE_CHARS/7).toInt();per="в день"}
                1->{val days=(1..today.dayOfMonth).map{today.withDayOfMonth(it)}
                    pts=days.map{d->val pg=(model.chars(d)/PAGE_CHARS).toInt();EdgeLine.Point(minutes(model.ms(d)),duration(model.ms(d))+if(pg>0)", $pg стр." else "",shortDate(d))}
                    labels=days.mapIndexedNotNull{i,d->if(d.dayOfMonth%7==1)i to "${d.dayOfMonth}" else null};strong=-1
                    total=model.total(0);avg=minutes(model.average(0));perPages=(days.sumOf{model.chars(it)}/PAGE_CHARS/days.size).toInt();per="в день"}
                else->{val months=(1..today.monthValue).map{YearMonth.of(today.year,it)}
                    val ms=months.map{m->(1..m.lengthOfMonth()).sumOf{d->model.ms(m.atDay(d))}};val ch=months.map{m->(1..m.lengthOfMonth()).sumOf{d->model.chars(m.atDay(d))}}
                    pts=months.indices.map{i->val pg=(ch[i]/PAGE_CHARS).toInt();EdgeLine.Point(minutes(ms[i]),duration(ms[i])+if(pg>0)", $pg стр." else "",months[i].month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE,ru))}
                    labels=months.mapIndexed{i,m->i to m.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE,ru).trimEnd('.').take(3)};strong=months.size-1
                    total=model.total(1);avg=ms.average().toFloat()/60000f;perPages=(ch.sum()/PAGE_CHARS/months.size).toInt();per="в месяц"}
            }
            fun tile(row:LinearLayout,value:String,what:String){val t=a.column().apply{background=pill(quiet,14);setPadding(a.d(12),a.d(10),a.d(10),a.d(10))}
                t.addView(a.text(value,18f,a.fg,true,serif=true).apply{maxLines=1});t.addView(label(what,11.5f).apply{maxLines=1;setPadding(0,a.d(3),0,0)})
                row.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{if(row.childCount>0)marginStart=a.d(8)})}
            fun fill(){left.removeAllViews()
                left.addView(big(if(total>0)duration(total) else "0 мин",40f).apply{maxLines=1;isSingleLine=true})
                // approved: the averages as three small tiles
                val tiles=a.line();tile(tiles,duration((avg*60000).toLong()),per);if(perPages>0)tile(tiles,"$perPages стр.",per);session?.let{tile(tiles,duration(it),"за один заход")}
                space(left,14);left.addView(tiles,LinearLayout.LayoutParams(-1,-2))}
            if(first||!live)fill()
            else{left.animate().cancel();left.animate().alpha(0f).translationY(-a.d(8).toFloat()).setDuration(150).setInterpolator(Ease.inCubic).withEndAction{fill();left.translationY=a.d(10).toFloat()
                left.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
            chart.set(pts,labels,strong,avg,"",true)
        }
        bind(true);floorChart(box,chart);add(box)
    }

    /** «Вкус»: the favourite author with their covers, the genres as one bar, the decade the books come from; parts
     *  apart by air, no rules. Shown when there is anything to say. */
    private fun taste(){
        val author=more.favouriteAuthor();val genres=more.genres();val decades=more.decades()
        if(author==null&&genres.isEmpty()&&decades==null)return
        val warm=if(light)0xffc87600.toInt() else 0xfff0a83a.toInt()
        val box=surface();box.addView(kicker("Вкус"))
        author?.let{au->space(box,14);val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
            row.addView(AuthorMark(a,au.name),LinearLayout.LayoutParams(a.d(56),a.d(56)).apply{marginEnd=a.d(14)})
            val t=a.column();t.addView(label("Любимый автор",12.5f));t.addView(a.text(au.name,19f,a.fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(2),0,0)})
            val n=au.books.size;t.addView(label("$n ${booksWord(n)}"+if(au.ms>=3_600_000L)", ${(au.ms/3_600_000L)} ч чтения" else "",12.5f).apply{setPadding(0,a.d(2),0,0)})
            row.addView(t,LinearLayout.LayoutParams(0,-2,1f));box.addView(row)}
        if(genres.isNotEmpty()){space(box,if(author!=null)24 else 14);box.addView(label("Жанры",12.5f));space(box,10)
            val colors=listOf(violet,blue,mint,if(light)0xffe2e2e6.toInt() else 0xff3a3a3d.toInt())
            val bar=a.line();genres.forEachIndexed{i,(_,pct)->bar.addView(View(a).apply{background=pill(if(i<3)colors[i] else colors[3],4)},LinearLayout.LayoutParams(0,a.d(8),pct.toFloat()).apply{if(i>0)marginStart=a.d(2)})}
            box.addView(bar,LinearLayout.LayoutParams(-1,a.d(8)))
            genres.take(3).forEachIndexed{i,(name,pct)->val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(8),0,0)}
                r.addView(View(a).apply{background=pill(colors[i],4)},LinearLayout.LayoutParams(a.d(8),a.d(8)).apply{marginEnd=a.d(8)})
                r.addView(a.text(name,13f,a.fg),LinearLayout.LayoutParams(0,-2,1f));r.addView(label("$pct%",13f));box.addView(r)}}
        decades?.let{(shown,top)->space(box,if(author!=null||genres.isNotEmpty())24 else 14)
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL};r.addView(label("Период",12.5f),LinearLayout.LayoutParams(0,-2,1f));r.addView(a.text("$top-е",14f,a.fg,true));box.addView(r);space(box,12)
            val mx=shown.maxOf{it.second}.coerceAtLeast(1)
            val bars=a.line().apply{gravity=Gravity.BOTTOM};val names=a.line()
            shown.forEachIndexed{i,(d,n)->val on=d==top;val h=if(n>0)max(4,(44*n/mx)) else 4
                bars.addView(View(a).apply{background=pill(when{on->warm;n>0->(warm and 0xffffff) or 0x66000000;else->if(light)0xffe2e2e6.toInt() else 0xff3a3a3d.toInt()},4)},LinearLayout.LayoutParams(0,a.d(h),1f).apply{if(i>0)marginStart=a.d(6)})
                names.addView(label(if(on||i%2==1&&abs(shown.indexOfFirst{it.first==top}-i)>1)"$d" else "",10.5f,if(on)a.fg else captionInk,on).apply{gravity=Gravity.CENTER;maxLines=1},LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)marginStart=a.d(6)})}
            box.addView(bars,LinearLayout.LayoutParams(-1,a.d(44)));box.addView(names,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(7)})}
        box.contentDescription="Вкус"+(author?.let{": любимый автор ${it.name}"}?:"")
        add(box)
    }

    /** «Рекорды»: the fastest book, the longest sitting, the most pages in a day; one look for all three rows. */
    private fun records(){
        val fast=more.fastestBook();val sitting=more.longestSession();val day=more.bestDay()
        if(fast==null&&sitting==null&&day==null)return
        val box=surface();box.addView(kicker("Рекорды"))
        fun row(lead:View,title:String,value:String,side:String){space(box,if(box.childCount==1)16 else 16)
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL};r.addView(lead,LinearLayout.LayoutParams(a.d(44),a.d(44)).apply{marginEnd=a.d(14)})
            val t=a.column();t.addView(label(title,12.5f));t.addView(a.text(value,19f,a.fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(3),0,0)});r.addView(t,LinearLayout.LayoutParams(0,-2,1f))
            if(side.isNotBlank())r.addView(label(side,12.5f).apply{gravity=Gravity.END},LinearLayout.LayoutParams(-2,-2).apply{marginStart=a.d(10)});box.addView(r)}
        fun tileOf(kind:String,c:Int)=FrameLayout(a).apply{background=pill((c and 0xffffff) or ((if(light)0x22 else 0x26) shl 24),22);addView(RecordIcon(a,kind,c),FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.CENTER))}
        val orange=if(light)0xffe07a10.toInt() else 0xffff9a3c.toInt()
        fast?.let{(b,days,ms)->row(tileOf("bolt",orange),"Быстрее всего прочитана","«${b.str("title")}» за $days ${daysWordRu(days)}",if(ms>=3_600_000L)"${ms/3_600_000L} ч\nчтения" else if(ms>=60000)"${ms/60000} мин\nчтения" else "")}
        sitting?.let{(ms,at)->row(tileOf("hour",violet),"Самая долгая сессия",duration(ms),at?.let{dayMonth(it)}?:"")}
        day?.let{(pages,at)->row(tileOf("pages",mint),"Больше всего за день","$pages ${pagesWord(pages)}",dayMonth(at))}
        box.contentDescription="Рекорды"
        add(box)
    }

    /** «Мой год в книгах» (approved): the year, the three numbers as tiles, the year's latest books with their stars and
     *  hours; the card (or its button) opens the whole year. */
    private fun books(){
        val box=surface(if(light)0xff009a5c.toInt() else 0xff37c080.toInt(),.86f,.12f,.8f)
        val years=(model.finished.mapNotNull{it.date?.year}+today.year).distinct().sortedDescending().take(8)
        goalYear=today.year
        fun bind(){box.removeAllViews()
            val list=yearBooks(model,data,today.year);val target=model.target(today.year)
            val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
            val title=a.column();title.addView(kicker("Мой год в книгах"));title.addView(a.text("${today.year}",30f,a.fg,true,serif=true).apply{includeFontPadding=false;setPadding(0,a.d(6),0,0)})
            top.addView(title,LinearLayout.LayoutParams(0,-2,1f))
            val expand=FrameLayout(a).apply{isClickable=true;contentDescription="Открыть год в книгах";setOnClickListener{openRoad(box,years){bind()}}
                background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0d000000 else 0x12ffffff)}
                addView(Glyph(a,"expand",a.fg),FrameLayout.LayoutParams(a.d(15),a.d(15),Gravity.CENTER));a.press(this)}
            top.addView(expand,LinearLayout.LayoutParams(a.d(34),a.d(34)).apply{marginStart=a.d(10)})
            box.addView(top)
            if(list.isEmpty()&&target<=0){space(box,16);box.addView(a.emptyGoal(today.year),LinearLayout.LayoutParams(-1,-2));return}
            space(box,16);box.addView(a.yearTiles(list,target,false),LinearLayout.LayoutParams(-1,-2))
            // the latest books in one row, newest first
            if(list.isNotEmpty()){val gap=9;val w=((a.resources.configuration.screenWidthDp-32-36-gap*4)/5).coerceAtMost(72)
                val row=a.line().apply{clipChildren=false;clipToPadding=false}
                list.sortedByDescending{it.end}.take(5).forEach{yb->row.addView(a.yearBookCell(yb,a.d(w)),LinearLayout.LayoutParams(-2,-2).apply{if(row.childCount>0)marginStart=a.d(gap)})}
                space(box,18);box.addView(row,LinearLayout.LayoutParams(-1,-2))}
            box.isClickable=true;box.setOnClickListener{openRoad(box,years){bind()}}
            box.contentDescription="Мой год в книгах: ${list.size}"+if(target>0)" из $target" else ""}
        bind();add(box)
    }
    /** «Тир-лист»: the year's ratings as five short rows of small covers; the card opens the whole list. */
    private fun tiers(){
        val ys=Tiers.years(model);if(ys.isEmpty())return
        TierBackdrop.prepare(a)
        val box=surface(StarRow.starYellow(a),.86f,.1f,.8f);a.tierCard=box
        fun bind(){box.removeAllViews()
            val y=if(today.year in ys)today.year else ys.first()
            val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
            top.addView(kicker("Тир-лист $y"),LinearLayout.LayoutParams(0,-2,1f))
            // the same full-screen button as on «Цель на год»
            top.addView(FrameLayout(a).apply{isClickable=true;contentDescription="Открыть тир-лист";setOnClickListener{box.performClick()}
                background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0d000000 else 0x12ffffff)}
                addView(Glyph(a,"expand",a.fg),FrameLayout.LayoutParams(a.d(15),a.d(15),Gravity.CENTER));a.press(this)},LinearLayout.LayoutParams(a.d(34),a.d(34)))
            box.addView(top);space(box,8)
            val list=Tiers.books(model,y)
            val fit=((a.resources.configuration.screenWidthDp-32-36-24-16)/48).coerceIn(3,8)
            // approved: only the tiers that have books (all five while nothing is rated yet)
            val any=list.any{Tiers.of(it) in 0..4}
            for(k in 0..4){val inTier=Tiers.sorted(list.filter{Tiers.of(it)==k});if(any&&inTier.isEmpty())continue
                val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(72);clipChildren=false;clipToPadding=false}
                row.addView(TierLetter(a,k,24f,false),LinearLayout.LayoutParams(a.d(24),-2))
                // the covers as on the full-screen tier list: sharp and with a soft shadow
                inTier.take(fit).forEach{b->row.addView(CoverView(a,b,a.cover(b)).apply{softShadow=true},LinearLayout.LayoutParams(a.d(40),a.d(60)).apply{marginStart=a.d(if(row.childCount==1)16 else 8)})}
                box.addView(row,LinearLayout.LayoutParams(-1,a.d(72)))}
            box.contentDescription="Тир-лист за $y"}
        box.isClickable=true;box.setOnClickListener{if(a.tierList==null)TierListOverlay(a,model,if(today.year in ys)today.year else ys.first(),{a.tierCard?.takeIf{it.isAttachedToWindow}?:box}){bind()}.show()}
        a.press(box)
        bind();add(box)
    }
    /** The whole road of the year, opened out of the card; switching the year there switches it on the card too. */
    private fun openRoad(box:View,years:List<Int>,rebind:()->Unit){if(a.goalRoad!=null)return
        GoalRoadOverlay(a,model,data,goalYear,years,live,{box}){y->goalYear=y;a.statisticsGoalYear=y;rebind()}.show()}
    private fun daysWordFull(n:Int)="$n "+if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    private fun roundButton(s:String,desc:String)=a.text(s,16f,a.fg).apply{gravity=Gravity.CENTER;contentDescription=desc;isClickable=true
        background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0d000000 else 0x14ffffff)};a.press(this)}
}


internal fun MainActivity.updateStatistics(change:(JSONObject)->Unit) {
    var fresh=JSONObject();work({library.db.runInTransaction{val meta=library.dao.meta("stats")?.let{JSONObject(it.json)} ?: JSONObject().put("id","stats");fresh=meta.optJSONObject("data") ?: JSONObject();change(fresh);meta.put("data",fresh);library.dao.putMeta(MetaRow("stats",meta.toString()))}}){if(profileOverlay!=null){profileStats=fresh;refreshProfile()};checkpointSoon()}
}
internal fun MainActivity.wheel(min:Int,max:Int,value:Int,description:String)=NumberPicker(if(android.os.Build.VERSION.SDK_INT<29 && light)android.view.ContextThemeWrapper(this,android.R.style.Theme_Material_Light)else this).apply {
    minValue=min;maxValue=max;this.value=value.coerceIn(min,max);wrapSelectorWheel=false
    descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS;contentDescription=description
    if(android.os.Build.VERSION.SDK_INT>=29)textColor=fg
    // Wheel-only input: let NumberPicker draw its selected row with the same paint as
    // adjacent rows. Its embedded, non-focusable EditText can clip the baseline in a sheet.
    for(i in 0 until childCount)(getChildAt(i) as? EditText)?.visibility=View.INVISIBLE
}
internal fun MainActivity.monthEditor(initial:YearMonth,today:LocalDate,onSave:(YearMonth)->Unit) {
    val box=column();val captions=line();listOf("Месяц","Год").forEach{captions.addView(text(it,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};box.addView(captions);val row=line();val month=wheel(1,12,initial.monthValue,"Месяц");val year=wheel(1900,today.year,initial.year,"Год");row.addView(month,LinearLayout.LayoutParams(0,d(144),1f));row.addView(year,LinearLayout.LayoutParams(0,d(144),1f));year.setOnValueChangedListener{_,_,y->month.maxValue=if(y==today.year)today.monthValue else 12};if(initial.year==today.year)month.maxValue=today.monthValue
    box.addView(row);dialog().setTitle("Месяц календаря").setView(box).setPositiveButton("Показать"){_,_->onSave(YearMonth.of(year.value,month.value))}.show()
}

/** Thin rounded progress track with a gradient fill. */
internal class ProgressRail(a:MainActivity,animate:Boolean,private val fraction:Float,private val accent:Int,private val accent2:Int,description:String):StatsAnimatedView(a,animate){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription=description}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=h/2f;p.shader=null;p.color=if(a.light)0xffe6e6ea.toInt()else 0xff3a3a3d.toInt();c.drawRoundRect(0f,0f,w,h,r,r,p)
        val f=(fraction*phase).coerceIn(0f,1f);if(f>0f){p.shader=LinearGradient(0f,0f,w,0f,intArrayOf(accent,accent2),null,Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,max(h,w*f),h,r,r,p);p.shader=null}}
}
/** Each infographic starts when scrolled into view, once; no looping shimmer or offscreen work. */
internal open class StatsAnimatedView(a:MainActivity,private val motion:Boolean):AnimatedProfileView(a,false) {
    private var clock:android.animation.ValueAnimator?=null
    private var started=false
    private val watcher=ViewTreeObserver.OnPreDrawListener {
        val visible=Rect()
        if(!started && isShown && getLocalVisibleRect(visible) && visible.height()>height/3){
            started=true
            if(motion && a.prefs.str("motion")!="reduced")clock=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=720;interpolator=android.view.animation.PathInterpolator(.22f,1f,.36f,1f);addUpdateListener{phase=it.animatedValue as Float;invalidate()};start()}
            else {phase=1f;invalidate()}
        };true
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();phase=if(!started && motion && a.prefs.str("motion")!="reduced")0f else 1f;viewTreeObserver.addOnPreDrawListener(watcher)}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;if(viewTreeObserver.isAlive)viewTreeObserver.removeOnPreDrawListener(watcher);super.onDetachedFromWindow()}
}

/** Units next to the big numbers ("ч", "мин", "дней") are set in the interface sans, not in the numbers' serif. */
internal class SansSpan:android.text.style.MetricAffectingSpan(){
    override fun updateDrawState(tp:android.text.TextPaint){tp.typeface=PolkaFonts.sans(400)}
    override fun updateMeasureState(tp:android.text.TextPaint){tp.typeface=PolkaFonts.sans(400)}
}
