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

/**
 * Only the requested reading metrics. Every card follows one template: an icon tile and a quiet title,
 * a big numeral with a grey caption, a thin scale underneath, and a soft shadow that lets the card float.
 */
internal class StatisticsScreen(private val a:MainActivity,private val data:JSONObject,range:Int,private val animate:Boolean) {
    private val model=ReadingStatistics(data,a.books)
    private val today=model.today
    private val content=a.column().apply{clipChildren=false;clipToPadding=false}
    private val scroll=ScrollView(a)
    private var period=range.coerceIn(0,2)
    private var goalYear=(a.statisticsGoalYear ?: today.year).coerceIn(today.year,today.year+1)
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
    private fun surface()=a.column().apply {
        tag="statistics-card"
        val radius=a.d(28).toFloat()
        background=GradientDrawable().apply{setColor(cardColor);cornerRadius=radius;setStroke(a.d(1),if(light)0x08000000 else 0x14ffffff)}
        setPadding(a.d(20),a.d(20),a.d(20),a.d(20));clipChildren=false;clipToPadding=false
        elevation=a.d(if(light)9 else 12).toFloat()
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,radius)}}
        if(android.os.Build.VERSION.SDK_INT>=28){outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
    }
    private fun tile(kind:String,color:Int):View=FrameLayout(a).apply{
        background=pill((color and 0xffffff) or ((if(light)0x26 else 0x33) shl 24),12);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(IconView(a,kind,color),FrameLayout.LayoutParams(a.d(19),a.d(19),Gravity.CENTER))
    }
    private fun header(box:LinearLayout,title:String,kind:String,color:Int) {
        val row=a.line();row.addView(tile(kind,color),LinearLayout.LayoutParams(a.d(40),a.d(40)).apply{rightMargin=a.d(14)})
        row.addView(label(title,15.5f,titleInk),LinearLayout.LayoutParams(0,-2,1f));box.addView(row)
    }
    private fun action(s:String,primary:Boolean=false,click:()->Unit)=a.button(s,primary,click).apply {
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33808080),pill(if(primary)strong else quiet,26),null)
        setTextColor(if(primary)onStrong else a.fg)
    }
    private fun segment(labels:List<String>,selected:Int,onSelect:(Int)->Unit):LinearLayout {
        val box=SlidingSegment(a,selected,labels.size,quiet,thumb,24)
        fun update(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply {isSelected=i==index;setTextColor(if(isSelected)a.fg else captionInk)}}
        labels.forEachIndexed{i,s->box.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(38);setOnClickListener{box.choose(i);update(i);onSelect(i)}},LinearLayout.LayoutParams(0,-2,1f))}
        update(selected);return box
    }
    private fun add(card:View){content.addView(card,LinearLayout.LayoutParams(-1,-2));space(content,18)}
    private fun daysWord(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    private fun minutesGen(n:Int)=if(n%100 in 11..14)"минут" else if(n%10==1)"минуты" else "минут"
    fun show() {
        val extra=max(0,(a.resources.configuration.screenWidthDp-640)/2)
        a.followInsets(content) { i->content.setPadding(a.d(16+extra)+i.left,a.d(8)+i.top,a.d(16+extra)+i.right,a.d(40)+i.bottom) }
        scroll.setBackgroundColor(a.bg);scroll.elevation=a.d(20).toFloat();scroll.clipChildren=false;scroll.addView(content);a.root.addView(scroll,FrameLayout.LayoutParams(-1,-1));a.profileOverlay=scroll
        val head=a.line();head.addView(a.icon("back","Назад"){a.dismissProfile()},LinearLayout.LayoutParams(a.d(44),a.d(44)));head.addView(View(a),LinearLayout.LayoutParams(0,1,1f));head.addView(a.icon("settings","Настройки"){a.settings()},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(head)
        space(content,14)
        streak();annualGoal();booksRead();speed();readingTime();favorite()
        if(animate && a.prefs.str("motion")!="reduced") { NativeMotion(scroll,MotionPose(.55f,y=a.d(12).toFloat(),scale=.99f),duration=420,curve=MotionSpec.settle).start() }
    }
    private fun streak() {
        val box=surface()
        val top=a.line().apply{gravity=Gravity.TOP}
        top.addView(FlameView(a,model.streak,model.streak>0,model.streak>0&&!model.met(today),true).apply{contentDescription="Серия чтения: ${model.streak}";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(a.d(92),a.d(132)).apply{rightMargin=a.d(16)})
        val side=a.column().apply{setPadding(0,a.d(6),0,0)}
        side.addView(a.text("${daysWord(model.streak)} подряд",26f,a.fg,true,serif=true).apply{cssLeading(1.1f)});space(side,8)
        val minutes=(model.ms(today)/60000L).toInt();val left=ceil(max(0,model.dailyGoal*60000L-model.ms(today))/60000.0).toInt()
        side.addView(label(when{model.met(today)->"Сегодня цель достигнута";model.streak>0->"Ещё $left ${minutesGen(left)} сегодня — и серия продолжится";else->"Огонёк загорится после ${model.dailyGoal} ${minutesGen(model.dailyGoal)} чтения за день"},15f,captionInk).apply{cssLeading(1.3f)});space(side,16)
        val today1=a.line();today1.addView(label("Сегодня",13.5f),LinearLayout.LayoutParams(0,-2,1f));today1.addView(label("${min(minutes,model.dailyGoal)} из ${model.dailyGoal} мин",13.5f,if(model.met(today))strong else captionInk,model.met(today)));side.addView(today1);space(side,8)
        side.addView(ProgressRail(a,animate,(model.ms(today).toFloat()/(model.dailyGoal*60000L)).coerceIn(0f,1f),amber,coral,"Сегодня: ${min(minutes,model.dailyGoal)} из ${model.dailyGoal} минут"),LinearLayout.LayoutParams(-1,a.d(7)))
        top.addView(side,LinearLayout.LayoutParams(0,-2,1f));box.addView(top);space(box,22)
        val week=a.line()
        for(i in 6 downTo 0){val date=today.minusDays(i.toLong());val isToday=date==today
            val cell=a.column().apply{gravity=Gravity.CENTER};cell.addView(StatsDayMark(a,model.met(date),model.ms(date)>0,isToday),LinearLayout.LayoutParams(a.d(36),a.d(36)));space(cell,8)
            cell.addView(label(weekNames[date.dayOfWeek.value-1],13f,if(isToday)a.fg else captionInk,isToday).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
            cell.contentDescription="${dayText(date)}: ${if(model.met(date))"серия поддержана" else "${duration(model.ms(date))} чтения"}"
            week.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
        };box.addView(week);add(box)
    }
    private fun speed() {
        val box=surface();header(box,"Скорость","bolt",blue);space(box,24)
        box.addView(label(if(model.speed>0)"${model.speed}" else "—",44f,a.fg,true));space(box,4)
        box.addView(label(if(model.speed>0)"слов в минуту" else "посчитаем после пары минут чтения",14.5f));space(box,22)
        box.addView(ScaleRail(a,animate,true,if(model.speed>0)(model.speed/400f).coerceIn(0f,1f) else null,.5f,"ср. 200","0","400+",blue,blue,if(model.speed>0)"Скорость чтения: ${model.speed} слов в минуту, среднее около 200" else "Скорость чтения пока не посчитана"),LinearLayout.LayoutParams(-1,-2));add(box)
    }
    private fun annualGoal() {
        val box=surface()
        fun bind(){box.removeAllViews();header(box,"Цель на год","target",coral);space(box,18)
            box.addView(segment(listOf("Цель · ${today.year}","${today.year+1}"),if(goalYear==today.year)0 else 1){goalYear=today.year+it;a.statisticsGoalYear=goalYear;bind()}.apply{minimumHeight=a.d(44)})
            space(box,22);val target=model.target(goalYear);val done=model.countYear(goalYear)
            if(target==0){box.addView(label("Год для хороших книг",22f,a.fg,true));space(box,6);box.addView(label("Выберите, сколько книг хотите прочитать за $goalYear год. Темп — ваш.",14.5f));space(box,18);box.addView(action("Задать цель",true){a.annualGoalEditor(goalYear,model)})}
            else {
                box.addView(label("$done из $target",44f,a.fg,true));space(box,4);box.addView(label("книг за $goalYear год",14.5f));space(box,22)
                box.addView(ScaleRail(a,animate,false,(done.toFloat()/target).coerceIn(0f,1f),null,null,"0","$target",coral,amber,"Годовая цель: $done из $target книг, ${(done*100.0/target).roundToInt()} процентов"),LinearLayout.LayoutParams(-1,-2));space(box,16)
                box.addView(label(if(done>=target)"Цель достигнута!" else "Осталось: ${MainActivity.countBooks(target-done)}",14.5f,coral,true));space(box,18)
                box.addView(action("Изменить цель"){a.annualGoalEditor(goalYear,model)})
            }
        };bind();add(box)
    }
    private fun booksRead() {
        val box=surface();header(box,"Прочитано книг","book",mint);space(box,24)
        box.addView(label("${model.countYear(today.year)}",44f,a.fg,true));space(box,4);box.addView(label("за этот год",14.5f));space(box,20)
        for((i,pair) in listOf("За месяц" to model.countMonth(YearMonth.from(today)),"За всё время" to model.finished.size).withIndex()){
            box.addView(View(a).apply{setBackgroundColor(if(light)0x0f000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,a.d(1)))
            val row=a.line().apply{setPadding(0,a.d(13),0,a.d(if(i==1)0 else 13))};row.addView(label(pair.first,14.5f),LinearLayout.LayoutParams(0,-2,1f));row.addView(label("${pair.second}",17f,a.fg,true));box.addView(row)
        }
        box.contentDescription="Прочитано книг: за месяц ${model.countMonth(YearMonth.from(today))}, за год ${model.countYear(today.year)}, за всё время ${model.finished.size}"
        if(model.undated.isNotEmpty()){space(box,10);box.addView(label("Без даты: ${model.undated.size}",12.5f))};add(box)
    }
    private fun readingTime() {
        val box=surface();header(box,"Время чтения","clock",violet);space(box,20)
        val values=a.column();fun bind(){values.removeAllViews();val total=model.total(period);val average=model.average(period)
            val row=if(compact)a.column() else a.line().apply {gravity=Gravity.TOP}
            fun metric(title:String,value:String):LinearLayout {val c=a.column();c.addView(label(value,30f,a.fg,true));space(c,4);c.addView(label(title,14.5f));return c}
            row.addView(metric("Всего за период",duration(total)),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));if(compact)space(row,14)
            row.addView(metric("В среднем в день",duration(average)),if(compact)LinearLayout.LayoutParams(-1,-2)else LinearLayout.LayoutParams(0,-2,1f));values.addView(row);space(values,22)
            val plot=model.entries.filter { it.first>=model.start(period) };val n=if(period==0)today.dayOfMonth else if(period==1)today.monthValue else minOf(24,max(1,java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(model.first ?: today),YearMonth.from(today)).toInt()+1))
            val series=when(period){0->(1..n).map { model.ms(today.withDayOfMonth(it))/60000.0 };1->(1..n).map { month->plot.filter {it.first.monthValue==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 };else->(n-1 downTo 0).map { offset->val month=YearMonth.from(today).minusMonths(offset.toLong());plot.filter {YearMonth.from(it.first)==month}.sumOf { max(0,it.second.optLong("ms")) }/60000.0 }}
            val chartLabels=when(period){0->(1..n).map{today.withDayOfMonth(it).format(DateTimeFormatter.ofPattern("d MMMM",ru))};1->(1..n).map{today.withMonth(it).withDayOfMonth(1).format(DateTimeFormatter.ofPattern("LLLL",ru))};else->(n-1 downTo 0).map{YearMonth.from(today).minusMonths(it.toLong()).atDay(1).format(DateTimeFormatter.ofPattern("LLL yyyy",ru))}}
            val hint=label("",14.5f,a.fg,true).apply{visibility=View.GONE}
            values.addView(TimeRibbon(a,series,animate,onSelect={i->hint.visibility=View.VISIBLE;hint.text="${chartLabels[i]} · ${duration((series[i]*60000).toLong())}"},accent=violet),LinearLayout.LayoutParams(-1,a.d(112)));space(values,10)
            val axis=a.line();listOf(chartLabels.first(),chartLabels[chartLabels.size/2],chartLabels.last()).forEachIndexed{i,t->axis.addView(label(t,12f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=when(i){0->Gravity.START;1->Gravity.CENTER;else->Gravity.END}},LinearLayout.LayoutParams(0,-2,1f))};values.addView(axis);space(values,8)
            values.addView(hint)
        }
        box.addView(segment(listOf("Месяц","Год","Всё время"),period){period=it;a.profileRange=it;bind()}.apply{minimumHeight=a.d(44)});space(box,22);box.addView(values);bind();add(box)
    }
    private fun favorite() {
        val box=surface();header(box,"Любимый автор","user",rose);space(box,22)
        val author=model.favorite
        if(author==null)box.addView(label("Появится, когда вы дочитаете книгу с указанным автором.",14.5f))
        else {
            box.addView(a.text(author.name,26f,a.fg,bold=true,serif=true).apply{cssLeading(1.15f)});space(box,6);box.addView(label("Прочитано: ${MainActivity.countBooks(author.count)}",14.5f,rose,true));space(box,20)
            val covers=a.line();author.books.take(5).forEachIndexed { i,b ->val cover=CoverView(a,b,a.cover(b)).apply {contentDescription=b.str("title");setOnClickListener{a.detail(b)}};covers.addView(cover,LinearLayout.LayoutParams(a.d(46),a.d(69)).apply {if(i>0)leftMargin=a.d(10)})};box.addView(covers)
        };add(box)
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
/** Thin rounded bars: muted for data, one solid accent bar for the selected (or latest) value. */
internal class TimeRibbon(a:MainActivity,private val values:List<Double>,animate:Boolean,private val speed:Int?=null,private val onSelect:((Int)->Unit)?=null,private val accent:Int?=null):StatsAnimatedView(a,animate){
    private var selected=-1
    private var touched=-1
    init{if(onSelect!=null){isClickable=true;isFocusable=true};contentDescription=if(speed==null)values.joinToString("; "){duration((it*60000).toLong())}else "Приблизительная скорость: $speed слов в минуту"}
    override fun onTouchEvent(e:MotionEvent):Boolean {if(onSelect==null)return super.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP){touched=(e.x/width*values.size).toInt().coerceIn(0,values.lastIndex);performClick()};return true}
    override fun performClick():Boolean {super.performClick();if(values.isNotEmpty()&&onSelect!=null){selected=if(touched>=0)touched else (selected+1)%values.size;touched=-1;onSelect.invoke(selected);invalidate()};return true}
    override fun onDraw(c:Canvas){if(values.isEmpty())return;val p=Paint(Paint.ANTI_ALIAS_FLAG);val cell=width.toFloat()/values.size;val maximum=max(1.0,values.maxOrNull() ?: 1.0)
        val ink=accent ?: if(a.light)0xff161617.toInt()else 0xfff3f2ef.toInt();val track=if(a.light)0x16161617 else 0x24ffffff;val data=if(a.light)0x59161617 else 0x73ffffff
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
