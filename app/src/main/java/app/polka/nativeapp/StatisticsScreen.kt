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

/** Only the requested reading metrics. No library composition, shelves, notes or session leaderboard. */
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
    private fun tint(color:Int)=((if(a.light)0x20 else 0x28) shl 24) or (color and 0xffffff)
    private fun label(s:String,size:Float=14f,color:Int=StatisticsPalette.secondary(a.light),bold:Boolean=false)=a.text(s,size,color,bold)
    private fun space(box:LinearLayout,n:Int=12)=a.space(box,n)
    private fun surface(color:Int?=null)=a.column().apply {
        val colors=tone(color)
        val pigments=when(color){
            statMint->if(a.light)intArrayOf(0xffd8f8e8.toInt(),0xff99f2c8.toInt())else intArrayOf(0xff1f4037.toInt(),0xff285b4a.toInt())
            statBlue->if(a.light)intArrayOf(0xfff6faff.toInt(),0xffe7f1ff.toInt())else intArrayOf(0xff252c37.toInt(),0xff1c2531.toInt())
            statCyan->if(a.light)intArrayOf(0xfff2fbfc.toInt(),0xffdcf1f3.toInt())else intArrayOf(0xff203940.toInt(),0xff1b2d34.toInt())
            statViolet->if(a.light)intArrayOf(0xfff7f1ff.toInt(),0xffeae1f7.toInt())else intArrayOf(0xff332a43.toInt(),0xff252230.toInt())
            else->intArrayOf(colors.start,colors.end)
        }
        tag="statistics-card"
        background=GradientDrawable(GradientDrawable.Orientation.TL_BR,pigments).apply { cornerRadius=a.d(26).toFloat();setStroke(a.d(1),if(a.light)0x60ffffff else 0x12ffffff) }
        setPadding(a.d(16),a.d(16),a.d(16),a.d(16))
    }
    private fun action(s:String,color:Int?=null,primary:Boolean=false,click:()->Unit)=a.button(s,primary,click).apply {
        val colors=tone(color);a.controlSurface(this,28f,selected=primary)
        setTextColor(colors.accent)
    }
    private fun segment(labels:List<String>,selected:Int,color:Int,onSelect:(Int)->Unit):LinearLayout {
        val colors=tone(color);val box=SlidingSegment(a,selected,labels.size,colors.control,colors.accent)
        fun update(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply {isSelected=i==index;setTextColor(if(isSelected)StatisticsPalette.onAccent(a.light)else if(a.light)a.fg else StatisticsPalette.secondary(false))}}
        labels.forEachIndexed{i,s->box.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(36);setOnClickListener{box.choose(i);update(i);onSelect(i)}},LinearLayout.LayoutParams(0,-2,1f))}
        update(selected);return box
    }
    private fun add(card:View){content.addView(card,LinearLayout.LayoutParams(-1,-2));space(content,12)}
    private fun heading(box:LinearLayout,title:String,caption:String="") {val row=a.line();val kind=when(title){"Прочитано книг"->"book";"Время чтения"->"clock";"Скорость чтения"->"bolt";else->"user"};row.addView(IconView(a,kind,a.muted).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(a.d(20),a.d(20)).apply{rightMargin=a.d(8)});row.addView(label(title,if(title=="Прочитано книг"||title=="Скорость чтения")14f else 18f,a.fg,true),LinearLayout.LayoutParams(0,-2,1f));box.addView(row);if(caption.isNotBlank()){space(box,4);box.addView(label(caption))};space(box,16)}
    fun show() {
        val extra=max(0,(a.resources.configuration.screenWidthDp-640)/2)
        a.followInsets(content) { i->content.setPadding(a.d(16+extra)+i.left,a.d(8)+i.top,a.d(16+extra)+i.right,a.d(32)+i.bottom) }
        scroll.setBackgroundColor(a.bg);scroll.elevation=a.d(20).toFloat();scroll.addView(content);a.root.addView(scroll,FrameLayout.LayoutParams(-1,-1));a.profileOverlay=scroll
        val head=a.line();head.addView(a.icon("back","Назад"){a.dismissProfile()},LinearLayout.LayoutParams(a.d(44),a.d(44)));head.addView(View(a),LinearLayout.LayoutParams(0,1,1f));head.addView(a.icon("settings","Настройки"){a.settings()},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(head)
        space(content,18)
        annualGoal();booksRead();speed();add(metrics);readingTime();streak();favorite()
        if(animate && a.prefs.str("motion")!="reduced") { NativeMotion(scroll,MotionPose(.55f,y=a.d(12).toFloat(),scale=.99f),duration=420,curve=MotionSpec.settle).start() }
    }
    private fun booksRead() {
        val box=surface(statBlue);heading(box,"Прочитано книг")
        box.addView(label("${model.countYear(today.year)}",40f,ink(statBlue),true));box.addView(label("За год",13f));space(box,16)
        for((name,value) in listOf("За месяц" to model.countMonth(YearMonth.from(today)),"За всё время" to model.finished.size)){
            val row=a.line();row.addView(label(name,12f),LinearLayout.LayoutParams(0,-2,1f));row.addView(label("$value",16f,a.fg,true));box.addView(row);space(box,6)
        }
        box.contentDescription="Прочитано книг: за месяц ${model.countMonth(YearMonth.from(today))}, за год ${model.countYear(today.year)}, за всё время ${model.finished.size}"
        if(model.undated.isNotEmpty()){space(box,6);box.addView(label("Без даты: ${model.undated.size}",12f))};addMetric(box)
    }
    private fun annualGoal() {
        val box=surface(statMint)
        fun bind(){box.removeAllViews()
            box.addView(segment(listOf("Цель · ${today.year}","${today.year+1}"),if(goalYear==today.year)0 else 1,statMint){goalYear=today.year+it;a.statisticsGoalYear=goalYear;bind()}.apply{minimumHeight=a.d(44)})
            space(box,16);val target=model.target(goalYear);val done=model.countYear(goalYear)
            if(target==0){box.addView(label("Год для хороших книг",22f,a.fg,true));space(box,6);box.addView(label("Выберите, сколько книг хотите прочитать за $goalYear год. Темп — ваш."));space(box,16);box.addView(action("Задать цель",statMint,true){a.annualGoalEditor(goalYear,model)})}
            else {
                val row=a.line();row.addView(AnnualRing(a,done,target,animate),LinearLayout.LayoutParams(a.d(104),a.d(104)))
                val summary=a.column().apply{setPadding(a.d(16),0,0,0)};summary.addView(label("$done из $target",26f,a.fg,true));space(summary,4);summary.addView(label("книг за $goalYear год"));space(summary,8)
                val expected=model.expected(goalYear,target)
                val status=when{done>=target->"Цель достигнута!";goalYear>today.year->"Старт — 1 января";done==expected->"В своём темпе";done>expected->"На ${done-expected} кн. впереди плана";else->"До плана — ${expected-done} кн."}
                summary.addView(label(status,14f,ink(statMint),true));row.addView(summary,LinearLayout.LayoutParams(0,-2,1f));box.addView(row);space(box,12)
                box.addView(action("Изменить цель",statMint){a.annualGoalEditor(goalYear,model)})
            }
        };bind();add(box)
    }
    private fun readingTime() {
        val box=surface(statBlue);heading(box,"Время чтения")
        val values=a.column();fun bind(){values.removeAllViews();val total=model.total(period);val average=model.average(period)
            val row=if(compact)a.column() else a.line().apply {gravity=Gravity.TOP}
            fun metric(title:String,value:String,color:Int):LinearLayout {val c=a.column();c.addView(label(value,26f,ink(color),true));space(c,4);c.addView(label(title));return c}
            row.addView(metric("Всего за период",duration(total),statBlue),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));if(compact)space(row,12)
            row.addView(metric("В среднем в день",duration(average),statCyan),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));values.addView(row);space(values,16)
            val plot=model.entries.filter { it.first>=model.start(period) };val n=if(period==0)today.dayOfMonth else if(period==1)today.monthValue else minOf(24,max(1,java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(model.first ?: today),YearMonth.from(today)).toInt()+1))
            val series=when(period){0->(1..n).map { model.ms(today.withDayOfMonth(it))/60000.0 };1->(1..n).map { month->plot.filter {it.first.monthValue==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 };else->(n-1 downTo 0).map { offset->val month=YearMonth.from(today).minusMonths(offset.toLong());plot.filter {YearMonth.from(it.first)==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 }}
            val chartLabels=when(period){0->(1..n).map{today.withDayOfMonth(it).format(DateTimeFormatter.ofPattern("d MMMM",ru))};1->(1..n).map{today.withMonth(it).withDayOfMonth(1).format(DateTimeFormatter.ofPattern("LLLL",ru))};else->(n-1 downTo 0).map{YearMonth.from(today).minusMonths(it.toLong()).atDay(1).format(DateTimeFormatter.ofPattern("LLL yyyy",ru))}}
            val hint=label("",14f).apply{visibility=View.GONE}
            values.addView(TimeRibbon(a,series,animate,onSelect={i->hint.visibility=View.VISIBLE;hint.text="${chartLabels[i]} · ${duration((series[i]*60000).toLong())}"}),LinearLayout.LayoutParams(-1,a.d(92)));space(values,8)
            values.addView(hint)
        }
        box.addView(segment(listOf("Месяц","Год","Всё время"),period,statBlue){period=it;a.profileRange=it;bind()}.apply{minimumHeight=a.d(44)});space(box,18);box.addView(values);bind();add(box)
    }
    private fun streak() {
        val box=surface(statAmber);val darkInk=0xff26180f.toInt()
        box.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xffff6a00.toInt(),0xffffab00.toInt(),0xffffd500.toInt())).apply{cornerRadius=a.d(26).toFloat()}
        val row=a.line();val emblem=a.column().apply{gravity=Gravity.CENTER}
        emblem.addView(FlameView(a,model.streak,model.streak>0,model.streak>0&&!model.met(today),true).apply{contentDescription="Серия чтения: ${model.streak}";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(a.d(72),a.d(104)))
        row.addView(emblem,LinearLayout.LayoutParams(a.d(88),-2).apply{rightMargin=a.d(4)})
        val summary=a.column();summary.addView(label("Дни подряд",20f,darkInk,true));space(summary,6)
        val left=ceil(max(0,model.dailyGoal*60000L-model.ms(today))/60000.0).toInt()
        summary.addView(label(when{model.met(today)->"Сегодня цель достигнута";model.streak>0->"Ещё $left мин сегодня — и серия продолжится";else->"Читайте ${model.dailyGoal} мин в день, чтобы начать серию"},14f,darkInk));row.addView(summary,LinearLayout.LayoutParams(0,-2,1f));box.addView(row);space(box,20)
        val week=a.line();for(i in 6 downTo 0){val date=today.minusDays(i.toLong());val cell=a.column().apply{gravity=Gravity.CENTER};cell.addView(label(weekNames[date.dayOfWeek.value-1],12f,darkInk));space(cell,7)
            cell.addView(StatsDayMark(a,model.met(date),model.ms(date)>0,date==today),LinearLayout.LayoutParams(a.d(32),a.d(32)));cell.contentDescription="${dayText(date)}: ${if(model.met(date))"серия поддержана" else "${duration(model.ms(date))} чтения"}";week.addView(cell,LinearLayout.LayoutParams(0,-2,1f))};box.addView(week);add(box)
    }
    private fun favorite() {
        val box=surface(statViolet);heading(box,"Любимый автор")
        val author=model.favorite
        if(author==null)box.addView(label("Появится, когда вы дочитаете книгу с указанным автором."))
        else {
            box.addView(label(author.name,24f,a.fg,true));space(box,6);box.addView(label("Прочитано: ${MainActivity.countBooks(author.count)}",14f,ink(statViolet),true));space(box,16)
            val covers=a.line();author.books.take(5).forEachIndexed { i,b ->val cover=CoverView(a,b,a.cover(b)).apply {contentDescription=b.str("title");setOnClickListener{a.detail(b)}};covers.addView(cover,LinearLayout.LayoutParams(a.d(44),a.d(66)).apply {if(i>0)leftMargin=a.d(8)})};box.addView(covers)
        };add(box)
    }
    private fun speed() {
        val box=surface(statCyan);heading(box,"Скорость чтения")
        box.addView(label(if(model.speed>0)"${model.speed}" else "—",40f,ink(statCyan),true));box.addView(label("слов/мин",13f));space(box,16)
        box.addView(TimeRibbon(a,List(16){(it+1).toDouble()},animate,model.speed),LinearLayout.LayoutParams(-1,a.d(38)));space(box,8)
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
    override fun onDraw(c:Canvas){val p=Paint(3);val stroke=a.d(9).toFloat();val r=RectF(stroke,stroke,width-stroke,height-stroke);p.style=Paint.Style.STROKE;p.strokeWidth=stroke;p.strokeCap=Paint.Cap.ROUND;p.color=StatisticsPalette.tone(a.light,statMint).track;c.drawOval(r,p);p.shader=SweepGradient(width/2f,height/2f,if(a.light)intArrayOf(0xff078260.toInt(),0xff076c8e.toInt(),0xff078260.toInt())else intArrayOf(statMint,statCyan,statMint),null);c.drawArc(r,-90f,360f*min(1f,done.toFloat()/target)*phase,false,p);p.shader=null;p.style=Paint.Style.FILL;p.color=a.fg;p.textAlign=Paint.Align.CENTER;p.textSize=a.d(21).toFloat();p.typeface=Typeface.create("sans-serif",Typeface.BOLD);c.drawText("${(done*100.0/target).roundToInt()}%",width/2f,height/2f-(p.ascent()+p.descent())/2,p)}
}
internal class TimeRibbon(a:MainActivity,private val values:List<Double>,animate:Boolean,private val speed:Int?=null,private val onSelect:((Int)->Unit)?=null):StatsAnimatedView(a,animate){
    private var selected=-1
    private var touched=-1
    init{if(onSelect!=null){isClickable=true;isFocusable=true};contentDescription=if(speed==null)values.joinToString("; "){duration((it*60000).toLong())}else "Приблизительная скорость: $speed слов в минуту"}
    override fun onTouchEvent(e:MotionEvent):Boolean {if(onSelect==null)return super.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP){touched=(e.x/width*values.size).toInt().coerceIn(0,values.lastIndex);performClick()};return true}
    override fun performClick():Boolean {super.performClick();if(values.isNotEmpty()&&onSelect!=null){selected=if(touched>=0)touched else (selected+1)%values.size;touched=-1;onSelect.invoke(selected);invalidate()};return true}
    override fun onDraw(c:Canvas){if(values.isEmpty())return;val p=Paint(3);val gap=a.d(3).toFloat();val cell=width.toFloat()/values.size;val track=StatisticsPalette.tone(a.light,if(speed!=null)statCyan else statBlue).track;val maximum=max(1.0,values.maxOrNull() ?: 1.0)
        values.forEachIndexed{i,v->val x=i*cell;val radius=min(a.d(4).toFloat(),(cell-gap)/2).coerceAtLeast(1f);p.color=track;val h=if(speed!=null)height.toFloat() else max(a.d(4).toFloat(),(v/maximum*height).toFloat());c.drawRoundRect(x,height-h,x+max(1f,cell-gap),height.toFloat(),radius,radius,p)
            val active=speed?.let{it>0 && i.toDouble()/values.size<(it/400.0).coerceIn(0.0,1.0)} ?: (v>0)
            if(active){p.shader=LinearGradient(0f,0f,0f,height.toFloat(),StatisticsPalette.graph(a.light,speed!=null),null,Shader.TileMode.CLAMP);c.drawRoundRect(x,height-h*phase,x+max(1f,cell-gap),height.toFloat(),radius,radius,p);p.shader=null};if(i==selected){p.color=a.fg;c.drawCircle(x+max(1f,cell-gap)/2,a.d(3).toFloat(),a.d(2).toFloat(),p)}}
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
