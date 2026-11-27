package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.*

/**
 * «Настройки» as its own page (approved «Настройки А»): quiet group captions, the appearance as three page tiles, and
 * every other group one card of 56 dp rows (names 16 sp, values grey with a chevron, switches 52 x 32 dp), split by thin
 * lines. Only grey and white.
 */
fun MainActivity.settings(){settingsPage?.let{return};openSettingsPage(true)}

internal fun MainActivity.openSettingsPage(animate:Boolean){
    val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false;setBackgroundColor(bg);elevation=d(24).toFloat();isClickable=true}
    val content=column();scroll.addView(content)
    val extra=max(0,(resources.configuration.screenWidthDp-640)/2)
    followInsets(content){i->content.setPadding(d(16+extra)+i.left,i.top+d(8),d(16+extra)+i.right,d(32)+i.bottom)}
    root.addView(scroll,FrameLayout.LayoutParams(-1,-1));settingsPage=scroll
    val muted2=if(light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
    fun caption(title:String,top:Int=28){content.addView(text(title,13f,muted2,true).apply{setPadding(d(4),0,0,0)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(top);bottomMargin=d(10)})}
    fun lit(top:Boolean,bottom:Boolean)=android.graphics.drawable.StateListDrawable().apply{setExitFadeDuration(220)
        val r=d(24).toFloat();val t=if(top)r else 0f;val b=if(bottom)r else 0f
        addState(intArrayOf(android.R.attr.state_pressed),android.graphics.drawable.GradientDrawable().apply{setColor(if(light)0x0c000000 else 0x0fffffff);cornerRadii=floatArrayOf(t,t,t,t,b,b,b,b)})
        addState(intArrayOf(),android.graphics.drawable.ColorDrawable(0))}
    // a group is one card, its rows split by thin lines (approved «Настройки А»); a row lights up under the finger
    fun card(rows:List<View>){val box=column().apply{floatCard(this,24)}
        rows.forEachIndexed{i,r->if(r.isClickable)r.background=lit(i==0,i==rows.lastIndex);if(i>0)box.addView(View(this).apply{setBackgroundColor(if(light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1).apply{leftMargin=d(18)})
            box.addView(r,LinearLayout.LayoutParams(-1,-2).apply{if(r is LayoutToggle)height=d(56)})}
        content.addView(box,LinearLayout.LayoutParams(-1,-2))}
    fun row(title:String,sub:String?=null,value:String?=null,chevron:Boolean=true,click:(()->Unit)?=null):Triple<LinearLayout,TextView?,TextView?>{
        val r=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(if(sub!=null)64 else 56);setPadding(d(18),d(10),d(16),d(10))}
        if(click!=null)r.apply{isClickable=true;isFocusable=true;setOnClickListener{click()}}
        val names=column();names.addView(text(title,16f,fg))
        val subView=sub?.let{text(it,13f,muted2).apply{setPadding(0,d(3),0,0)}.also{v->names.addView(v)}}
        r.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        val valueView=value?.let{text(it,15f,muted2).also{v->r.addView(v,LinearLayout.LayoutParams(-2,-2).apply{if(chevron)rightMargin=d(10)})}}
        if(chevron)r.addView(Chevron(this),LinearLayout.LayoutParams(d(10),d(16)))
        return Triple(r,subView,valueView)}
    // head: back, and the large serif title centred on the page (an empty place the back button's size on the right
    // keeps it in the middle), as the greeting on the statistics
    val head=line().apply{gravity=Gravity.CENTER_VERTICAL}
    head.addView(icon("back","Назад"){closeSettings()},LinearLayout.LayoutParams(d(46),d(46)))
    head.addView(text("Настройки",26f,fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=1},LinearLayout.LayoutParams(0,-2,1f))
    head.addView(View(this),LinearLayout.LayoutParams(d(46),d(46)))
    content.addView(head,LinearLayout.LayoutParams(-1,-2))
    // appearance
    caption("Оформление",18)
    val themes=listOf("dark" to "Тёмное","light" to "Светлое","system" to "Как в системе")
    val current=prefs.str("ui",prefs.str("theme","dark")).let{if(it in themes.map{t->t.first})it else "dark"}
    val tiles=line()
    themes.forEachIndexed{i,(key,name)->
        val col=column().apply{gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;isFocusable=true;contentDescription="Оформление: $name"}
        col.addView(ThemePreview(this,key,key==current),LinearLayout.LayoutParams(-1,d(84)))
        col.addView(text(name,12.5f,if(key==current)fg else muted2,key==current).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(8)})
        col.setOnClickListener{if(key!=current){prefs.put("ui",key);savePreferences()
            // the page is rebuilt in the new colours right where it was, without an entrance
            val y=scroll.scrollY;themeCrossfade{settingsPage=null;root.removeView(scroll);render(true,false);if(profileOverlay!=null)refreshProfile();openSettingsPage(false);settingsPage?.let{p->(p as ScrollView).scrollTo(0,y);p.post{p.scrollTo(0,y)}}}}}
        press(col);tiles.addView(col,LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)leftMargin=d(10)})}
    content.addView(tiles)
    // reading (filled in once the statistics are loaded)
    caption("Чтение")
    val year=LocalDate.now().year
    var stats=JSONObject()
    lateinit var flame:LinearLayout;lateinit var goal:LinearLayout;lateinit var flameValue:TextView;lateinit var goalValue:TextView
    fun refreshPlaques(){val model=ReadingStatistics(stats,books);val m=stats.optInt("goal",10);val t=model.target(year)
        flameValue.text="$m мин в день";flame.contentDescription="Огонёк: $m минут в день"
        goalValue.text=if(t>0)MainActivity.countBooks(t) else "не задана";goal.contentDescription="Цель на $year: "+goalValue.text}
    row("Огонёк",value="…"){numberSheet("Огонёк",stats.optInt("goal",10),1..240,listOf(10,15,20,30,45,60),{n->"минут в день, чтобы огонёк горел"}){v->stats.put("goal",v);updateStatistics{it.put("goal",v)};refreshPlaques()}}
        .let{flame=it.first;flameValue=it.third!!}
    row("Цель на $year",value="…"){val model=ReadingStatistics(stats,books);val cur=model.target(year)
        fun store(n:Int?){(stats.optJSONObject("annualGoals")?:JSONObject().also{stats.put("annualGoals",it)}).apply{if(n==null)remove("$year") else put("$year",n)};updateStatistics{changeAnnualGoal(it,year,n)};refreshPlaques()}
        numberSheet("Цель на $year",cur,1..9999,listOf(12,24,36,52),{n->if(n<=0)"книг за год" else MainActivity.countBooks(n).substringAfter(' ')+" за год · примерно ${max(1,(n/12f).roundToInt())} в месяц"},if(cur>0)("Убрать цель" to {store(null)}) else null){n->store(n)}}
        .let{goal=it.first;goalValue=it.third!!}
    card(listOf(flame,goal))
    work({stats=library.dao.meta("stats")?.json?.let{JSONObject(it).optJSONObject("data")}?:JSONObject()}){refreshPlaques()}
    // behaviour
    caption("Поведение")
    fun sw(title:String,on:Boolean,change:(Boolean)->Unit)=LayoutToggle(this,title,on,plain=true,change=change)
    card(listOf(sw("Вибрация",prefs.optBoolean("haptics",true)){on->prefs.put("haptics",on);savePreferences()},
        sw("Меньше движения",prefs.str("motion")=="reduced"){on->prefs.put("motion",if(on)"reduced" else "full");savePreferences()}))
    // backups
    caption("Резервные копии")
    val time=getSharedPreferences("health",0).getLong("exportedAt",0)
    val last=if(time==0L)"копий ещё не было" else "последняя копия "+java.text.SimpleDateFormat("d MMMM, HH:mm",java.util.Locale("ru")).format(java.util.Date(time))
    card(listOf(row("Создать копию"){exportBackup()}.first,row("Восстановить из файла"){pickBackup()}.first,
        row("Точки восстановления",sub=last){localRecovery()}.first))
    // where the reading tools come from
    caption("О приложении")
    card(listOf(row("Версия",value=packageManager.getPackageInfo(packageName,0).versionName,chevron=false).first))
    content.addView(text("Перевод: Google Переводчик (на устройстве). Словарь: Викисловарь, CC BY-SA 4.0. Фоновые звуки: свободная лицензия CC0. Иконки: Lucide, ISC.",12.5f,muted).apply{setPadding(d(6),d(10),d(6),0);cssLeading(1.4f)})
    if(animate)Depth(this,scroll).enter()
}
/** The whole window changes colours under a soft crossfade from a picture of how it looked a moment ago. */
internal fun MainActivity.themeCrossfade(change:()->Unit){
    val decor=window.decorView as? FrameLayout
    if(decor==null||prefs.str("motion")=="reduced"||decor.width<=0||decor.height<=0){change();return}
    val pic=runCatching{android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888).also{decor.draw(android.graphics.Canvas(it))}}.getOrNull()
    if(pic==null){change();return}
    val cover=ImageView(this).apply{setImageBitmap(pic);scaleType=ImageView.ScaleType.FIT_XY;isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;elevation=d(60).toFloat()}
    decor.addView(cover,FrameLayout.LayoutParams(-1,-1))
    change()
    cover.animate().alpha(0f).setStartDelay(60).setDuration(MotionSpec.PAGE_IN).setInterpolator(MotionSpec.move).withEndAction{decor.removeView(cover);cover.setImageDrawable(null);pic.recycle()}.start()
}

internal fun MainActivity.closeSettings(){val page=settingsPage?:return;settingsPage=null
    Depth(this,page).leave{root.removeView(page)}}

/** A number typed in, with quick choices: a big serif field in the middle, the unit under it, presets as capsules, «Готово».
 *  [extra] adds a quiet action under the button (e.g. removing the goal). */
internal fun MainActivity.numberSheet(title:String,initial:Int,range:IntRange,presets:List<Int>,unit:(Int)->String,extra:Pair<String,()->Unit>?=null,saved:(Int)->Unit){
    val box=column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    val pill=if(light)0x0e000000 else 0x12ffffff
    box.addView(text(title,15f,fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(4)})
    val field=edit(if(initial>0)"$initial" else "","0").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(range.last.toString().length))
        textSize=56f;typeface=PolkaFonts.serif(this@numberSheet,true);gravity=Gravity.CENTER;background=null;setTextColor(fg);setHintTextColor(if(light)0x33000000 else 0x33ffffff);setPadding(0,0,0,0);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;setSelection(text.length);contentDescription=title}
    box.addView(field,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(14)})
    val caption=text(unit(initial),14f,muted).apply{gravity=Gravity.CENTER}
    box.addView(caption,LinearLayout.LayoutParams(-1,-2))
    val chips=line()
    val views=presets.map{n->text("$n",14f,fg,true).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;contentDescription="$n";press(this)}}
    fun paint(){val cur=field.text.toString().toIntOrNull();views.forEachIndexed{i,v->val on=presets[i]==cur;v.background=shape(if(on)fg else pill,19);v.setTextColor(if(on)bg else fg)}}
    views.forEachIndexed{i,v->v.setOnClickListener{field.setText("${presets[i]}");field.setSelection(field.length())};chips.addView(v,LinearLayout.LayoutParams(0,d(38),1f).apply{if(i>0)leftMargin=d(8)})}
    box.addView(chips,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(20)})
    field.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
        override fun afterTextChanged(s:android.text.Editable?){caption.text=unit(s.toString().toIntOrNull()?:0);paint()}})
    paint()
    lateinit var sheet:android.app.AlertDialog
    fun done(){val n=field.text.toString().toIntOrNull();if(n==null||n !in range){field.animate().translationX(d(8).toFloat()).setDuration(60).withEndAction{field.animate().translationX(0f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()}.start();return}
        saved(n);sheet.dismiss()}
    field.setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){done();true}else false}
    box.addView(text("Готово",16f,bg,true).apply{gravity=Gravity.CENTER;background=shape(fg,26);isClickable=true;isFocusable=true;press(this);setOnClickListener{done()}},LinearLayout.LayoutParams(-1,d(52)).apply{topMargin=d(18)})
    extra?.let{(label,fn)->box.addView(text(label,14f,muted).apply{gravity=Gravity.CENTER;setPadding(0,d(14),0,d(2));isClickable=true;setOnClickListener{fn();sheet.dismiss()}},LinearLayout.LayoutParams(-1,-2))}
    sheet=dialog().setBare().setView(box).show()
    field.post{field.requestFocus();(getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field,0)}
}
