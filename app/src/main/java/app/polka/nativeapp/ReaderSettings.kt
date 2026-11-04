package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** «Оформление чтения» (approved design): a compact panel at the bottom, under half the screen and without dimming,
 *  so every change shows at once in the page above it. The main page holds brightness, theme, font and size,
 *  line spacing and margins; «Вёрстка и режим» and the font list open inside the same panel at the same height. */
internal class ReaderSettings(private val a:MainActivity,private val r:NativeReader) {
    private val themes=listOf("night" to "Ночь","black" to "Чёрная","sepia" to "Сепия","paper" to "Бумага")
    private val fonts=listOf(Triple("literata","Literata","для долгого чтения"),Triple("ptserif","PT Serif","классическая книжная"),Triple("sans","Без засечек","чистая и простая"))
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val onColor get()=if(a.light)0x1f000000 else 0x24ffffff
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var dialog:android.app.Dialog?=null
    private lateinit var sheet:LinearLayout
    private lateinit var pages:FrameLayout
    private lateinit var main:View
    private var sub:View?=null
    private val refreshers=mutableListOf<()->Unit>()

    fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=FrameLayout(a).apply{isClickable=true;setOnClickListener{close()}}
        sheet=a.column().apply{isClickable=true;setPadding(a.d(14),a.d(10),a.d(14),a.d(14));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(12)})
        pages=FrameLayout(a).apply{clipChildren=true}
        main=mainPage();pages.addView(main,FrameLayout.LayoutParams(-1,-2))
        sheet.addView(pages,LinearLayout.LayoutParams(-1,-2))
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root.addView(sheet,lp)
        dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,r.backgroundColor)
            if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;sheet.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){if(sub!=null)back() else close();true}else false}
        dlg.show()
        if(!reduced){sheet.translationY=a.d(400).toFloat();sheet.post{sheet.translationY=sheet.height+a.d(20).toFloat();sheet.animate().translationY(0f).setDuration(360).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
    }
    private fun close(){val d=dialog?:return;dialog=null
        if(reduced){d.dismiss();return}
        sheet.animate().translationY(sheet.height+a.d(24).toFloat()).setDuration(240).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}

    // ---- pages -------------------------------------------------------------------------------

    private fun mainPage():View{
        val box=a.column()
        box.addView(brightnessRow())
        box.addView(themeTiles(),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})
        val fontSize=a.line()
        fontSize.addView(fontButton(),LinearLayout.LayoutParams(0,a.d(54),1.2f))
        fontSize.addView(sizeStepper(),LinearLayout.LayoutParams(0,a.d(54),1f).apply{leftMargin=a.d(8)})
        box.addView(fontSize,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
        val presets=a.line()
        presets.addView(presetGroup("Интервал",listOf(1.4f,1.6f,1.85f),{r.lineHeight()},{v->a.prefs.put("lhv",v.toDouble())},"lines"),LinearLayout.LayoutParams(0,-2,1f))
        presets.addView(presetGroup("Поля",listOf(12f,24f,40f),{a.prefs.optInt("margin",24).toFloat()},{v->a.prefs.put("margin",v.roundToInt())},"margins"),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=a.d(8)})
        box.addView(presets,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
        val more=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(12),0);background=a.shape(pillColor,16);isClickable=true;a.press(this);setOnClickListener{open(layoutPage())}}
        more.addView(a.text("Вёрстка и режим",14f),LinearLayout.LayoutParams(0,-2,1f))
        val summary=a.text("",12.5f,a.muted);more.addView(summary)
        refreshers.add{summary.text=(if(r.paged())"Страницы" else "Прокрутка")+(if(a.prefs.optBoolean("justify",true))" · по ширине" else "")+"  ›"}
        box.addView(more,LinearLayout.LayoutParams(-1,a.d(48)).apply{topMargin=a.d(10)})
        refresh();return box
    }
    /** A sub-page slides in over the main one at the main page's height; its content scrolls if longer. */
    private fun open(page:View){if(sub!=null)return;val h=main.height
        val holder=a.column()
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(a.text("‹",18f,a.fg).apply{gravity=Gravity.CENTER;background=a.shape(pillColor,15);isClickable=true;a.press(this);setOnClickListener{back()};contentDescription="Назад"},LinearLayout.LayoutParams(a.d(30),a.d(30)))
        head.addView(a.text(page.tag as? String?:"",15f,a.fg,true).apply{setPadding(a.d(10),0,0,0)})
        holder.addView(head,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(8)})
        val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(24));addView(page)}
        holder.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        pages.addView(holder,FrameLayout.LayoutParams(-1,h));sub=holder
        main.layoutParams=main.layoutParams.apply{height=h}
        val w=pages.width.toFloat()
        if(reduced){main.visibility=View.INVISIBLE;return}
        holder.translationX=w;holder.animate().translationX(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
        main.animate().translationX(-w*.3f).alpha(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
    private fun back(){val s=sub?:return;sub=null;val w=pages.width.toFloat();refresh()
        if(reduced){pages.removeView(s);main.visibility=View.VISIBLE;main.alpha=1f;main.translationX=0f;return}
        s.animate().translationX(w).setDuration(280).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).withEndAction{pages.removeView(s)}.start()
        main.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}

    private fun layoutPage():View{
        val box=a.column().apply{tag="Вёрстка и режим"}
        box.addView(modeRow(),LinearLayout.LayoutParams(-1,-2))
        box.addView(group(
            inline("Межстрочный",1.2f,2.2f,.05f,r.lineHeight(),1.6f,{fmt(it,2)}){v->a.prefs.put("lhv",v.toDouble())},
            inline("Поля",8f,48f,2f,a.prefs.optInt("margin",24).toFloat(),24f,{"${it.roundToInt()}"}){v->a.prefs.put("margin",v.roundToInt())},
            inline("Межбуквенный",-.03f,.12f,.005f,a.prefs.optDouble("ls",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ls",v.toDouble())},
            *(if(Build.VERSION.SDK_INT>=29)arrayOf(inline("Межсловный",0f,.6f,.02f,a.prefs.optDouble("ws",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ws",v.toDouble())})else emptyArray()),
            toggle("По ширине","justify",true),
            toggle("Переносы слов","hyph",true),
            toggle("Убирать пустые строки","trimEmpty",false)
        ),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        box.addView(a.text("Сбросить оформление",13.5f,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(14),0,a.d(6));a.press(this);setOnClickListener{reset()}},LinearLayout.LayoutParams(-1,-2))
        return box
    }
    private fun fontPage():View{
        val box=a.column().apply{tag="Шрифт"}
        val rows=mutableListOf<Pair<String,LinearLayout>>()
        fun paint(){val cur=a.prefs.str("font","literata");rows.forEach{(k,v)->v.background=a.shape(if(k==cur)onColor else pillColor,16);(v.getChildAt(1) as View).visibility=if(k==cur)View.VISIBLE else View.INVISIBLE}}
        fonts.forEach{(key,label,note)->
            val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(14),0);isClickable=true;a.press(this)}
            val texts=a.column()
            texts.addView(TextView(a).apply{text=label;textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.reader(a,key);includeFontPadding=false})
            texts.addView(a.text(note,11f,a.muted).apply{setPadding(0,a.d(3),0,0)})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(a.text("✓",13f,a.bg,true).apply{gravity=Gravity.CENTER;background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(a.fg)}},LinearLayout.LayoutParams(a.d(22),a.d(22)))
            row.setOnClickListener{a.prefs.put("font",key);a.savePreferences();paint();typography()}
            rows.add(key to row);box.addView(row,LinearLayout.LayoutParams(-1,a.d(58)).apply{bottomMargin=a.d(6)})}
        paint();return box
    }

    // ---- building blocks -----------------------------------------------------------------------

    private fun brightnessRow():View{
        val row=a.line()
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0)
        val bar=BrightBar(a,start){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);r.applyBrightness();if(done)a.savePreferences()}
        bar.dim=auto.on
        auto.setOnClickListener{val on=!auto.on;auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value.toDouble());a.savePreferences();r.applyBrightness()}
        row.addView(bar,LinearLayout.LayoutParams(0,a.d(40),1f));row.addView(auto,LinearLayout.LayoutParams(-2,a.d(40)).apply{leftMargin=a.d(8)})
        return row
    }
    private fun themeTiles():View{
        val row=a.line()
        themes.forEachIndexed{i,(key,label)->
            val (bg,ink)=colors(key)
            val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;isClickable=true}
            val tile=ThemeTile(a,bg,ink);val name=a.text(label,10.5f,a.muted).apply{gravity=Gravity.CENTER}
            col.addView(tile,LinearLayout.LayoutParams(-1,a.d(48)));col.addView(name,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(5)})
            refreshers.add{val on=a.prefs.str("rt","night")==key;tile.on=on;name.setTextColor(if(on)a.fg else a.muted)}
            col.setOnClickListener{a.prefs.put("rt",key);a.savePreferences();typography()}
            a.press(col);row.addView(col,LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)leftMargin=a.d(8)})}
        return row
    }
    private fun fontButton():View{
        val v=FrameLayout(a).apply{background=a.shape(pillColor,16);isClickable=true;a.press(this);setOnClickListener{open(fontPage())};contentDescription="Шрифт"}
        val texts=a.column().apply{setPadding(a.d(14),0,a.d(28),0);gravity=Gravity.CENTER_VERTICAL}
        texts.addView(a.text("Шрифт",10.5f,a.muted))
        val name=TextView(a).apply{textSize=17f;setTextColor(a.fg);includeFontPadding=false;maxLines=1}
        texts.addView(name,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
        refreshers.add{val k=a.prefs.str("font","literata");name.text=fonts.firstOrNull{it.first==k}?.second?:"Literata";name.typeface=PolkaFonts.reader(a,k)}
        v.addView(texts,FrameLayout.LayoutParams(-1,-1))
        v.addView(a.text("›",18f,a.muted),FrameLayout.LayoutParams(-2,-2,Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=a.d(14)})
        return v
    }
    private fun sizeStepper():View{
        val row=a.line().apply{background=a.shape(pillColor,16)}
        val value=a.text("",15f,a.fg,true).apply{gravity=Gravity.CENTER}
        val label=a.text("размер",9.5f,a.muted).apply{gravity=Gravity.CENTER}
        fun change(d:Float){val v=(a.prefs.optDouble("size",19.0).toFloat()+d).coerceIn(14f,32f);a.prefs.put("size",v.toDouble());a.savePreferences();typography()}
        fun glyph(size:Float,d:Float,desc:String)=TextView(a).apply{text="А";textSize=size;setTextColor(a.fg);typeface=PolkaFonts.reader(a,"literata");gravity=Gravity.CENTER;isClickable=true;contentDescription=desc;a.press(this);setOnClickListener{change(d)}}
        row.addView(glyph(14f,-1f,"Меньше"),LinearLayout.LayoutParams(0,-1,1f))
        val mid=a.column().apply{gravity=Gravity.CENTER};mid.addView(value);mid.addView(label);row.addView(mid,LinearLayout.LayoutParams(0,-1,1f))
        row.addView(glyph(20f,1f,"Больше"),LinearLayout.LayoutParams(0,-1,1f))
        refreshers.add{value.text=fmt(a.prefs.optDouble("size",19.0).toFloat())}
        return row
    }
    /** Three choices as icons (tight / normal / airy lines, or narrow / medium / wide margins), with a small caption. */
    private fun presetGroup(title:String,values:List<Float>,current:()->Float,set:(Float)->Unit,kind:String):View{
        val col=a.column()
        val seg=a.line().apply{background=a.shape(pillColor,16);setPadding(a.d(3),a.d(3),a.d(3),a.d(3))}
        val cells=values.mapIndexed{i,v->PresetGlyph(a,kind,i).apply{isClickable=true;contentDescription="$title ${i+1}";a.press(this);setOnClickListener{set(v);a.savePreferences();typography()}}}
        cells.forEach{seg.addView(it,LinearLayout.LayoutParams(0,a.d(40),1f))}
        col.addView(seg,LinearLayout.LayoutParams(-1,-2))
        col.addView(a.text(title,10.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(5)})
        refreshers.add{val c=current();val best=values.indices.minByOrNull{abs(values[it]-c)}?:1;val exact=abs(values[best]-c)<=(values.last()-values.first())*.06f
            cells.forEachIndexed{i,g->g.on=exact&&i==best;g.background=if(g.on)a.shape(onColor,13) else null}}
        return col
    }
    private fun group(vararg rows:View)=a.column().apply{
        background=a.shape(pillColor,18);setPadding(a.d(14),a.d(2),a.d(14),a.d(2))
        rows.forEachIndexed{i,v->if(i>0)addView(View(a).apply{setBackgroundColor(if(a.light)0x10000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1));addView(v,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun fmt(v:Float,digits:Int=1):String{val t=if(digits==2)"%.2f".format(java.util.Locale.US,v) else "%.1f".format(java.util.Locale.US,v);return t.trimEnd('0').trimEnd('.').replace('.',',')}
    private fun percent(v:Float):String{val p=(v*100).roundToInt();return if(p==0)"0" else if(p>0)"+$p%" else "−${-p}%"}
    /** One line: the name, a compact slider and the value. */
    private fun inline(title:String,from:Float,to:Float,step:Float,value:Float,default:Float?,format:(Float)->String,set:(Float)->Unit):View{
        val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(46)}
        row.addView(a.text(title,14f),LinearLayout.LayoutParams(0,-2,1f))
        val valueView=a.text(format(value),12.5f,a.muted).apply{gravity=Gravity.END}
        val bar=SettingSlider(a,from,to,step,value,default){v,done->valueView.text=format(v);set(v);typography();if(done)a.savePreferences()}
        row.addView(bar,LinearLayout.LayoutParams(a.d(150),a.d(40)));row.addView(valueView,LinearLayout.LayoutParams(a.d(42),-2))
        return row
    }
    private fun toggle(title:String,key:String,default:Boolean)=PolkaSwitch(a).apply{
        text=title;setTextColor(a.fg);textSize=14f;minHeight=a.d(46);isChecked=a.prefs.optBoolean(key,default)
        setOnCheckedChangeListener{_,on->a.prefs.put(key,on);a.savePreferences();typography()}
    }
    private fun modeRow():View{
        val labels=listOf("Страницы","Прокрутка");val selected=if(r.paged())0 else 1
        val seg=SlidingSegment(a,selected,2,pillColor,if(a.light)Color.WHITE else 0xfff2f2f2.toInt(),16)
        fun tint(index:Int){for(i in 0 until seg.childCount)(seg.getChildAt(i) as TextView).setTextColor(if(i==index)(if(a.light)a.fg else 0xff111111.toInt()) else a.muted)}
        labels.forEachIndexed{i,s->seg.addView(a.text(s,13.5f,bold=true).apply{gravity=Gravity.CENTER;minHeight=a.d(38);a.press(this);setOnClickListener{seg.choose(i);tint(i);r.setReadingMode(i==0);refresh()}},LinearLayout.LayoutParams(0,-2,1f))}
        tint(selected);return seg
    }

    // ---- state -----------------------------------------------------------------------------

    private fun colors(key:String)=when(key){"black"->0xff000000.toInt() to 0xffc9c7c1.toInt();"sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt();"paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt();else->0xff18181a.toInt() to 0xffdedcd6.toInt()}
    private fun typography(){r.applyTypography();refresh()}
    private fun refresh(){refreshers.forEach{it()}}
    private fun reset(){
        listOf("lhv","ls","ws","margin").forEach{a.prefs.remove(it)}
        a.prefs.put("size",19.0);a.prefs.put("lh","normal");a.prefs.put("font","literata");a.prefs.put("justify",true);a.prefs.put("hyph",true);a.prefs.put("trimEmpty",false)
        a.savePreferences();r.applyTypography();val d=dialog;dialog=null;d?.dismiss();r.view.post{ReaderSettings(a,r).show()}
    }
}

/** The brightness bar: a wide pill that fills to the level, a small sun at the start and a big one at the end. */
internal class BrightBar(private val a:MainActivity,start:Float,private val change:(Float,Boolean)->Unit):View(a){
    var value=start.coerceIn(.02f,1f);private set
    var dim=false;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    init{contentDescription="Яркость экрана";isClickable=true}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);set(e.x,false)}
        MotionEvent.ACTION_MOVE->set(e.x,false)
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->set(e.x,true)};return true}
    private fun set(x:Float,done:Boolean){value=(x/width).coerceIn(.02f,1f);dim=false;invalidate();change(value,done)}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;val w=width.toFloat();val d=a.resources.displayMetrics.density
        p.style=Paint.Style.FILL;p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        val fx=(w*value).coerceAtLeast(h)
        p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,fx,0f,if(a.light)0xff9a9a9e.toInt() else 0xff8c8c90.toInt(),if(a.light)0xff5c5c60.toInt() else 0xffe6e6e8.toInt(),Shader.TileMode.CLAMP)
        p.alpha=if(dim)110 else 255;c.drawRoundRect(0f,0f,fx,h,r,r,p);p.shader=null;p.alpha=255
        fun sun(cx:Float,big:Boolean,ink:Int){val k=if(big)1f else .7f;p.color=ink;p.style=Paint.Style.FILL;c.drawCircle(cx,h/2,3.6f*d*k,p)
            if(big){p.style=Paint.Style.STROKE;p.strokeWidth=1.6f*d;for(i in 0 until 8){val t=Math.PI*i/4;c.drawLine(cx+(cos(t)*6.5f*d).toFloat(),h/2+(sin(t)*6.5f*d).toFloat(),cx+(cos(t)*9f*d).toFloat(),h/2+(sin(t)*9f*d).toFloat(),p)}}}
        sun(h/2,false,if(a.light)Color.WHITE else 0xff1d1d20.toInt());sun(w-h/2,true,if(fx>w-h)(if(a.light)Color.WHITE else 0xff1d1d20.toInt()) else a.muted)}
}

/** A theme as a rounded tile with «Аа» in its own ink; the chosen one gets a ring. */
internal class ThemeTile(private val a:MainActivity,private val bg:Int,private val ink:Int):View(a){
    var on=false;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val inset=3*d;val r=RectF(inset,inset,width-inset,height-inset);val rad=13*d
        if(on){p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;c.drawRoundRect(RectF(d,d,width-d,height-d),rad+2*d,rad+2*d,p)}
        p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(Color.red(bg)>128)0x1a000000 else 0x24ffffff;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.FILL;p.color=ink;p.textSize=16*a.resources.displayMetrics.scaledDensity;p.typeface=PolkaFonts.reader(a,"literata");p.textAlign=Paint.Align.CENTER
        c.drawText("Аа",width/2f,height/2f-(p.ascent()+p.descent())/2f,p)}
}

/** Icons for the presets: lines with tight / normal / airy spacing, or a page with narrow / medium / wide margins. */
internal class PresetGlyph(private val a:MainActivity,private val kind:String,private val level:Int):View(a){
    var on=false;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND;style=Paint.Style.STROKE}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val w=24*d;val h=20*d;c.save();c.translate((width-w)/2,(height-h)/2)
        p.color=if(on)a.fg else a.muted;p.strokeWidth=2*d
        if(kind=="lines"){val gaps=floatArrayOf(4f,5f,7.5f)[level]*d;var y=h/2-gaps*1.5f;val n=if(level==2)3 else 4
            if(level==2)y=h/2-gaps
            for(i in 0 until n){val end=if(i==n-1)w*.6f else w;c.drawLine(2*d,y,end-2*d,y,p);y+=gaps}}
        else{p.strokeWidth=1.8f*d;c.drawRoundRect(RectF(2*d,1*d,w-2*d,h-1*d),3*d,3*d,p);val m=floatArrayOf(5f,7.5f,10f)[level]*d;p.strokeWidth=1.8f*d
            for(i in 0 until 3){val y=6*d+i*4*d;val end=if(i==2)w/2+ (w/2-m)*.3f else w-m;c.drawLine(m,y,end,y,p)}}
        c.restore()}
}

/** A quiet track, a default tick and a white knob; snaps softly to the default value with a tick of haptics. */
internal class SettingSlider(private val a:MainActivity,private val from:Float,private val to:Float,private val step:Float,value:Float,private val default:Float?,private val onChange:(Float,Boolean)->Unit):SeekBar(a){
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown=value.coerceIn(from,to);private var detent=false
    var dim=false;set(v){field=v;invalidate()}
    init{
        background=null;splitTrack=false;setPadding(a.d(14),0,a.d(14),0)
        thumb=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setSize(a.d(28),a.d(28))}
        max=((to-from)/step).roundToInt();progress=((shown-from)/step).roundToInt()
        setOnSeekBarChangeListener(object:OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){
                if(!user)return
                var v=(from+p*step).coerceIn(from,to)
                if(default!=null){val near=abs(v-default)<=(to-from)*.03f;if(near&&!detent&&a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);detent=near;if(near)v=default}
                shown=v;invalidate();onChange(v,false)
            }
            override fun onStartTrackingTouch(s:SeekBar?){parent?.requestDisallowInterceptTouchEvent(true)}
            override fun onStopTrackingTouch(s:SeekBar?){onChange(shown,true)}
        })
    }
    fun value()=shown
    override fun onDraw(c:Canvas){
        val y=height/2f;val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val span=(right-left).coerceAtLeast(1f)
        fun xOf(v:Float)=left+span*((v-from)/(to-from)).coerceIn(0f,1f)
        val x=xOf(shown);val h=a.d(3).toFloat()
        ink.style=Paint.Style.FILL;ink.clearShadowLayer()
        ink.color=if(a.light)0xffdcdcd9.toInt() else 0xff3c3c40.toInt();c.drawRoundRect(left,y-h,right,y+h,h,h,ink)
        ink.color=if(dim)(a.fg and 0x00ffffff) or 0x55000000 else a.fg;c.drawRoundRect(left,y-h,x,y+h,h,h,ink)
        default?.let{val dx=xOf(it);if(abs(dx-x)>a.d(8)){ink.color=if(a.light)0x40000000 else 0x50ffffff;c.drawCircle(dx,y,a.d(2).toFloat(),ink)}}
        ink.color=0x26000000;c.drawCircle(x,y+a.d(1),a.d(13).toFloat(),ink)
        ink.color=Color.WHITE;c.drawCircle(x,y,a.d(12).toFloat(),ink)
        if(!a.light){ink.style=Paint.Style.STROKE;ink.strokeWidth=1f;ink.color=0x22000000;c.drawCircle(x,y,a.d(12).toFloat(),ink)}
    }
}

/** A page-coloured circle with «Аа» in the page ink; the accent ring grows in when chosen. */
internal class ThemeSwatch(private val a:MainActivity,private val bg:Int,private val ink:Int,selected:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var ring=if(selected)1f else 0f;private var anim:ValueAnimator?=null
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun select(on:Boolean){val target=if(on)1f else 0f;if(target==ring)return;anim?.cancel()
        if(a.prefs.str("motion")=="reduced"||!isAttachedToWindow){ring=target;invalidate();return}
        anim=ValueAnimator.ofFloat(ring,target).apply{duration=260;interpolator=PathInterpolator(.2f,1f,.3f,1f);addUpdateListener{ring=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val outer=minOf(width,height)/2f-a.d(1);val inner=outer-a.d(5)
        if(ring>0f){p.style=Paint.Style.STROKE;p.strokeWidth=(2.5f*a.resources.displayMetrics.density)*ring;p.color=a.accent;c.drawCircle(cx,cy,outer-p.strokeWidth/2f,p)}
        val r=inner-a.d(1)*(1f-ring)
        p.style=Paint.Style.FILL;p.color=bg;c.drawCircle(cx,cy,r,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(Color.red(bg)>128)0x22000000 else 0x30ffffff;c.drawCircle(cx,cy,r,p)
        p.style=Paint.Style.FILL;p.color=ink;p.textSize=a.d(17).toFloat();p.typeface=PolkaFonts.reader(a,"literata");p.textAlign=Paint.Align.CENTER
        c.drawText("Аа",cx,cy-(p.ascent()+p.descent())/2f,p)
    }
}

/** «Авто» pill used for brightness. */
internal class AutoChip(private val a:MainActivity,initial:Boolean):TextView(a){
    var on=initial;private set
    init{text="Авто";textSize=13f;gravity=Gravity.CENTER;setPadding(a.d(14),a.d(6),a.d(14),a.d(6));minHeight=a.d(30);a.press(this);paint()}
    fun set(value:Boolean){if(value==on)return;on=value;paint()}
    private fun paint(){background=a.shape(if(on)a.fg else (if(a.light)0xffe6e6e3.toInt() else 0xff333336.toInt()),15);setTextColor(if(on)a.bg else a.muted)}
}

/** Small sun for the brightness ends. */
internal class SunGlyph(private val a:MainActivity,private val large:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val k=if(large)1f else .72f;p.color=a.muted
        p.style=Paint.Style.FILL;c.drawCircle(cx,cy,a.d(4)*k,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=(1.6f*a.resources.displayMetrics.density)*k
        for(i in 0 until 8){val t=Math.PI*i/4;val r1=(6.5f*a.resources.displayMetrics.density)*k;val r2=(9f*a.resources.displayMetrics.density)*k;c.drawLine(cx+(cos(t)*r1).toFloat(),cy+(sin(t)*r1).toFloat(),cx+(cos(t)*r2).toFloat(),cy+(sin(t)*r2).toFloat(),p)}
    }
}
