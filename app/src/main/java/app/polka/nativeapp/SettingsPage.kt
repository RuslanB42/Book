package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.*

/**
 * «Настройки» as its own page (approved design): quiet group captions, the appearance as three page tiles, «Огонёк»
 * and «Цель» as two small plaques like the statistics cards, behaviour as two switch capsules, backups as two
 * buttons and a capsule. Only grey and white, except the flame and the green road.
 */
fun MainActivity.settings(){settingsPage?.let{return};openSettingsPage(true)}

internal fun MainActivity.openSettingsPage(animate:Boolean){
    val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false;setBackgroundColor(bg);elevation=d(24).toFloat();isClickable=true}
    val content=column();scroll.addView(content)
    val extra=max(0,(resources.configuration.screenWidthDp-640)/2)
    followInsets(content){i->content.setPadding(d(20+extra)+i.left,i.top+d(8),d(20+extra)+i.right,d(32)+i.bottom)}
    root.addView(scroll,FrameLayout.LayoutParams(-1,-1));settingsPage=scroll
    val muted2=if(light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
    val pill=if(light)0x0e000000 else 0x12ffffff
    fun group(title:String,top:Int=26){content.addView(text(title,13f,muted2).apply{setPadding(d(4),0,0,0)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(top);bottomMargin=d(10)})}
    // head: back, then a large serif title
    // head: the title stands right beside the back button, on one line
    val head=line().apply{gravity=Gravity.CENTER_VERTICAL}
    head.addView(icon("back","Назад"){closeSettings()},LinearLayout.LayoutParams(d(44),d(44)))
    head.addView(text("Настройки",26f,fg,true,serif=true).apply{setPadding(d(6),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
    content.addView(head,LinearLayout.LayoutParams(-1,-2))
    // appearance
    group("Оформление",20)
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
    // reading: two value capsules, the same height as the switches (filled in once the statistics are loaded)
    group("Чтение")
    val year=LocalDate.now().year
    val capInk=if(light)0xff3a3a3e.toInt() else 0xffcfcfd4.toInt()
    fun valueRow(title:String):Pair<LinearLayout,TextView>{val row=line().apply{gravity=Gravity.CENTER_VERTICAL;background=shape(pill,24);setPadding(d(18),0,d(16),0);isClickable=true;isFocusable=true;press(this)}
        row.addView(text(title,14f,capInk),LinearLayout.LayoutParams(0,-2,1f))
        val v=text("…",14f,fg,true);row.addView(v,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=d(10)})
        row.addView(Chevron(this),LinearLayout.LayoutParams(d(10),d(16)));return row to v}
    val (flame,flameValue)=valueRow("Огонёк");val (goal,goalValue)=valueRow("Цель на $year")
    content.addView(flame,LinearLayout.LayoutParams(-1,d(48)));content.addView(goal,LinearLayout.LayoutParams(-1,d(48)).apply{topMargin=d(10)})
    var stats=JSONObject()
    fun refreshPlaques(){val model=ReadingStatistics(stats,books);val m=stats.optInt("goal",10);val t=model.target(year)
        flameValue.text="$m мин в день";flame.contentDescription="Огонёк: $m минут в день"
        goalValue.text=if(t>0)MainActivity.countBooks(t) else "не задана";goal.contentDescription="Цель на $year: "+goalValue.text}
    work({stats=library.dao.meta("stats")?.json?.let{JSONObject(it).optJSONObject("data")}?:JSONObject()}){refreshPlaques()}
    flame.setOnClickListener{numberSheet("Огонёк",stats.optInt("goal",10),1..240,listOf(10,15,20,30,45,60),{n->"минут в день, чтобы огонёк горел"}){v->stats.put("goal",v);updateStatistics{it.put("goal",v)};refreshPlaques()}}
    goal.setOnClickListener{val model=ReadingStatistics(stats,books);val cur=model.target(year)
        fun store(n:Int?){(stats.optJSONObject("annualGoals")?:JSONObject().also{stats.put("annualGoals",it)}).apply{if(n==null)remove("$year") else put("$year",n)};updateStatistics{changeAnnualGoal(it,year,n)};refreshPlaques()}
        numberSheet("Цель на $year",cur,1..9999,listOf(12,24,36,52),{n->if(n<=0)"книг за год" else MainActivity.countBooks(n).substringAfter(' ')+" за год · примерно ${max(1,(n/12f).roundToInt())} в месяц"},if(cur>0)("Убрать цель" to {store(null)}) else null){n->store(n)}}
    // behaviour
    group("Поведение")
    fun sw(title:String,on:Boolean,change:(Boolean)->Unit)=LayoutToggle(this,title,on,change)
    content.addView(sw("Вибрация",prefs.optBoolean("haptics",true)){on->prefs.put("haptics",on);savePreferences()},LinearLayout.LayoutParams(-1,d(48)))
    content.addView(sw("Меньше движения",prefs.str("motion")=="reduced"){on->prefs.put("motion",if(on)"reduced" else "full");savePreferences()},LinearLayout.LayoutParams(-1,d(48)).apply{topMargin=d(10)})
    // backups
    group("Резервные копии")
    val buttons=line()
    fun capsule(label:String,primary:Boolean,click:()->Unit)=text(label,15f,if(primary)bg else fg,true).apply{gravity=Gravity.CENTER;background=shape(if(primary)fg else pill,25);isClickable=true;isFocusable=true;setOnClickListener{click()};press(this)}
    buttons.addView(capsule("Создать копию",true){exportBackup()},LinearLayout.LayoutParams(0,d(50),1f))
    buttons.addView(capsule("Восстановить",false){pickBackup()},LinearLayout.LayoutParams(0,d(50),1f).apply{leftMargin=d(10)})
    content.addView(buttons)
    val time=getSharedPreferences("health",0).getLong("exportedAt",0)
    val points=line().apply{gravity=Gravity.CENTER_VERTICAL;background=shape(pill,18);setPadding(d(18),d(8),d(16),d(8));isClickable=true;isFocusable=true;contentDescription="Точки восстановления";setOnClickListener{localRecovery()};press(this)}
    val texts=column();texts.addView(text("Точки восстановления",14f,fg))
    texts.addView(text("последняя копия — "+(if(time==0L)"ещё не было" else java.text.SimpleDateFormat("d MMM, HH:mm",java.util.Locale("ru")).format(java.util.Date(time))),11.5f,muted2).apply{setPadding(0,d(2),0,0)})
    points.addView(texts,LinearLayout.LayoutParams(0,-2,1f));points.addView(Chevron(this),LinearLayout.LayoutParams(d(12),d(18)))
    content.addView(points,LinearLayout.LayoutParams(-1,d(52)).apply{topMargin=d(10)})
    if(animate&&prefs.str("motion")!="reduced"){scroll.alpha=0f;scroll.translationY=d(18).toFloat();scroll.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).withLayer().start()}
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
    cover.animate().alpha(0f).setStartDelay(60).setDuration(460).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).withEndAction{decor.removeView(cover);cover.setImageDrawable(null);pic.recycle()}.start()
}

internal fun MainActivity.closeSettings(){val page=settingsPage?:return;settingsPage=null
    if(prefs.str("motion")=="reduced"){root.removeView(page);return}
    page.animate().alpha(0f).translationY(d(14).toFloat()).setDuration(220).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{root.removeView(page)}.start()}

/** «Огонёк» plaque: the app's flame large in the lower right with a warm light inside the plaque, the big number on the left. */
internal class FlamePlaque(private val a:MainActivity):FrameLayout(a){
    var minutes=10;set(v){field=v;number.text="$v";contentDescription="Огонёк: $v минут в день"}
    private val number=a.text("10",34f,a.fg,true,serif=true).apply{includeFontPadding=false}
    init{isClickable=true;isFocusable=true;a.press(this);contentDescription="Огонёк"
        background=PlaqueBackground(a,0xfff9601f.toInt(),.82f,.84f)
        val muted=if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL};head.addView(a.text("Огонёк",12f,muted),LinearLayout.LayoutParams(0,-2,1f));head.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        addView(head,LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(a.d(16),a.d(14),a.d(14),0)})
        addView(FlameArt(a,1f),LayoutParams(a.d(50),a.d(62),Gravity.BOTTOM or Gravity.END).apply{setMargins(0,0,a.d(12),a.d(14))})
        val col=a.column();col.addView(number);col.addView(a.text("минут в день",13f,muted).apply{setPadding(0,a.d(6),0,0)})
        addView(col,LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply{setMargins(a.d(16),0,0,a.d(16))})}
}

/** «Цель на год» plaque: the number, how many are read, and a small road to the flag with the read part in green. */
internal class GoalPlaque(private val a:MainActivity,private val year:Int):FrameLayout(a){
    private val number=a.text("—",30f,a.fg,true,serif=true).apply{includeFontPadding=false}
    private val unit=a.text("",13f,if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt())
    private val read=a.text("",12f,if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt())
    private val road=MiniRoad(a)
    fun set(target:Int,done:Int){number.text=if(target>0)"$target" else "—";unit.text=if(target>0)" "+MainActivity.countBooks(target).substringAfter(' ') else " без цели";read.text="прочитано $done";road.fraction=if(target>0)(done.toFloat()/target).coerceIn(0f,1f) else 0f
        contentDescription="Цель на $year: "+(if(target>0)MainActivity.countBooks(target) else "не задана")+", прочитано $done"}
    init{isClickable=true;isFocusable=true;a.press(this)
        background=PlaqueBackground(a,if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt(),.85f,.1f)
        val muted=if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL};head.addView(a.text("Цель на $year",12f,muted),LinearLayout.LayoutParams(0,-2,1f));head.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        addView(head,LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(a.d(16),a.d(14),a.d(14),0)})
        val col=a.column();val line=a.line().apply{gravity=Gravity.BOTTOM};line.addView(number);line.addView(unit);col.addView(line);col.addView(read.apply{setPadding(0,a.d(4),0,0)})
        addView(col,LayoutParams(-1,-2,Gravity.TOP).apply{setMargins(a.d(16),a.d(44),a.d(16),0)})
        addView(road,LayoutParams(-1,a.d(44),Gravity.BOTTOM).apply{setMargins(a.d(12),0,a.d(16),a.d(10))})}
}

/** The plaques' face: the card colour with one soft light in its own colour (inside the plaque, so nothing is cut). */
internal class PlaqueBackground(private val a:MainActivity,private val glow:Int,private val fx:Float,private val fy:Float):android.graphics.drawable.Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val r=RectF()
    override fun draw(c:Canvas){val b=bounds;r.set(b);val rad=a.d(24).toFloat()
        p.shader=null;p.color=if(a.light)0x0e000000 else 0x12ffffff;c.drawRoundRect(r,rad,rad,p)
        p.color=Color.WHITE // the light must not inherit the card's faint alpha (that hid it completely)
        val cx=b.left+b.width()*fx;val cy=b.top+b.height()*fy;val R=max(b.width(),b.height())*.85f
        p.shader=RadialGradient(cx,cy,R,intArrayOf((glow and 0xffffff) or ((if(a.light)0x2e else 0x4a) shl 24),(glow and 0xffffff) or ((if(a.light)0x12 else 0x1c) shl 24),glow and 0xffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.save();val path=Path().apply{addRoundRect(r,rad,rad,Path.Direction.CW)};c.clipPath(path);c.drawRect(r,p);c.restore();p.shader=null}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** A tiny road to the flag; the read part glows green. */
internal class MiniRoad(private val a:MainActivity):View(a){
    var fraction=0f;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private val path=Path();private val pm=PathMeasure()
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat();val H=h.toFloat();
        fun x(f:Float):Float{return a.d(4)+(W-a.d(20))*f}
        fun y(f:Float):Float{return H*.5f+H*.42f*f}
        path.reset();path.moveTo(x(0f),y(.9f));path.cubicTo(x(.25f),y(1f),x(.3f),y(.2f),x(.55f),y(.35f));path.cubicTo(x(.75f),y(.5f),x(.8f),y(.9f),x(1f),y(.1f));pm.setPath(path,false)}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat()
        p.shader=null;p.color=if(a.light)0x24000000 else 0x38808080;p.strokeWidth=6*d;c.drawPath(path,p)
        if(fraction>0f){val seg=Path();pm.getSegment(0f,pm.length*fraction,seg,true);p.color=if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt();p.strokeWidth=3.5f*d;c.drawPath(seg,p)}
        val e=FloatArray(2);pm.getPosTan(pm.length,e,null);val fp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=a.fg}
        c.drawRect(e[0]-d,e[1]-16*d,e[0]+d,e[1],fp);c.drawPath(Path().apply{moveTo(e[0]+d,e[1]-16*d);lineTo(e[0]+11*d,e[1]-13*d);lineTo(e[0]+d,e[1]-10*d);close()},fp)}
}

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
