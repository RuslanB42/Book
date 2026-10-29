package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
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

/** Only the requested reading metrics. Soft cards on a quiet page: white and pastel tiles, big numerals, thin charts. */
internal class StatisticsScreen(private val a:MainActivity,private val data:JSONObject,range:Int,private val animate:Boolean) {
    private val model=ReadingStatistics(data,a.books)
    private val today=model.today
    private val content=a.column()
    private val scroll=ScrollView(a)
    private var period=range.coerceIn(0,2)
    private var goalYear=(a.statisticsGoalYear ?: today.year).coerceIn(today.year,today.year+1)
    private val compact=a.resources.configuration.screenWidthDp<340 || a.resources.configuration.fontScale>1.15f
    private val metrics=LinearLayout(a).apply{orientation=if(compact)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
    private fun addMetric(card:View){metrics.addView(card,if(compact)LinearLayout.LayoutParams(-1,-2).apply{if(metrics.childCount>0)topMargin=a.d(12)}else LinearLayout.LayoutParams(0,-1,1f).apply{if(metrics.childCount>0)leftMargin=a.d(12)})}
    private fun tone(color:Int?=null)=StatisticsPalette.tone(a.light,color)
    private fun ink(color:Int)=tone(color).accent
    private val cardColor get()=if(a.light)Color.WHITE else 0xff1c1c1e.toInt()
    private val strong get()=if(a.light)0xff161617.toInt() else 0xfff3f2ef.toInt()
    private val onStrong get()=if(a.light)Color.WHITE else 0xff161617.toInt()
    private val quiet get()=if(a.light)0xffeeeeec.toInt() else 0xff2c2c2e.toInt()
    private val coralText get()=if(a.light)0xffc4452a.toInt() else 0xffff9c80.toInt()
    private fun label(s:String,size:Float=14f,color:Int=StatisticsPalette.secondary(a.light),bold:Boolean=false)=a.text(s,size,color,bold)
    private fun space(box:LinearLayout,n:Int=12)=a.space(box,n)
    private fun pill(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=a.d(radius).toFloat()}
    private fun surface(color:Int?=null)=a.column().apply {
        tag="statistics-card"
        val colors=if(color==null)intArrayOf(cardColor,cardColor) else tone(color).let{if(a.light)intArrayOf(it.end,it.start)else intArrayOf(it.start,it.end)}
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors).apply { cornerRadius=a.d(28).toFloat();setStroke(a.d(1),if(a.light)0x0d000000 else 0x14ffffff) }
        setPadding(a.d(18),a.d(18),a.d(18),a.d(18))
    }
    private fun action(s:String,primary:Boolean=false,click:()->Unit)=a.button(s,primary,click).apply {
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33808080),pill(if(primary)strong else quiet,26),null)
        setTextColor(if(primary)onStrong else a.fg)
    }
    private fun segment(labels:List<String>,selected:Int,onSelect:(Int)->Unit):LinearLayout {
        val box=SlidingSegment(a,selected,labels.size,quiet,strong,24)
        fun update(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply {isSelected=i==index;setTextColor(if(isSelected)onStrong else a.fg)}}
        labels.forEachIndexed{i,s->box.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(38);setOnClickListener{box.choose(i);update(i);onSelect(i)}},LinearLayout.LayoutParams(0,-2,1f))}
        update(selected);return box
    }
    private fun add(card:View){content.addView(card,LinearLayout.LayoutParams(-1,-2));space(content,12)}
    private fun heading(box:LinearLayout,title:String,caption:String="",stacked:Boolean=false) {
        val kind=when(title){"Прочитано книг"->"book";"Время чтения"->"clock";"Скорость чтения"->"bolt";else->"user"}
        val chip=FrameLayout(a).apply{background=pill(if(a.light)0xb3ffffff.toInt()else 0x1fffffff,15);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        chip.addView(IconView(a,kind,strong),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))
        val heading=label(title,14f,StatisticsPalette.secondary(a.light),true)
        if(stacked){box.addView(chip,LinearLayout.LayoutParams(a.d(30),a.d(30)));space(box,10);box.addView(heading)}
        else {val row=a.line();row.addView(chip,LinearLayout.LayoutParams(a.d(30),a.d(30)).apply{rightMargin=a.d(10)});row.addView(heading,LinearLayout.LayoutParams(0,-2,1f));box.addView(row)}
        if(caption.isNotBlank()){space(box,4);box.addView(label(caption))};space(box,if(stacked)8 else 16)
    }
    fun show() {
        val extra=max(0,(a.resources.configuration.screenWidthDp-640)/2)
        a.followInsets(content) { i->content.setPadding(a.d(16+extra)+i.left,a.d(8)+i.top,a.d(16+extra)+i.right,a.d(32)+i.bottom) }
        scroll.setBackgroundColor(a.bg);scroll.elevation=a.d(20).toFloat();scroll.addView(content);a.root.addView(scroll,FrameLayout.LayoutParams(-1,-1));a.profileOverlay=scroll
        val head=a.line();head.addView(a.icon("back","Назад"){a.dismissProfile()},LinearLayout.LayoutParams(a.d(44),a.d(44)));head.addView(View(a),LinearLayout.LayoutParams(0,1,1f));head.addView(a.icon("settings","Настройки"){a.settings()},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(head)
        space(content,14)
        streak();annualGoal();booksRead();speed();add(metrics);readingTime();favorite()
        if(animate && a.prefs.str("motion")!="reduced") { NativeMotion(scroll,MotionPose(.55f,y=a.d(12).toFloat(),scale=.99f),duration=420,curve=MotionSpec.settle).start() }
    }
    private fun booksRead() {
        val box=surface(statBlue);box.setPadding(a.d(16),a.d(16),a.d(16),a.d(16));heading(box,"Прочитано книг",stacked=true)
        box.addView(label("${model.countYear(today.year)}",44f,a.fg,true));box.addView(label("За год",13f));space(box,16)
        for((name,value) in listOf("За месяц" to model.countMonth(YearMonth.from(today)),"За всё время" to model.finished.size)){
            val row=a.line();row.addView(label(name,12f),LinearLayout.LayoutParams(0,-2,1f));row.addView(label("$value",16f,a.fg,true));box.addView(row);space(box,6)
        }
        box.contentDescription="Прочитано книг: за месяц ${model.countMonth(YearMonth.from(today))}, за год ${model.countYear(today.year)}, за всё время ${model.finished.size}"
        if(model.undated.isNotEmpty()){space(box,6);box.addView(label("Без даты: ${model.undated.size}",12f))};addMetric(box)
    }
    private fun annualGoal() {
        val box=surface()
        fun bind(){box.removeAllViews()
            box.addView(segment(listOf("Цель · ${today.year}","${today.year+1}"),if(goalYear==today.year)0 else 1){goalYear=today.year+it;a.statisticsGoalYear=goalYear;bind()}.apply{minimumHeight=a.d(44)})
            space(box,18);val target=model.target(goalYear);val done=model.countYear(goalYear)
            if(target==0){box.addView(label("Год для хороших книг",22f,a.fg,true));space(box,6);box.addView(label("Выберите, сколько книг хотите прочитать за $goalYear год. Темп — ваш."));space(box,16);box.addView(action("Задать цель",true){a.annualGoalEditor(goalYear,model)})}
            else {
                val row=a.line();row.addView(AnnualRing(a,done,target,animate),LinearLayout.LayoutParams(a.d(112),a.d(112)))
                val summary=a.column().apply{setPadding(a.d(18),0,0,0)};summary.addView(label("$done из $target",28f,a.fg,true));space(summary,4);summary.addView(label("книг за $goalYear год"));space(summary,8)
                val expected=model.expected(goalYear,target)
                val status=when{done>=target->"Цель достигнута!";goalYear>today.year->"Старт — 1 января";done==expected->"В своём темпе";done>expected->"На ${done-expected} кн. впереди плана";else->"До плана — ${expected-done} кн."}
                summary.addView(label(status,14f,coralText,true));row.addView(summary,LinearLayout.LayoutParams(0,-2,1f));box.addView(row);space(box,16)
                box.addView(action("Изменить цель"){a.annualGoalEditor(goalYear,model)})
            }
        };bind();add(box)
    }
    private fun readingTime() {
        val box=surface(statCyan);heading(box,"Время чтения")
        val values=a.column();fun bind(){values.removeAllViews();val total=model.total(period);val average=model.average(period)
            val row=if(compact)a.column() else a.line().apply {gravity=Gravity.TOP}
            fun metric(title:String,value:String):LinearLayout {val c=a.column();c.addView(label(value,28f,a.fg,true));space(c,4);c.addView(label(title));return c}
            row.addView(metric("Всего за период",duration(total)),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));if(compact)space(row,12)
            row.addView(metric("В среднем в день",duration(average)),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));values.addView(row);space(values,20)
            val plot=model.entries.filter { it.first>=model.start(period) };val n=if(period==0)today.dayOfMonth else if(period==1)today.monthValue else minOf(24,max(1,java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(model.first ?: today),YearMonth.from(today)).toInt()+1))
            val series=when(period){0->(1..n).map { model.ms(today.withDayOfMonth(it))/60000.0 };1->(1..n).map { month->plot.filter {it.first.monthValue==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 };else->(n-1 downTo 0).map { offset->val month=YearMonth.from(today).minusMonths(offset.toLong());plot.filter {YearMonth.from(it.first)==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 }}
            val chartLabels=when(period){0->(1..n).map{today.withDayOfMonth(it).format(DateTimeFormatter.ofPattern("d MMMM",ru))};1->(1..n).map{today.withMonth(it).withDayOfMonth(1).format(DateTimeFormatter.ofPattern("LLLL",ru))};else->(n-1 downTo 0).map{YearMonth.from(today).minusMonths(it.toLong()).atDay(1).format(DateTimeFormatter.ofPattern("LLL yyyy",ru))}}
            val hint=label("",14f,a.fg,true).apply{visibility=View.GONE}
            values.addView(TimeRibbon(a,series,animate,onSelect={i->hint.visibility=View.VISIBLE;hint.text="${chartLabels[i]} · ${duration((series[i]*60000).toLong())}"}),LinearLayout.LayoutParams(-1,a.d(112)));space(values,8)
            val axis=a.line();listOf(chartLabels.first(),chartLabels[chartLabels.size/2],chartLabels.last()).forEachIndexed{i,t->axis.addView(label(t,11f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=when(i){0->Gravity.START;1->Gravity.CENTER;else->Gravity.END}},LinearLayout.LayoutParams(0,-2,1f))};values.addView(axis);space(values,8)
            values.addView(hint)
        }
        box.addView(segment(listOf("Месяц","Год","Всё время"),period){period=it;a.profileRange=it;bind()}.apply{minimumHeight=a.d(44)});space(box,20);box.addView(values);bind();add(box)
    }
    private fun streak() {
        val week=a.line()
        for(i in 6 downTo 0){val date=today.minusDays(i.toLong());val isToday=date==today
            val cell=a.column().apply{gravity=Gravity.CENTER;setPadding(a.d(2),a.d(10),a.d(2),a.d(10));background=GradientDrawable().apply{setColor(cardColor);cornerRadius=a.d(20).toFloat();setStroke(a.d(if(isToday)2 else 1),if(isToday)(if(a.light)0xffee6a4b.toInt()else 0xffff8a6b.toInt())else if(a.light)0x0d000000 else 0x14ffffff)}}
            cell.addView(label(weekNames[date.dayOfWeek.value-1],11f,if(isToday)a.fg else StatisticsPalette.secondary(a.light),isToday));space(cell,8)
            cell.addView(StatsDayMark(a,model.met(date),model.ms(date)>0,isToday),LinearLayout.LayoutParams(a.d(26),a.d(26)));cell.contentDescription="${dayText(date)}: ${if(model.met(date))"серия поддержана" else "${duration(model.ms(date))} чтения"}"
            week.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply{if(i<6)leftMargin=a.d(6)})
        };add(week)
        val box=a.column().apply{tag="statistics-card";background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,if(a.light)intArrayOf(0xffeef9e8.toInt(),0xffc4efd2.toInt())else intArrayOf(0xff1f3b2e.toInt(),0xff17302a.toInt())).apply{cornerRadius=a.d(28).toFloat();setStroke(a.d(1),if(a.light)0x0d000000 else 0x14ffffff)};setPadding(a.d(18),a.d(18),a.d(18),a.d(18))}
        val row=a.line();val emblem=a.column().apply{gravity=Gravity.CENTER}
        emblem.addView(FlameView(a,model.streak,model.streak>0,model.streak>0&&!model.met(today),true).apply{contentDescription="Серия чтения: ${model.streak}";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(a.d(72),a.d(104)))
        row.addView(emblem,LinearLayout.LayoutParams(a.d(88),-2).apply{rightMargin=a.d(6)})
        val summary=a.column();summary.addView(label("Дни подряд",20f,a.fg,true));space(summary,6)
        val left=ceil(max(0,model.dailyGoal*60000L-model.ms(today))/60000.0).toInt()
        summary.addView(label(when{model.met(today)->"Сегодня цель достигнута";model.streak>0->"Ещё $left мин сегодня — и серия продолжится";else->"Читайте ${model.dailyGoal} мин в день, чтобы начать серию"},14f,StatisticsPalette.secondary(a.light)));space(summary,10)
        summary.addView(label("Цель дня · ${model.dailyGoal} мин",12f,a.fg,true).apply{setPadding(a.d(10),a.d(4),a.d(10),a.d(4));background=pill(if(a.light)0x99ffffff.toInt()else 0x1fffffff,12)},LinearLayout.LayoutParams(-2,-2))
        row.addView(summary,LinearLayout.LayoutParams(0,-2,1f));box.addView(row);add(box)
    }
    private fun favorite() {
        val box=surface(statAmber);heading(box,"Любимый автор")
        val author=model.favorite
        if(author==null)box.addView(label("Появится, когда вы дочитаете книгу с указанным автором."))
        else {
            box.addView(label(author.name,24f,a.fg,true));space(box,6);box.addView(label("Прочитано: ${MainActivity.countBooks(author.count)}",14f,ink(statAmber),true));space(box,16)
            val covers=a.line();author.books.take(5).forEachIndexed { i,b ->val cover=CoverView(a,b,a.cover(b)).apply {contentDescription=b.str("title");setOnClickListener{a.detail(b)}};covers.addView(cover,LinearLayout.LayoutParams(a.d(44),a.d(66)).apply {if(i>0)leftMargin=a.d(8)})};box.addView(covers)
        };add(box)
    }
    private fun speed() {
        val box=surface(statViolet);box.setPadding(a.d(16),a.d(16),a.d(16),a.d(16));heading(box,"Скорость чтения",stacked=true)
        box.addView(label(if(model.speed>0)"${model.speed}" else "—",44f,a.fg,true));box.addView(label("слов/мин",13f));space(box,16)
        box.addView(TimeRibbon(a,List(16){(it+1).toDouble()},animate,model.speed),LinearLayout.LayoutParams(-1,a.d(44)));space(box,8)
        val scale=a.line();listOf("0","200","400+").forEachIndexed{i,value->scale.addView(label(value,11f).apply{gravity=when(i){0->Gravity.START;1->Gravity.CENTER;else->Gravity.END}},LinearLayout.LayoutParams(0,-2,1f))};box.addView(scale);addMetric(box)
    }

}

/** Merges against the latest DB value, never an old screen snapshot. */
internal fun MainActivity.updateStatistics(change:(JSONObject)->Unit) {
    var fresh=JSONObject();work({library.db.runInTransaction{val meta=library.dao.meta("stats")?.let{JSONObject(it.json)} ?: JSONObject().put("id","stats");fresh=meta.optJSONObject("data") ?: JSONObject();change(fresh);meta.put("data",fresh);library.dao.putMeta(MetaRow("stats",meta.toString()))}}){if(profileOverlay!=null){profileStats=fresh;refreshProfile()};checkpointSoon()}
}
internal fun MainActivity.annualGoalEditor(year:Int,model:ReadingStatistics) {
    val box=column();box.addView(text("Сколько книг прочитаете за $year год? Уже дочитанные в этом году книги с датой завершения засчитаются автоматически.",16f,muted));space(box,16)
    val input=edit(if(model.target(year)>0)"${model.target(year)}" else "","Например, 24").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(4));contentDescription="Количество книг на год"};box.addView(input)
    space(box,12);val presets=line();listOf(12,24,52).forEach{n->presets.addView(button("$n"){input.setText("$n");input.setSelection(input.length())},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(d(2),0,d(2),0)})};box.addView(presets);space(box,16)
    val error=text("",14f,if(light)0xffad3131.toInt()else 0xffff918c.toInt());error.visibility=View.GONE;box.addView(error)
    lateinit var sheet:android.app.AlertDialog
    box.addView(button("Сохранить цель",true){val n=input.text.toString().toIntOrNull();if(n==null || n !in 1..9999){error.text="Введите число от 1 до 9999";error.visibility=View.VISIBLE;input.requestFocus()}else{updateStatistics{changeAnnualGoal(it,year,n)};sheet.dismiss()}})
    if(model.target(year)>0){space(box,8);box.addView(button("Убрать цель"){confirm("Убрать цель на $year год?","Книги, даты и время чтения сохранятся."){updateStatistics{changeAnnualGoal(it,year,null)};sheet.dismiss()}})}
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

internal class AnnualRing(a:MainActivity,private val done:Int,private val target:Int,animate:Boolean):StatsAnimatedView(a,animate){
    init{contentDescription="Годовая цель: $done из $target книг, ${(done*100.0/target).roundToInt()} процентов"}
    override fun onDraw(c:Canvas){val p=Paint(Paint.ANTI_ALIAS_FLAG);val stroke=a.d(11).toFloat();val r=RectF(stroke,stroke,width-stroke,height-stroke);p.style=Paint.Style.STROKE;p.strokeWidth=stroke;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0xffeeeeec.toInt()else 0xff2c2c2e.toInt();c.drawOval(r,p)
        p.color=if(a.light)0xffee6a4b.toInt()else 0xffff8a6b.toInt();val sweep=360f*min(1f,done.toFloat()/target)*phase;if(sweep>0f)c.drawArc(r,-90f,sweep,false,p)
        p.style=Paint.Style.FILL;p.color=a.fg;p.textAlign=Paint.Align.CENTER;p.textSize=a.d(22).toFloat();p.typeface=Typeface.create("sans-serif",Typeface.BOLD);c.drawText("${(done*100.0/target).roundToInt()}%",width/2f,height/2f-(p.ascent()+p.descent())/2,p)}
}
/** Thin rounded bars: muted for data, one solid bar for the selected (or latest) value. Speed uses full-height ticks. */
internal class TimeRibbon(a:MainActivity,private val values:List<Double>,animate:Boolean,private val speed:Int?=null,private val onSelect:((Int)->Unit)?=null):StatsAnimatedView(a,animate){
    private var selected=-1
    private var touched=-1
    init{if(onSelect!=null){isClickable=true;isFocusable=true};contentDescription=if(speed==null)values.joinToString("; "){duration((it*60000).toLong())}else "Приблизительная скорость: $speed слов в минуту"}
    override fun onTouchEvent(e:MotionEvent):Boolean {if(onSelect==null)return super.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP){touched=(e.x/width*values.size).toInt().coerceIn(0,values.lastIndex);performClick()};return true}
    override fun performClick():Boolean {super.performClick();if(values.isNotEmpty()&&onSelect!=null){selected=if(touched>=0)touched else (selected+1)%values.size;touched=-1;onSelect.invoke(selected);invalidate()};return true}
    override fun onDraw(c:Canvas){if(values.isEmpty())return;val p=Paint(Paint.ANTI_ALIAS_FLAG);val cell=width.toFloat()/values.size;val maximum=max(1.0,values.maxOrNull() ?: 1.0)
        val ink=if(a.light)0xff161617.toInt()else 0xfff3f2ef.toInt();val track=if(a.light)0x16161617 else 0x24ffffff;val data=if(a.light)0x59161617 else 0x73ffffff
        val highlight=if(speed!=null)-1 else if(selected>=0)selected else values.indexOfLast{it>0}
        values.forEachIndexed{i,v->
            val bar=if(speed!=null)max(a.d(2).toFloat(),cell*.42f) else max(1f,cell-a.d(3).toFloat());val x=i*cell+(cell-bar)/2f;val radius=min(a.d(5).toFloat(),bar/2f)
            val h=if(speed!=null)height.toFloat() else max(a.d(5).toFloat(),(v/maximum*height).toFloat())
            p.color=track;c.drawRoundRect(x,height-h,x+bar,height.toFloat(),radius,radius,p)
            val active=speed?.let{it>0 && i.toDouble()/values.size<(it/400.0).coerceIn(0.0,1.0)} ?: (v>0)
            if(active){p.color=if(speed!=null||i==highlight)ink else data;c.drawRoundRect(x,height-h*phase,x+bar,height.toFloat(),radius,radius,p)}
        }
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
