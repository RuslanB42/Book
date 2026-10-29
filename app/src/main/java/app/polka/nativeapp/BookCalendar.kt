package app.polka.nativeapp

import android.app.Dialog
import android.content.*
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import android.view.accessibility.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

internal data class CalendarPeriod(val first:LocalDate,val last:LocalDate) {
    init { require(first<=last && first.year>=1900 && last<=LocalDate.now()) }
    fun pages():List<List<YearMonth>> {val start=YearMonth.from(first);val n=ChronoUnit.MONTHS.between(start,YearMonth.from(last)).toInt()+1;return (0 until n).map{start.plusMonths(it.toLong())}.chunked(12)}
    fun contains(date:LocalDate)=date in first..last
}
internal fun MainActivity.openBookCalendar(data:JSONObject,month:YearMonth) {
    bookCalendar?.dismiss();BookCalendarDialog(this,data,month).also{bookCalendar=it;it.show()}
}
internal class BookCalendarDialog(private val a:MainActivity,data:JSONObject,initial:YearMonth):Dialog(a,R.style.PolkaSheetTheme) {
    private val model=ReadingStatistics(data,a.books)
    private val today=LocalDate.now()
    internal var period=CalendarPeriod(initial.atDay(1),minOf(initial.atEndOfMonth(),today));private set
    private var mode=0
    private var focusedMonth=initial
    internal var pageIndex=0;private set
    private lateinit var content:LinearLayout
    internal lateinit var poster:BookCalendarPoster;private set
    private val locale=Locale("ru")
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);content=a.column().apply{setBackgroundColor(a.bg)};setContentView(content)
        val w=window!!;ReaderWindow.configure(w,false,a.bg);w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(a.bg));w.setLayout(-1,-1);w.setGravity(Gravity.FILL)
        if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}
        SafeArea.bind(content){i->val s=SafeArea.content(i,android.os.Build.VERSION.SDK_INT>=30);content.setPadding(s.left,s.top,s.right,s.bottom)}
        rebuild()
    }
    override fun dismiss(){super.dismiss();if(a.bookCalendar===this)a.bookCalendar=null}
    internal fun choosePeriod(first:LocalDate,last:LocalDate){period=CalendarPeriod(first,last);focusedMonth=YearMonth.from(first);mode=2;pageIndex=0;rebuild()}
    internal fun selectMode(index:Int){when(index){0->{mode=0;val m=focusedMonth;period=CalendarPeriod(m.atDay(1),minOf(m.atEndOfMonth(),today));pageIndex=0;rebuild()};1->{mode=1;val y=focusedMonth.year;period=CalendarPeriod(LocalDate.of(y,1,1),minOf(LocalDate.of(y,12,31),today));pageIndex=0;rebuild()};else->rangeEditor()}}
    private fun rebuild(){content.removeAllViews()
        val toolbar=a.line().apply{setPadding(a.d(12),a.d(8),a.d(12),a.d(8))};toolbar.addView(a.icon("back","Закрыть календарь"){dismiss()},LinearLayout.LayoutParams(a.d(44),a.d(44)));toolbar.addView(a.text("Календарь",24f,bold=true),LinearLayout.LayoutParams(0,-2,1f));toolbar.addView(a.icon("download","Сохранить календарь в PNG"){export(false)},LinearLayout.LayoutParams(a.d(44),a.d(44)));toolbar.addView(a.icon("share","Поделиться календарём"){export(true)},LinearLayout.LayoutParams(a.d(44),a.d(44)));content.addView(toolbar)
        val controls=a.column().apply{setPadding(a.d(20),0,a.d(20),a.d(12))};controls.addView(a.segment(listOf("Месяц","Год","Период"),mode){selectMode(it)});val nav=a.line()
        val prev=a.icon("back","Предыдущий период"){shift(-1)}.apply{isEnabled=mode<2 && period.first.year>1900;alpha=if(isEnabled)1f else .25f};nav.addView(prev,LinearLayout.LayoutParams(a.d(44),a.d(44)))
        val format=if(mode==0)"LLLL yyyy" else  if(mode==1)"yyyy" else  "d MMM yyyy";val text=if(mode<2)period.first.format(DateTimeFormatter.ofPattern(format,locale)).replaceFirstChar{it.titlecase(locale)}else "${period.first.format(DateTimeFormatter.ofPattern(format,locale))} — ${period.last.format(DateTimeFormatter.ofPattern(format,locale))}"
        nav.addView(a.text(text,16f,bold=true).apply{gravity=Gravity.CENTER;minHeight=a.d(48);contentDescription="Выбрать период календаря";setOnClickListener{when(mode){0->a.monthEditor(YearMonth.from(period.first),today){m->focusedMonth=m;period=CalendarPeriod(m.atDay(1),minOf(m.atEndOfMonth(),today));pageIndex=0;rebuild()};1->yearEditor();else->rangeEditor()}}},LinearLayout.LayoutParams(0,-2,1f))
        val next=a.icon("back","Следующий период"){shift(1)}.apply{rotation=180f;isEnabled=mode<2 && period.last<today;alpha=if(isEnabled)1f else .25f};nav.addView(next,LinearLayout.LayoutParams(a.d(44),a.d(44)));controls.addView(nav);content.addView(controls)
        val pages=if(mode==1)listOf((1..12).map{YearMonth.of(period.first.year,it)})else period.pages();pageIndex=pageIndex.coerceIn(0,pages.lastIndex);poster=BookCalendarPoster(a,model,period,pages[pageIndex]).apply { onDay={date->dayBooks(date)};onMonth={month->focusedMonth=month;mode=0;period=CalendarPeriod(month.atDay(1),minOf(month.atEndOfMonth(),today));pageIndex=0;rebuild()} }
        val scroller=ScrollView(a).apply{isFillViewport=false;clipToPadding=false;setPadding(a.d(12),0,a.d(12),a.d(20));addView(FrameLayout(a).apply{addView(poster,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL))},android.view.ViewGroup.LayoutParams(-1,-2))};content.addView(scroller,LinearLayout.LayoutParams(-1,0,1f));if(a.prefs.str("motion")!="reduced" && android.animation.ValueAnimator.areAnimatorsEnabled()){poster.alpha=0f;poster.animate().alpha(1f).setDuration(180).start()}
        if(pages.size>1){val navPages=a.line();navPages.addView(a.button("Назад"){pageIndex--;rebuild()}.apply{isEnabled=pageIndex>0},LinearLayout.LayoutParams(0,-2,1f));navPages.addView(a.text("${pageIndex+1} / ${pages.size}",14f).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(a.d(64),-2));navPages.addView(a.button("Далее"){pageIndex++;rebuild()}.apply{isEnabled=pageIndex<pages.lastIndex},LinearLayout.LayoutParams(0,-2,1f));content.addView(navPages)}
    }
    private fun dayBooks(date:LocalDate){
        val books=model.on(date);val box=a.column()
        if(books.isEmpty())box.addView(a.text("Нет дочитанных книг",16f,a.muted))
        books.forEach{book->val row=a.line().apply{setPadding(0,a.d(8),0,a.d(8));minimumHeight=a.d(84)}
            row.addView(CoverView(a,book,a.cover(book)),LinearLayout.LayoutParams(a.d(44),a.d(66)))
            val info=a.column().apply{setPadding(a.d(12),0,0,0)};info.addView(a.text(book.str("title"),17f,bold=true));a.space(info,4);info.addView(a.text(book.optJSONArray("authors")?.strings()?.joinToString(", ").orEmpty(),14f,a.muted));row.addView(info,LinearLayout.LayoutParams(0,-2,1f));row.contentDescription=book.str("title");row.setOnClickListener{dismiss();a.detail(book)};box.addView(row)
        }
        a.dialog().setTitle(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",locale))).setView(ScrollView(a).apply{addView(box)}).setPositiveButton("Готово",null).show()
    }
    private fun shift(delta:Int){if(mode==0){val m=YearMonth.from(period.first).plusMonths(delta.toLong());if(m.year<1900||m>YearMonth.from(today))return;focusedMonth=m;period=CalendarPeriod(m.atDay(1),minOf(m.atEndOfMonth(),today))}else{val y=period.first.year+delta;if(y !in 1900..today.year)return;focusedMonth=minOf(YearMonth.of(y,focusedMonth.month),YearMonth.from(today));period=CalendarPeriod(LocalDate.of(y,1,1),minOf(LocalDate.of(y,12,31),today))};pageIndex=0;rebuild()}
    private fun yearEditor(){val picker=a.wheel(1900,today.year,period.first.year,"Год календаря");a.dialog().setTitle("Год").setView(picker).setPositiveButton("Показать"){_,_->focusedMonth=minOf(YearMonth.of(picker.value,focusedMonth.month),YearMonth.from(today));period=CalendarPeriod(LocalDate.of(picker.value,1,1),minOf(LocalDate.of(picker.value,12,31),today));pageIndex=0;rebuild()}.show()}
    private fun rangeEditor(){var first=period.first;var last=period.last;val box=a.column();val fmt=DateTimeFormatter.ofPattern("d MMM yyyy",locale);val start=a.button("С ${first.format(fmt)}"){};val end=a.button("По ${last.format(fmt)}"){};start.setOnClickListener{a.pickCalendarDate(first){first=it;start.text="С ${it.format(fmt)}"}};end.setOnClickListener{a.pickCalendarDate(last){last=it;end.text="По ${it.format(fmt)}"}};box.addView(start);a.space(box,8);box.addView(end);val error=a.text("",14f,0xffbd4444.toInt()).apply{visibility=View.GONE};box.addView(error);a.space(box,16);lateinit var sheet:android.app.AlertDialog;box.addView(a.button("Показать",true){if(first>last){error.text="Начало должно быть раньше конца";error.visibility=View.VISIBLE}else{choosePeriod(first,last);sheet.dismiss()}});sheet=a.dialog().setTitle("Период календаря").setView(box).show()}
    internal fun image():Bitmap=poster.image()
    private var exporting=false
    private fun export(share:Boolean){if(exporting)return;exporting=true;val selected=poster;val name="Polka-${period.first}-${period.last}-${pageIndex+1}.png";selected.ready{if(isShowing)writeExport(selected,name,share)else exporting=false}}
    private fun writeExport(selected:BookCalendarPoster,name:String,share:Boolean){val bitmap=selected.image();val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,name)
        a.work({try{file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}},failed={exporting=false}){exporting=false;if(share){val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file);a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Книжный календарь",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться календарём"))}else{a.pendingCalendarImage=file;a.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="image/png";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,file.name)},17)}}
    }
}
internal fun MainActivity.pickCalendarDate(initial:LocalDate,save:(LocalDate)->Unit){val today=LocalDate.now();val box=column();val labels=line();listOf("День","Месяц","Год").forEach{labels.addView(text(it,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))};box.addView(labels);val row=line();val day=wheel(1,31,initial.dayOfMonth,"День периода");val month=wheel(1,12,initial.monthValue,"Месяц периода");val year=wheel(1900,today.year,initial.year,"Год периода");fun limit(){month.maxValue=if(year.value==today.year)today.monthValue else 12;val ym=YearMonth.of(year.value,month.value);day.maxValue=if(ym==YearMonth.from(today))today.dayOfMonth else ym.lengthOfMonth()};month.setOnValueChangedListener{_,_,_->limit()};year.setOnValueChangedListener{_,_,_->limit()};limit();listOf(day,month,year).forEach{row.addView(it,LinearLayout.LayoutParams(0,d(144),1f))};box.addView(row);dialog().setTitle("Дата").setView(box).setPositiveButton("Выбрать"){_,_->save(LocalDate.of(year.value,month.value,day.value))}.show()}

/** Shared layout geometry for preview, hit targets and PNG. A year is a bookshelf, never 12 tiny calendars. */
internal class BookCalendarPoster(private val a:MainActivity,private val model:ReadingStatistics,private val period:CalendarPeriod,internal val months:List<YearMonth>):View(a) {
    private val ru=Locale("ru")
    internal val monthly=months.size==1
    private val pad=40f
    private val gap=10f
    private val cellW=(1080f-pad*2-gap*6)/7
    private val cellH=242f
    private val gridTop=270f
    internal val weekRows=if(monthly)(months.first().atDay(1).dayOfWeek.value-1+months.first().lengthOfMonth()+6)/7 else 0
    internal val naturalHeight=if(monthly)(gridTop+weekRows*(cellH+gap)+88).roundToInt() else  (244+months.size*136+88)
    private val first=maxOf(period.first,months.first().atDay(1))
    private val last=minOf(period.last,months.last().atEndOfMonth())
    internal val completed=model.finished.filter{it.date?.let{date->date in first..last}==true}.sortedWith(compareBy<ReadingStatistics.Finished>{it.date}.thenBy{it.book.str("id")})
    private val byDay=completed.groupBy{it.date!!}
    internal val byMonth=months.associateWith{month->completed.filter{YearMonth.from(it.date)==month}.map{it.book}}
    internal val caption:String get()=if(monthly && (first.dayOfMonth>1 || last<minOf(months.first().atEndOfMonth(),model.today)))"${first.dayOfMonth}–${last.format(DateTimeFormatter.ofPattern("d MMM yyyy",ru))}" else if(months.first().year==months.last().year)"${months.first().year}" else "${months.first().year} — ${months.last().year}"
    private val artBooks by lazy { if(monthly)byDay.values.flatMap{it.take(2).map{entry->entry.book}} else byMonth.values.flatMap{it.take(12)} }
    private val art=CoverView(a,JSONObject())
    private val images=mutableMapOf<String,Bitmap>()
    private val requested=mutableSetOf<String>()
    internal var onDay:((LocalDate)->Unit)?=null
    internal var onMonth:((YearMonth)->Unit)?=null
    private val hitDays=mutableMapOf<LocalDate,RectF>()
    private val hitMonths=mutableMapOf<YearMonth,RectF>()
    private fun remember(book:JSONObject,bitmap:Bitmap){val maxWidth=if(monthly)240 else 150;val scale=minOf(1f,maxWidth.toFloat()/bitmap.width);images[book.str("id")]=if(scale<1f)Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(1),(bitmap.height*scale).roundToInt().coerceAtLeast(1),true)else bitmap}
    internal fun ready(callback:()->Unit){val missing=artBooks.filter{!it.optJSONObject("cover")?.str("__polkaBlob").isNullOrBlank() && images[it.str("id")]==null};if(missing.isEmpty()){callback();return};var left=missing.size;missing.forEach{b->a.requestCover(b){bitmap->if(bitmap!=null)remember(b,bitmap);left--;invalidate();if(left==0)callback()}}}
    init{artBooks.forEach{b->a.cover(b)?.let{remember(b,it)}};contentDescription="${if(monthly)"Календарь" else "Книги по месяцам"} с $first по $last: ${MainActivity.countBooks(completed.size)}";isClickable=true;isFocusable=true;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    // Full width at phone scale. Landscape/tablets remain bounded; tall content scrolls, never shrinks into illegibility.
    override fun onMeasure(w:Int,h:Int){val width=minOf(MeasureSpec.getSize(w).coerceAtLeast(1),a.d(640));setMeasuredDimension(width,(width*naturalHeight/1080f).roundToInt())}
    internal fun image():Bitmap {val result=Bitmap.createBitmap(2160,naturalHeight*2,Bitmap.Config.ARGB_8888);val canvas=Canvas(result);canvas.scale(2f,2f);paintPoster(canvas);return result}
    override fun onDraw(canvas:Canvas){canvas.save();canvas.scale(width/1080f,width/1080f);paintPoster(canvas);canvas.restore()}
    internal fun dayBounds(date:LocalDate)=hitDays[date]?.let{RectF(it)}
    internal fun monthBounds(month:YearMonth)=hitMonths[month]?.let{RectF(it)}
    private var accessibilityFocus=View.NO_ID
    private val nodeProvider=object:AccessibilityNodeProvider(){
        override fun createAccessibilityNodeInfo(id:Int):AccessibilityNodeInfo? {
            if(id==HOST_VIEW_ID)return AccessibilityNodeInfo.obtain(this@BookCalendarPoster).apply{onInitializeAccessibilityNodeInfo(this);hitDays.keys.forEach{addChild(this@BookCalendarPoster,it.dayOfMonth)};hitMonths.keys.forEachIndexed{i,_->addChild(this@BookCalendarPoster,100+i)}}
            val day=hitDays.entries.firstOrNull{it.key.dayOfMonth==id};val month=if(id>=100)hitMonths.entries.toList().getOrNull(id-100) else null
            val rect=day?.value ?: month?.value ?: return null
            return AccessibilityNodeInfo.obtain().apply{
                setSource(this@BookCalendarPoster,id);setParent(this@BookCalendarPoster);packageName=a.packageName;className="android.widget.Button";isEnabled=true;isClickable=true;isFocusable=true;isAccessibilityFocused=accessibilityFocus==id
                contentDescription=if(day!=null)"${day.key.format(DateTimeFormatter.ofPattern("d MMMM yyyy",ru))}: ${MainActivity.countBooks(byDay[day.key].orEmpty().size)}" else "${month!!.key.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",ru))}: ${MainActivity.countBooks(byMonth[month.key].orEmpty().size)}"
                val scale=width/1080f;val bounds=Rect((rect.left*scale).toInt(),(rect.top*scale).toInt(),ceil(rect.right*scale).toInt(),ceil(rect.bottom*scale).toInt());setBoundsInParent(bounds);val xy=IntArray(2);getLocationOnScreen(xy);bounds.offset(xy[0],xy[1]);setBoundsInScreen(bounds);val visible=Rect();isVisibleToUser=getGlobalVisibleRect(visible)&&Rect.intersects(visible,bounds)
                addAction(AccessibilityNodeInfo.ACTION_CLICK);addAction(if(isAccessibilityFocused)AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN)
            }
        }
        override fun performAction(id:Int,action:Int,args:Bundle?):Boolean {
            val day=hitDays.entries.firstOrNull{it.key.dayOfMonth==id};val month=if(id>=100)hitMonths.entries.toList().getOrNull(id-100) else null
            if(day==null && month==null)return false
            when(action){AccessibilityNodeInfo.ACTION_CLICK->{day?.let{onDay?.invoke(it.key)};month?.let{onMonth?.invoke(it.key)};return true}
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS->{accessibilityFocus=if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)id else View.NO_ID;val event=AccessibilityEvent.obtain(if(accessibilityFocus==id)AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED else AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);event.setSource(this@BookCalendarPoster,id);event.packageName=a.packageName;parent?.requestSendAccessibilityEvent(this@BookCalendarPoster,event);return true}
                android.R.id.accessibilityActionShowOnScreen->{val r=day?.value ?: month!!.value;val scale=width/1080f;requestRectangleOnScreen(Rect((r.left*scale).toInt(),(r.top*scale).toInt(),(r.right*scale).toInt(),(r.bottom*scale).toInt()),true);return true}
            };return false
        }
    }
    override fun getAccessibilityNodeProvider():AccessibilityNodeProvider=nodeProvider
    private var hovered=View.NO_ID
    override fun dispatchHoverEvent(event:MotionEvent):Boolean {
        val manager=a.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if(!manager.isTouchExplorationEnabled)return super.dispatchHoverEvent(event)
        val x=event.x*1080/width;val y=event.y*1080/width
        val next=if(event.actionMasked==MotionEvent.ACTION_HOVER_EXIT)View.NO_ID else hitDays.entries.firstOrNull{it.value.contains(x,y)}?.key?.dayOfMonth ?: hitMonths.entries.indexOfFirst{it.value.contains(x,y)}.let{if(it<0)View.NO_ID else 100+it}
        if(next!=hovered){fun send(id:Int,type:Int){if(id!=View.NO_ID){val e=AccessibilityEvent.obtain(type);e.setSource(this,id);e.packageName=a.packageName;parent?.requestSendAccessibilityEvent(this,e)}};send(next,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);send(hovered,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);hovered=next};return next!=View.NO_ID
    }
    private var downX=0f;private var downY=0f
    override fun onTouchEvent(event:MotionEvent):Boolean {when(event.actionMasked){MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;return true};MotionEvent.ACTION_UP->{if(abs(event.x-downX)+abs(event.y-downY)<a.d(12)){val x=event.x*1080/width;val y=event.y*1080/width;hitDays.entries.firstOrNull{it.value.contains(x,y)}?.let{onDay?.invoke(it.key)};hitMonths.entries.firstOrNull{it.value.contains(x,y)}?.let{onMonth?.invoke(it.key)};performClick()};return true};MotionEvent.ACTION_CANCEL->return true};return true}
    override fun performClick():Boolean {super.performClick();return true}
    private fun paintPoster(c:Canvas){
        hitDays.clear();hitMonths.clear()
        val p=Paint(3);val fg=if(a.light)0xff172c43.toInt() else 0xfff0f5fc.toInt();val secondary=if(a.light)0xff4f637a.toInt() else 0xffb2c4d8.toInt();val blue=StatisticsPalette.tone(a.light,statBlue).accent
        val surface=if(a.light)0xfff1f6fd.toInt() else 0xff162a40.toInt();val empty=if(a.light)0xffffffff.toInt() else 0xff1c3249.toInt();val active=if(a.light)0xffd4e9ff.toInt() else 0xff285580.toInt();val inactive=if(a.light)0xffa0adbd.toInt() else 0xff667e94.toInt()
        p.shader=LinearGradient(0f,0f,1080f,naturalHeight.toFloat(),if(a.light)intArrayOf(0xfff2f7fe.toInt(),0xffe9f1fb.toInt()) else intArrayOf(0xff172e48.toInt(),0xff112236.toInt()),null,Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,1080f,naturalHeight.toFloat(),40f,40f,p);p.shader=null
        fun text(s:String,x:Float,y:Float,size:Float,color:Int=fg,bold:Boolean=false,center:Boolean=false,serif:Boolean=false,right:Boolean=false){p.color=color;p.textSize=size;p.textAlign=if(right)Paint.Align.RIGHT else if(center)Paint.Align.CENTER else Paint.Align.LEFT;p.typeface=if(serif)PolkaFonts.serif(a,true) else Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL);c.drawText(s,x,y,p)}
        fun cover(book:JSONObject,r:RectF){val id=book.str("id");if(book.optJSONObject("cover")!=null && images[id]==null && requested.add(id))a.requestCover(book){bmp->if(bmp!=null)remember(book,bmp);invalidate()};art.drawCover(c,r,book,images[id] ?: a.cover(book))}
        val title=if(monthly)months.first().atDay(1).format(DateTimeFormatter.ofPattern("LLLL",ru)).replaceFirstChar{it.titlecase(ru)} else if(months.size==12 && months.first().year==months.last().year)"Книжный год" else "Мои книги"
        text(title,pad,106f,if(monthly)68f else 62f,serif=true)
        val subtitle=caption
        text(subtitle,pad,162f,40f,secondary)
        // Place totals in a distinct right column, never over the title.
        text("${completed.size}",1040f,108f,if(completed.size>999)52f else 76f,blue,true,right=true);text(MainActivity.countBooks(completed.size).substringAfter(" "),1040f,156f,32f,secondary,right=true)
        if(!monthly && !(first.dayOfYear==1 && months.size==12))text("${first.format(DateTimeFormatter.ofPattern("d MMM",ru))} — ${last.format(DateTimeFormatter.ofPattern("d MMM",ru))}",pad,214f,34f,secondary)
        if(monthly){
            val month=months.first();listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed{i,s->text(s,pad+(cellW+gap)*i+cellW/2,236f,40f,secondary,center=true)}
            val offset=month.atDay(1).dayOfWeek.value-1
            for(day in 1..month.lengthOfMonth()){
                val date=month.atDay(day);val i=day+offset-1;val left=pad+(i%7)*(cellW+gap);val top=gridTop+(i/7)*(cellH+gap);val enabled=date in first..last;val books=byDay[date].orEmpty().map{it.book};val rect=RectF(left,top,left+cellW,top+cellH)
                p.color=if(books.isNotEmpty())active else empty;p.alpha=if(enabled)255 else 100;c.drawRoundRect(rect,18f,18f,p);p.alpha=255
                if(enabled)hitDays[date]=rect
                text("$day",left+14,top+40,40f,if(enabled)fg else inactive,books.isNotEmpty())
                if(date==model.today){p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=blue;c.drawRoundRect(RectF(left+1.5f,top+1.5f,left+cellW-1.5f,top+cellH-1.5f),18f,18f,p);p.style=Paint.Style.FILL}
                if(books.isNotEmpty()){
                    val ch=174f;val cw=ch*2/3;val cx=left+cellW/2
                    // Real multiple completions: stacked covers plus an exact count, no books silently lost.
                    if(books.size>1){cover(books[1],RectF(cx-cw/2+6,top+60,cx+cw/2+6,top+60+ch))}
                    cover(books[0],RectF(cx-cw/2-2,top+54,cx+cw/2-2,top+54+ch))
                    if(books.size>1){p.color=if(a.light)0xff1856b5.toInt() else 0xff8dcbff.toInt();c.drawRoundRect(left+cellW-62,top+8,left+cellW-6,top+43,12f,12f,p);text(if(books.size>99)"99+" else "+${books.size-1}",left+cellW-34,top+34,30f,if(a.light)Color.WHITE else 0xff10263b.toInt(),true,true)}
                }
            }
        }else{
            val maxCount=byMonth.values.maxOfOrNull{it.size}?.coerceAtLeast(1) ?: 1
            months.forEachIndexed{index,month->
                val top=244f+index*136;val books=byMonth[month].orEmpty();val enabled=month.atDay(1)<=last;if(enabled)hitMonths[month]=RectF(pad,top,1040f,top+124)
                text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLL",ru)).replace(".","").replaceFirstChar{it.titlecase(ru)},pad,top+68,40f,if(enabled)fg else inactive,true)
                val x=174f;val available=776f;val count=books.size
                if(count>0){
                    val n=minOf(count,12);val barW=maxOf(100f,available*count/maxCount).coerceAtMost(available)
                    p.color=active;c.drawRoundRect(x,top+4,x+barW,top+120,18f,18f,p)
                    val step=if(n<=1)0f else minOf(72f,(barW-88)/(n-1));val cw=68f;val ch=102f
                    val coverStart=x+barW-10-cw-(n-1)*step
                    repeat(n){k->cover(books[k],RectF(coverStart+k*step,top+11,coverStart+k*step+cw,top+11+ch))}
                    if(count>12){p.color=if(a.light)0xff1856b5.toInt() else 0xff8dcbff.toInt();c.drawRoundRect(x+barW-66,top+78,x+barW-4,top+119,12f,12f,p);text("+${count-12}",x+barW-35,top+108,26f,if(a.light)Color.WHITE else 0xff10263b.toInt(),true,true)}
                }else{p.color=if(enabled)empty else surface;c.drawRoundRect(x,top+59,x+available,top+63,2f,2f,p)}
                text("$count",1040f,top+70,if(count>999)30f else 40f,if(count>0)blue else inactive,true,right=true)
            }
        }
        text("POLKA",pad,naturalHeight-32f,28f,secondary,true)
    }
}
