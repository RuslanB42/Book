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
private fun dayText(date:LocalDate)=date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",ru))

/**
 * Only the requested reading metrics. Every card follows one template: an icon tile and a quiet title,
 * a big numeral with a grey caption, a thin scale underneath, and a soft shadow that lets the card float.
 */
internal class StatisticsScreen(private val a:MainActivity,private val data:JSONObject,range:Int,private val animate:Boolean) {
    private val model=ReadingStatistics(data,a.books)
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
    private val coral get()=if(light)0xffe0583a.toInt() else 0xffff8f70.toInt()
    private val violet get()=if(light)0xff7a56d8.toInt() else 0xffa98bf7.toInt()
    private val rose get()=if(light)0xffd0457f.toInt() else 0xfff28bb5.toInt()
    private val amber get()=a.accentFill
    private fun label(s:String,size:Float=14f,color:Int=captionInk,bold:Boolean=false)=a.text(s,size,color,bold)
    private fun space(box:LinearLayout,n:Int=12)=a.space(box,n)
    private fun pill(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=a.d(radius).toFloat()}
    /** White (dark: graphite) card with a hairline edge and a soft, wide shadow underneath so it hovers over the page. */
    private fun surface(glow:Int?=null,fx:Float=.5f,fy:Float=.5f,reach:Float=.8f)=a.column().apply {
        tag="statistics-card"
        val radius=a.d(28).toFloat()
        background=WidgetCardDrawable(a,radius,glow,fx,fy,reach)
        setPadding(a.d(18),a.d(18),a.d(18),a.d(18));clipChildren=false;clipToPadding=false
        elevation=a.d(if(light)6 else 10).toFloat()
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,radius)}}
        if(android.os.Build.VERSION.SDK_INT>=28){outlineAmbientShadowColor=if(light)0x1a000000 else 0x99000000.toInt();outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
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
        return a.column().apply{setPadding(a.d(12),0,a.d(8),0);addView(label(date,12.5f));addView(a.text(hello,23f,a.fg,true,serif=true).apply{maxLines=1})}}
    private fun daysWord(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    private fun minutesShort(n:Int)="мин"
    private fun minutesGen(n:Int)=if(n%100 in 11..14)"минут" else if(n%10==1)"минуты" else "минут"
    fun show() {
        val extra=max(0,(a.resources.configuration.screenWidthDp-640)/2)
        scroll.clipToPadding=false;a.followInsets(content) { i->scroll.setPadding(0,i.top,0,0);content.setPadding(a.d(16+extra)+i.left,a.d(8),a.d(16+extra)+i.right,a.d(40)+i.bottom) }
        scroll.setBackgroundColor(a.bg);scroll.elevation=a.d(20).toFloat();scroll.clipChildren=false;scroll.addView(content);a.root.addView(scroll,FrameLayout.LayoutParams(-1,-1));a.profileOverlay=scroll
        val head=a.line();head.addView(a.icon("back","Назад"){a.dismissProfile()},LinearLayout.LayoutParams(a.d(44),a.d(44)));head.addView(greeting(),LinearLayout.LayoutParams(0,-2,1f));head.addView(a.icon("settings","Настройки"){a.settings()},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(head)
        space(content,8)
        streak();speed();readingTime();books()
        celebrateOnce()
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
    private fun bestStreak():Int{var best=0;var run=0;var d=model.first?:return model.streak
        while(!d.isAfter(today)){if(model.met(d)){run++;best=max(best,run)}else run=0;d=d.plusDays(1)};return max(best,model.streak)}

    private fun minutesAcc(n:Int)=if(n%100 in 11..14)"минут" else when(n%10){1->"минуту";2,3,4->"минуты";else->"минут"}

    /** Streak: the old flame, the number of days, the week as seven day circles (a burning circle = the day counted,
     *  today's ring fills while you read), and today's minutes toward the next flame. Tap — the month calendar. */
    /** «Серия» (approved «стеклянная карточка», in the card's own shape, type and glow): the flame with the count and the
     *  record, a hairline, the week as circles (kept days ticked, today a ring that fills), then today's minutes and the bar. */
    private fun streak(){
        val box=surface(0xfff9601f.toInt(),.14f,.62f,.75f).apply{clipToOutline=true}
        val n=model.streak;val best=bestStreak()
        val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(StreakFlameView(a,n>0,live),LinearLayout.LayoutParams(a.d(52),a.d(64)).apply{rightMargin=a.d(12)})
        val col=a.column()
        col.addView(kicker("Серия"));space(col,2)
        col.addView(bigCount(n," ${daysWord(n)}",34f,.42f))
        top.addView(col,LinearLayout.LayoutParams(0,-2,1f))
        val rec=a.column().apply{gravity=Gravity.END}
        rec.addView(label("рекорд",11.5f).apply{gravity=Gravity.END});rec.addView(label("$best",17f,a.fg,true).apply{gravity=Gravity.END})
        if(n>0||best>0)top.addView(rec,LinearLayout.LayoutParams(-2,-2))
        box.addView(top)
        box.addView(View(a).apply{setBackgroundColor(if(light)0x14000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14);bottomMargin=a.d(14)})
        val dates=(6 downTo 0).map{today.minusDays(it.toLong())}
        val todayFraction=(model.ms(today).toFloat()/(model.dailyGoal*60000L)).coerceIn(0f,1f)
        box.addView(WeekDots(a,dates.map{weekNames[it.dayOfWeek.value-1].lowercase()},BooleanArray(7){model.met(dates[it])},todayFraction),LinearLayout.LayoutParams(-1,-2))
        space(box,16)
        val minutes=(model.ms(today)/60000L).toInt()
        box.addView(kicker("Сегодня"));space(box,2)
        val row=a.line().apply{gravity=Gravity.BOTTOM}
        row.addView(bigCount(min(minutes,model.dailyGoal)," / ${model.dailyGoal} мин",34f,.42f),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label("${(todayFraction*100).roundToInt()}%",14f).apply{setPadding(0,0,0,a.d(6))})
        box.addView(row);space(box,8)
        box.addView(ProgressRail(a,animate,todayFraction,if(light)0xffc87600.toInt() else 0xfff09c17.toInt(),if(light)0xffcf5604.toInt() else 0xfff97c3d.toInt(),"Сегодня: ${min(minutes,model.dailyGoal)} из ${model.dailyGoal} минут"),LinearLayout.LayoutParams(-1,a.d(6)))
        val month=StreakMonth(a,model,today,live)
        box.addView(month,LinearLayout.LayoutParams(-1,-2))
        box.contentDescription="Серия чтения: $n ${daysWord(n)}, рекорд $best"
        box.isClickable=true;box.setOnClickListener{month.toggle()};a.press(box)
        add(box)
    }
    /** Once a day, the first time the statistics open after the day's goal was met, the streak is celebrated. */
    private fun celebrateOnce(){
        if(!model.met(today)||model.streak<=0)return
        val key=today.toString();if(a.prefs.str("streakCelebrated")==key)return
        a.prefs.put("streakCelebrated",key);a.savePreferences()
        val monday=today.minusDays(today.dayOfWeek.value-1L)
        val week=BooleanArray(7){model.met(monday.plusDays(it.toLong()))}
        scroll.postDelayed({if(scroll.isAttachedToWindow){a.streakCelebration?.dismiss();StreakCelebration(a,a.root,model.streak,week,today.dayOfWeek.value-1).show()}},450)
    }

    /** Speed: the number, how it compares with the average reader, and the blue bar from slower to faster. */
    private fun speed(){
        val box=surface(if(light)0xff1b8abd.toInt() else 0xff4cb0e5.toInt(),.88f,.15f,.8f)
        box.addView(kicker("Скорость"));space(box,6)
        box.addView(if(model.speed>0)bigCount(model.speed," слов в минуту",40f,.4f) else big("—",40f))
        space(box,4)
        val diff=((model.speed-200)/2.0).roundToInt()
        box.addView(label(when{model.speed<=0->"появится после пары минут чтения";abs(diff)<3->"как у среднего читателя";diff>0->"на $diff% быстрее среднего читателя";else->"на ${-diff}% медленнее среднего читателя"},12.5f))
        space(box,14)
        box.addView(SpeedBar(a,if(model.speed>0)(model.speed/400f).coerceIn(0f,1f) else null,.5f,live),LinearLayout.LayoutParams(-1,a.d(26)))
        val foot=a.line();foot.addView(label("медленнее",10.5f),LinearLayout.LayoutParams(0,-2,1f));foot.addView(label("~200",10.5f).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f));foot.addView(label("быстрее",10.5f).apply{gravity=Gravity.END},LinearLayout.LayoutParams(0,-2,1f));box.addView(foot)
        box.contentDescription=if(model.speed>0)"Скорость чтения: ${model.speed} слов в минуту" else "Скорость чтения появится после пары минут чтения"
        add(box)
    }

    /** Reading time: the total and the daily average, the Week / Month / Year switch, and a glowing line chart. */
    private fun readingTime(){
        val box=surface(if(light)0xff7973ca.toInt() else 0xff9d98f2.toInt(),.3f,.82f,.7f)
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL};head.addView(kicker("Время чтения"),LinearLayout.LayoutParams(0,-2,1f))
        val left=a.column()
        val names=listOf("Неделя","Месяц","Год")
        val chartHost=FrameLayout(a).apply{clipChildren=false}
        lateinit var bind:(Boolean)->Unit
        head.addView(PeriodPills(a,names,period){i->period=i;a.profileRange=i;bind(false)});box.addView(head);space(box,10);box.addView(left);space(box,4)
        box.addView(chartHost,LinearLayout.LayoutParams(-1,a.d(176)))
        fun minutes(ms:Long)=ms/60000.0
        var chart:ReadingLine?=null
        bind={first->
            val values:List<Double>;val labels:List<String>;val tips:List<String>;val total:Long;val avg:Double;val avgText:String;val caption:String;val sparse:Boolean
            when(period){
                0->{val days=(6 downTo 0).map{today.minusDays(it.toLong())};values=days.map{minutes(model.ms(it))};labels=days.map{weekNames[it.dayOfWeek.value-1].lowercase()}
                    tips=days.map{d->(if(d==today)"сегодня" else d.format(DateTimeFormatter.ofPattern("d MMM",ru)).trimEnd('.'))+" · "+duration(model.ms(d))}
                    total=days.sumOf{model.ms(it)};avg=minutes(total)/7;avgText="ср. ${avg.roundToInt()} мин";caption="в среднем ${duration((total/7))} в день";sparse=false}
                1->{val days=(1..today.dayOfMonth).map{today.withDayOfMonth(it)};values=days.map{minutes(model.ms(it))};labels=days.map{"${it.dayOfMonth}"}
                    tips=days.map{d->(if(d==today)"сегодня" else d.format(DateTimeFormatter.ofPattern("d MMMM",ru)))+" · "+duration(model.ms(d))}
                    total=model.total(0);avg=minutes(model.average(0));avgText="ср. ${avg.roundToInt()} мин";caption="в среднем ${duration(model.average(0))} в день";sparse=true}
                else->{val months=(1..today.monthValue).map{YearMonth.of(today.year,it)};val ms=months.map{m->(1..m.lengthOfMonth()).sumOf{d->model.ms(m.atDay(d))}}
                    values=ms.map{minutes(it)};labels=months.map{it.month.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE,ru).trimEnd('.').take(3)}
                    tips=months.indices.map{i->months[i].month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE,ru)+" · "+duration(ms[i])}
                    total=model.total(1);avg=values.average();avgText="ср. ${duration((avg*60000).toLong())}";caption="в среднем ${duration((avg*60000).toLong())} в месяц";sparse=false}
            }
            // the number and its caption: the old ones lift away, the new ones rise in (the chart morphs meanwhile)
            fun fill(){left.removeAllViews()
                left.addView(big(if(total>0)duration(total) else "0 мин",40f).apply{maxLines=1;isSingleLine=true})
                space(left,4);left.addView(label(caption,12.5f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})}
            if(first||!live)fill()
            else{left.animate().cancel();left.animate().alpha(0f).translationY(-a.d(8).toFloat()).setDuration(150).setInterpolator(Ease.inCubic).withEndAction{fill();left.translationY=a.d(10).toFloat()
                left.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
            val c=chart
            if(c==null){val n=ReadingLine(a,values,labels,tips,avg,avgText,live,sparse);chart=n;chartHost.addView(n,FrameLayout.LayoutParams(-1,-1))}
            else c.setData(values,labels,tips,avg,avgText,sparse)
        }
        bind(true);add(box)
    }

    /** The year's books in one card (approved prototype): «N из M», the year as a road with every finished book on it,
     *  and the chosen book's card underneath — first and last day, days and hours spent, its rating (set it right here).
     *  Tap a cover or a stack, swipe the book card, or switch the year from the «КНИГИ · 2026» label. */
    private var menuClosedAt=0L
    private fun books(){
        GoalBackdrop.prepare(a)
        val box=surface(if(light)0xff009a5c.toInt() else 0xff37c080.toInt(),.86f,.12f,.8f)
        val years=(model.finished.mapNotNull{it.date?.year}+today.year).distinct().sortedDescending().take(8)
        if(goalYear !in years&&goalYear!=today.year+1)goalYear=today.year
        fun bind(){box.removeAllViews()
            val list=yearBooks(model,data,goalYear);val target=model.target(goalYear);val done=list.size
            // head: «ЦЕЛЬ НА 2026 ⌄» opens the year menu, the round button (or a tap on the card) opens the whole road
            val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
            val chevron=Glyph(a,"down",if(light)0xff6e6e73.toInt() else 0xffa9a9ae.toInt())
            val yearBtn=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(12),a.d(6),a.d(9),a.d(6));isClickable=true;contentDescription="Цель на $goalYear, выбрать год"
                background=GradientDrawable().apply{setColor(if(light)0x0d000000 else 0x12ffffff);cornerRadius=a.d(14).toFloat()}
                addView(label(if(target>0||goalYear>=today.year)"ЦЕЛЬ НА $goalYear" else "КНИГИ · $goalYear",12.5f,a.fg,true).apply{letterSpacing=.03f})
                addView(chevron,LinearLayout.LayoutParams(a.d(12),a.d(12)).apply{marginStart=a.d(6)});a.press(this)}
            yearBtn.setOnClickListener{if(android.os.SystemClock.uptimeMillis()-menuClosedAt<400)return@setOnClickListener
                chevron.animate().rotation(180f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
                a.goalYearMenu(yearBtn,years,goalYear,live,{y->yearBooks(model,data,y).size to model.target(y)},{menuClosedAt=android.os.SystemClock.uptimeMillis();chevron.animate().rotation(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}){y->
                    goalYear=y;a.statisticsGoalYear=y
                    if(live)box.animate().alpha(0f).translationY(a.d(6).toFloat()).setDuration(160).setInterpolator(Ease.inCubic).withEndAction{bind();box.translationY=-a.d(6).toFloat()
                        box.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(Ease.outCubic).start()}.start() else bind()}}
            top.addView(yearBtn,LinearLayout.LayoutParams(-2,-2));top.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
            val expand=FrameLayout(a).apply{isClickable=true;contentDescription="Открыть дорогу года";setOnClickListener{openRoad(box,years){bind()}}
                background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0d000000 else 0x12ffffff)}
                addView(Glyph(a,"expand",a.fg),FrameLayout.LayoutParams(a.d(15),a.d(15),Gravity.CENTER));a.press(this)}
            top.addView(expand,LinearLayout.LayoutParams(a.d(34),a.d(34)))
            box.addView(top)
            val head=a.column();space(head,10)
            head.addView(if(target>0)bigCount(done," из ${ofBooks(target)}",46f,.35f) else bigCount(done," "+MainActivity.countBooks(done).substringAfter(' '),46f,.35f));space(head,5)
            head.addView(label(goalPace(goalYear,today,done,target),12.5f))
            box.addView(head)
            if(list.isEmpty()){space(box,16);box.addView(a.emptyGoal(goalYear),LinearLayout.LayoutParams(-1,-2));return}
            // only the road: finished books stand on it as covers; the card opens the whole road
            val road=YearRoad(a,list,target,live){_,_->}
            space(box,6);box.addView(road,LinearLayout.LayoutParams(-1,-2))
            box.isClickable=true;box.setOnClickListener{openRoad(box,years){bind()}}
            box.contentDescription="Книги за $goalYear: $done"+if(target>0)" из $target" else ""}
        bind();add(box)
    }
    /** The whole road of the year, opened out of the card; switching the year there switches it on the card too. */
    private fun openRoad(box:View,years:List<Int>,rebind:()->Unit){if(a.goalRoad!=null)return
        GoalRoadOverlay(a,model,data,goalYear,years,live,{box}){y->goalYear=y;a.statisticsGoalYear=y;rebind()}.show()}
    @Suppress("UNCHECKED_CAST")
    private fun toggleRate(rateRow:LinearLayout,road:YearRoad){if(rateRow.visibility==View.VISIBLE){closeRate(rateRow);return}
        rateRow.removeAllViews();val pick=rateRow.tag as? (Int)->Unit;val current=road.current.optInt("rating")
        val stars=(1..5).map{StarGlyph(a,it<=current)}
        stars.forEachIndexed{i,g->val v=i+1
            rateRow.addView(FrameLayout(a).apply{isClickable=true;contentDescription="Оценка $v из 5"
                // tapping a star lights the stars up to it one after another, then the rating is saved
                setOnClickListener{stars.forEachIndexed{k,o->o.on=k<v;if(k<v&&live){o.scaleX=.6f;o.scaleY=.6f;o.animate().scaleX(1f).scaleY(1f).setStartDelay(k*40L).setDuration(320).setInterpolator(PathInterpolator(.2f,1.6f,.4f,1f)).start()}}
                    rateRow.postDelayed({pick?.invoke(v)},if(live)360L else 0L)}
                addView(g,FrameLayout.LayoutParams(a.d(30),a.d(30),Gravity.CENTER))
                if(live){alpha=0f;translationY=a.d(8).toFloat();animate().alpha(1f).translationY(0f).setStartDelay(v*40L).setDuration(360).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}},LinearLayout.LayoutParams(0,a.d(44),1f))}
        rateRow.visibility=View.VISIBLE}
    private fun closeRate(rateRow:LinearLayout){rateRow.visibility=View.GONE}
    private fun daysWordFull(n:Int)="$n "+if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    private fun roundButton(s:String,desc:String)=a.text(s,16f,a.fg).apply{gravity=Gravity.CENTER;contentDescription=desc;isClickable=true
        background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(light)0x0d000000 else 0x14ffffff)};a.press(this)}
}

/** Small page dots under the book card: the open book is a longer green pill. */
internal class PagerDots(private val a:MainActivity,private val n:Int,private val at:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat();var x=0f;val cy=height/2f
        for(i in 0 until n){val w=if(i==at)16*d else 6*d;p.color=if(i==at)(if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt()) else if(a.light)0x33000000 else 0x4dffffff
            c.drawRoundRect(x,cy-3*d,x+w,cy+3*d,3*d,3*d,p);x+=w+6*d}}
}

internal fun MainActivity.updateStatistics(change:(JSONObject)->Unit) {
    var fresh=JSONObject();work({library.db.runInTransaction{val meta=library.dao.meta("stats")?.let{JSONObject(it.json)} ?: JSONObject().put("id","stats");fresh=meta.optJSONObject("data") ?: JSONObject();change(fresh);meta.put("data",fresh);library.dao.putMeta(MetaRow("stats",meta.toString()))}}){if(profileOverlay!=null){profileStats=fresh;refreshProfile()};checkpointSoon()}
}
internal fun MainActivity.annualGoalEditor(year:Int,model:ReadingStatistics,saved:(Int?)->Unit={}) {
    val box=column();box.addView(text("Сколько книг прочитаете за $year год? Уже дочитанные в этом году книги с датой завершения засчитаются автоматически.",16f,muted));space(box,16)
    val input=edit(if(model.target(year)>0)"${model.target(year)}" else "","Например, 24").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(4));contentDescription="Количество книг на год"};box.addView(input)
    space(box,12);val presets=line();listOf(12,24,52).forEach{n->presets.addView(button("$n"){input.setText("$n");input.setSelection(input.length())},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(2),0,d(2),0)})};box.addView(presets);space(box,16)
    val error=text("",14f,if(light)0xffad3131.toInt()else 0xffff918c.toInt());error.visibility=View.GONE;box.addView(error)
    lateinit var sheet:android.app.AlertDialog
    box.addView(button("Сохранить цель",true){val n=input.text.toString().toIntOrNull();if(n==null || n !in 1..9999){error.text="Введите число от 1 до 9999";error.visibility=View.VISIBLE;input.requestFocus()}else{updateStatistics{changeAnnualGoal(it,year,n)};saved(n);sheet.dismiss()}})
    if(model.target(year)>0){space(box,8);box.addView(button("Убрать цель"){confirm("Убрать цель на $year год?","Книги, даты и время чтения сохранятся."){updateStatistics{changeAnnualGoal(it,year,null)};saved(null);sheet.dismiss()}})}
    sheet=dialog().setTitle("Цель на $year год").setView(box).show()
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
internal fun MainActivity.completionDateEditor(book:JSONObject,initial:LocalDate?) {
    val today=LocalDate.now();val date=initial ?: today;val box=column();box.addView(text(book.str("title"),18f,bold=true));space(box,8);box.addView(text("Дата учитывается в статистике прочитанных книг и годовой цели.",14f,muted));space(box,12)
    val captions=line();listOf("День","Месяц","Год").forEach{captions.addView(text(it,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};box.addView(captions)
    val row=line();val day=wheel(1,31,date.dayOfMonth,"День");val month=wheel(1,12,date.monthValue,"Месяц");val year=wheel(1900,today.year,date.year,"Год")
    fun limit(){month.maxValue=if(year.value==today.year)today.monthValue else 12;val ym=YearMonth.of(year.value,month.value);day.maxValue=if(ym==YearMonth.from(today))today.dayOfMonth else ym.lengthOfMonth()};year.setOnValueChangedListener{_,_,_->limit()};month.setOnValueChangedListener{_,_,_->limit()};limit()
    listOf(day,month,year).forEach{row.addView(it,LinearLayout.LayoutParams(0,d(144),1f))};box.addView(row)
    dialog().setTitle("Дата завершения").setView(box).setPositiveButton("Сохранить дату"){_,_->val chosen=LocalDate.of(year.value,month.value,day.value);val latest=books.firstOrNull{it.str("id")==book.str("id")};if(latest!=null){statisticsMonth=YearMonth.from(chosen);statisticsDay=chosen;saveBook(latest.copyJson().put("completedAt",chosen.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))}}.setNegativeButton("Отмена",null).show()
}

/** Horizontal scale: a full gradient ruler with a thumb (speed) or a filled progress track with a plan marker (goal). */
internal class ScaleRail(a:MainActivity,animate:Boolean,private val scale:Boolean,private val value:Float?,private val marker:Float?,private val markerLabel:String?,private val left:String,private val right:String,private val accent:Int,private val accent2:Int,description:String):StatsAnimatedView(a,animate){
    private val text=Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription=description;text.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,13f,a.resources.displayMetrics)}
    private val head get()=a.d(26).toFloat()
    override fun onMeasure(w:Int,h:Int){val fm=text.fontMetrics;setMeasuredDimension(MeasureSpec.getSize(w),(head+a.d(8)+(fm.descent-fm.ascent)+a.d(2)).toInt())}
    override fun onDraw(c:Canvas){
        val w=width.toFloat();val railH=a.d(12).toFloat();val cy=head/2f;val r=railH/2f;val light=a.light
        shape.shader=null;shape.style=Paint.Style.FILL;shape.color=if(light)0xffe6e6ea.toInt()else 0xff3a3a3d.toInt()
        if(scale){shape.shader=LinearGradient(0f,0f,w,0f,intArrayOf(if(light)0xffe0e0e5.toInt()else 0xff47474b.toInt(),accent),null,Shader.TileMode.CLAMP);c.drawRoundRect(0f,cy-r,w,cy+r,r,r,shape);shape.shader=null}
        else{c.drawRoundRect(0f,cy-r,w,cy+r,r,r,shape);val f=((value?:0f)*phase).coerceIn(0f,1f)
            if(f>0f){shape.shader=LinearGradient(0f,0f,w,0f,intArrayOf(accent2,accent),null,Shader.TileMode.CLAMP);c.drawRoundRect(0f,cy-r,max(railH,w*f),cy+r,r,r,shape);shape.shader=null}}
        val tick=a.d(1).toFloat()
        if(marker!=null){val x=(w*marker).coerceIn(tick,w-tick);shape.color=if(light)0xffa0a0a6.toInt()else 0xffcfcfd4.toInt();c.drawRoundRect(x-tick,0f,x+tick,head,tick,tick,shape)}
        if(scale&&value!=null){val x=(w*value*phase).coerceIn(a.d(10).toFloat(),w-a.d(10).toFloat());shape.color=accent;c.drawCircle(x,cy,a.d(10).toFloat(),shape);shape.color=if(light)Color.WHITE else 0xfff3f2ef.toInt();c.drawCircle(x,cy,a.d(6).toFloat(),shape)}
        text.color=if(light)0xff86868b.toInt()else 0xff8a8a8e.toInt();val base=head+a.d(8)-text.fontMetrics.ascent
        text.textAlign=Paint.Align.LEFT;c.drawText(left,0f,base,text);text.textAlign=Paint.Align.RIGHT;c.drawText(right,w,base,text)
        if(markerLabel!=null&&marker!=null){text.textAlign=Paint.Align.CENTER;val tw=text.measureText(markerLabel);val cx=(w*marker).coerceIn(tw/2f,w-tw/2f);val gap=a.d(10).toFloat()
            if(cx-tw/2f>text.measureText(left)+gap&&cx+tw/2f<w-text.measureText(right)-gap)c.drawText(markerLabel,cx,base,text)}
    }
}
/** Thin rounded progress track with a gradient fill. */
internal class ProgressRail(a:MainActivity,animate:Boolean,private val fraction:Float,private val accent:Int,private val accent2:Int,description:String):StatsAnimatedView(a,animate){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription=description}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=h/2f;p.shader=null;p.color=if(a.light)0xffe6e6ea.toInt()else 0xff3a3a3d.toInt();c.drawRoundRect(0f,0f,w,h,r,r,p)
        val f=(fraction*phase).coerceIn(0f,1f);if(f>0f){p.shader=LinearGradient(0f,0f,w,0f,intArrayOf(accent,accent2),null,Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,max(h,w*f),h,r,r,p);p.shader=null}}
}
/** Thin rounded bars: muted for data, one solid accent bar for the selected (or latest) value.
 *  With [tips]: press and hold a bar to see a small floating bubble with its label above it; slide to scrub. */
internal class TimeRibbon(a:MainActivity,private val values:List<Double>,animate:Boolean,private val speed:Int?=null,private val onSelect:((Int)->Unit)?=null,private val accent:Int?=null,private val tips:List<Pair<String,String>>?=null):StatsAnimatedView(a,animate){
    private var selected=-1
    private var touched=-1
    private var held=false
    private var bubble=0f;private var bubbleX=-1f
    private var bubbleAnim:android.animation.ValueAnimator?=null
    private var downX=0f;private var downY=0f
    private val holdTask=Runnable{if(tips!=null&&values.isNotEmpty()){held=true;parent?.requestDisallowInterceptTouchEvent(true);pick(downX,true);showBubble(true)}}
    private val hideTask=Runnable{showBubble(false)}
    init{if(onSelect!=null||tips!=null){isClickable=true;isFocusable=true};contentDescription=if(speed==null)values.joinToString("; "){duration((it*60000).toLong())}else "Приблизительная скорость: $speed слов в минуту"}
    private fun indexAt(x:Float)=(x/width*values.size).toInt().coerceIn(0,values.lastIndex)
    private fun pick(x:Float,first:Boolean){val i=indexAt(x);if(i!=selected||first){selected=i;if(a.prefs.optBoolean("haptics",true))performHapticFeedback(if(first)HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK);invalidate()}}
    private fun showBubble(on:Boolean){bubbleAnim?.cancel();val from=bubble;val to=if(on)1f else 0f
        if(a.prefs.str("motion")=="reduced"){bubble=to;if(!on)selected=-1;invalidate();return}
        bubbleAnim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=if(on)200 else 220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{bubble=it.animatedValue as Float;invalidate()};addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(!on){selected=-1;bubbleX=-1f;invalidate()}}});start()}}
    // Room for the bubble above the bars: unclip only up to the card itself — never the scrolling page, which must keep clipping.
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(tips!=null){var p=parent as? ViewGroup;while(p!=null&&p !is ScrollView){p.clipChildren=false;p.clipToPadding=false;if(p.tag=="statistics-card")break;p=p.parent as? ViewGroup}}}
    override fun onDetachedFromWindow(){removeCallbacks(holdTask);removeCallbacks(hideTask);bubbleAnim?.cancel();super.onDetachedFromWindow()}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(tips!=null){when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;removeCallbacks(hideTask);postDelayed(holdTask,(ViewConfiguration.getLongPressTimeout()*.55f).toLong())}
            MotionEvent.ACTION_MOVE->if(held)pick(e.x,false) else if(kotlin.math.abs(e.x-downX)>a.d(10)||kotlin.math.abs(e.y-downY)>a.d(10))removeCallbacks(holdTask)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{removeCallbacks(holdTask);if(held){held=false;parent?.requestDisallowInterceptTouchEvent(false);postDelayed(hideTask,1100)}}
        };return true}
        if(onSelect==null)return super.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP){touched=(e.x/width*values.size).toInt().coerceIn(0,values.lastIndex);performClick()};return true}
    override fun performClick():Boolean {super.performClick();if(tips==null&&values.isNotEmpty()&&onSelect!=null){selected=if(touched>=0)touched else (selected+1)%values.size;touched=-1;onSelect.invoke(selected);invalidate()};return true}
    private val tipPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){if(values.isEmpty())return;val p=Paint(Paint.ANTI_ALIAS_FLAG);val cell=width.toFloat()/values.size;val maximum=max(1.0,values.maxOrNull() ?: 1.0)
        val ink=accent ?: if(a.light)0xff161617.toInt()else 0xfff3f2ef.toInt();val track=if(a.light)0x16161617 else 0x24ffffff;val data=if(a.light)0x59161617 else 0x73ffffff
        val highlight=if(speed!=null)-1 else if(selected>=0)selected else values.indexOfLast{it>0}
        values.forEachIndexed{i,v->
            val bar=if(speed!=null)max(a.d(2).toFloat(),cell*.42f) else max(1f,cell-a.d(3).toFloat());val x=i*cell+(cell-bar)/2f;val radius=min(a.d(5).toFloat(),bar/2f)
            val h=if(speed!=null)height.toFloat() else max(a.d(5).toFloat(),(v/maximum*height).toFloat())
            p.color=track;c.drawRoundRect(x,height-h,x+bar,height.toFloat(),radius,radius,p)
            val active=speed?.let{it>0 && i.toDouble()/values.size<(it/400.0).coerceIn(0.0,1.0)} ?: (v>0)
            if(active){p.color=if(speed!=null||i==highlight)ink else data;c.drawRoundRect(x,height-h*phase,x+bar,height.toFloat(),radius,radius,p)}
            else if(i==selected&&bubble>0f){p.color=ink;p.alpha=(90*bubble).toInt();c.drawRoundRect(x,height-h,x+bar,height.toFloat(),radius,radius,p);p.alpha=255}
        }
        // Floating bubble above the held bar.
        if(tips!=null&&selected in tips.indices&&bubble>0f){val (title,value)=tips[selected];val cx=selected*cell+cell/2f
            bubbleX=if(bubbleX<0f)cx else bubbleX+(cx-bubbleX)*.45f;if(kotlin.math.abs(bubbleX-cx)>.5f)postInvalidateOnAnimation()
            tipPaint.textSize=a.d(13).toFloat();tipPaint.typeface=PolkaFonts.sans(600);val w1=tipPaint.measureText(value)
            tipPaint.textSize=a.d(11).toFloat();tipPaint.typeface=PolkaFonts.sans(400);val w2=tipPaint.measureText(title)
            val bw=max(w1,w2)+a.d(24);val bh=a.d(44).toFloat();val bx=(bubbleX-bw/2).coerceIn(0f,width-bw);val v=values[selected];val barTop=height-max(a.d(5).toFloat(),(v/maximum*height).toFloat())
            val by=barTop-bh-a.d(10)
            c.save();c.translate(0f,a.d(6)*(1f-bubble));val s=.92f+.08f*bubble;c.scale(s,s,bubbleX,by+bh)
            tipPaint.alpha=255;tipPaint.color=if(a.light)Color.WHITE else 0xff343438.toInt();tipPaint.alpha=(255*bubble).toInt()
            tipPaint.setShadowLayer(a.d(8).toFloat(),0f,a.d(3).toFloat(),if(a.light)(0x33*bubble).toInt() shl 24 else (0x99*bubble).toInt() shl 24)
            c.drawRoundRect(bx,by,bx+bw,by+bh,a.d(12).toFloat(),a.d(12).toFloat(),tipPaint);tipPaint.clearShadowLayer()
            // a small pointer down to the bar
            val tip=Path();tip.moveTo(bubbleX-a.d(6),by+bh-1);tip.lineTo(bubbleX,by+bh+a.d(6));tip.lineTo(bubbleX+a.d(6),by+bh-1);tip.close();c.drawPath(tip,tipPaint)
            tipPaint.textAlign=Paint.Align.CENTER;val mid=bx+bw/2
            tipPaint.color=a.fg;tipPaint.alpha=(255*bubble).toInt();tipPaint.textSize=a.d(13).toFloat();tipPaint.typeface=PolkaFonts.sans(600);c.drawText(value,mid,by+a.d(19),tipPaint)
            tipPaint.color=a.muted;tipPaint.alpha=(255*bubble).toInt();tipPaint.textSize=a.d(11).toFloat();tipPaint.typeface=PolkaFonts.sans(400);c.drawText(title,mid,by+a.d(35),tipPaint)
            c.restore()}
    }
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
