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

/** «Оформление чтения»: live preview on top, then theme, font, fine typography, brightness and text cleanup. */
internal class ReaderSettings(private val a:MainActivity,private val r:NativeReader) {
    private val themes=listOf("night" to "Ночь","black" to "Чёрная","sepia" to "Сепия","paper" to "Бумага")
    private val fonts=listOf("literata" to "Literata","ptserif" to "PT Serif","sans" to "Без засечек")
    private val card get()=if(a.light)0xfff3f3f1.toInt() else 0xff232325.toInt()
    private val quiet get()=if(a.light)0xffe6e6e3.toInt() else 0xff333336.toInt()
    private var panel:android.app.AlertDialog?=null
    private val previewText=TextView(a)
    private val previewBox=FrameLayout(a)
    private val previewFade=View(a)
    private val swatches=mutableListOf<ThemeSwatch>();private val swatchLabels=mutableListOf<TextView>()
    private val fontCards=mutableListOf<LinearLayout>()

    fun show(){
        val box=a.column().apply{setPadding(0,a.d(4),0,a.d(12))}
        box.addView(preview(),LinearLayout.LayoutParams(-1,a.d(138)))
        section(box,"Тема");box.addView(themeRow())
        section(box,"Шрифт");box.addView(fontRow())
        section(box,"Текст")
        box.addView(group(
            slider("Размер",14f,32f,.5f,a.prefs.optDouble("size",19.0).toFloat(),19f,{fmt(it)},glyph("А",13f),glyph("А",21f)){v,_->a.prefs.put("size",v.toDouble());typography()},
            slider("Межстрочный интервал",1.2f,2.2f,.05f,r.lineHeight(),1.6f,{fmt(it,2)}){v,_->a.prefs.put("lhv",v.toDouble());typography()},
            slider("Межбуквенный интервал",-.03f,.12f,.005f,a.prefs.optDouble("ls",0.0).toFloat(),0f,{percent(it)}){v,_->a.prefs.put("ls",v.toDouble());typography()},
            *(if(Build.VERSION.SDK_INT>=29)arrayOf(slider("Межсловный интервал",0f,.6f,.02f,a.prefs.optDouble("ws",0.0).toFloat(),0f,{percent(it)}){v,_->a.prefs.put("ws",v.toDouble());typography()})else emptyArray()),
            slider("Поля страницы",8f,48f,2f,a.prefs.optInt("margin",24).toFloat(),24f,{"${it.roundToInt()}"}){v,_->a.prefs.put("margin",v.roundToInt());typography()}
        ))
        section(box,"Яркость");box.addView(group(brightness()))
        section(box,"Вёрстка")
        box.addView(group(
            toggle("По ширине","justify",true),
            toggle("Переносы слов","hyph",true),
            toggle("Убирать пустые строки","trimEmpty",false),
            modeRow()
        ))
        box.addView(a.text("Сбросить оформление",14f,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(18),0,a.d(6));a.press(this);setOnClickListener{reset()}},LinearLayout.LayoutParams(-1,-2))
        refresh()
        panel=a.dialog().setTitle("Оформление чтения").setView(box).setTall(true).show()
    }

    // ---- building blocks -------------------------------------------------------------------

    private fun section(box:LinearLayout,title:String){a.space(box,22);box.addView(a.text(title.uppercase(),12f,a.muted,true).apply{letterSpacing=.06f;setPadding(a.d(4),0,0,0)});a.space(box,10)}
    private fun group(vararg rows:View)=a.column().apply{
        background=a.shape(card,20);setPadding(a.d(16),a.d(6),a.d(16),a.d(6))
        rows.forEachIndexed{i,v->if(i>0)addView(View(a).apply{setBackgroundColor(if(a.light)0x14000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1));addView(v,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun glyph(s:String,size:Float)=a.text(s,size,a.muted,serif=true).apply{gravity=Gravity.CENTER;minWidth=a.d(24)}
    private fun fmt(v:Float,digits:Int=1):String{val t=if(digits==2)"%.2f".format(java.util.Locale.US,v) else "%.1f".format(java.util.Locale.US,v);return t.trimEnd('0').trimEnd('.').replace('.',',')}
    private fun percent(v:Float):String{val p=(v*100).roundToInt();return if(p==0)"0" else if(p>0)"+$p%" else "−${-p}%"}

    private fun slider(title:String,from:Float,to:Float,step:Float,value:Float,default:Float?,format:(Float)->String,lead:View?=null,trail:View?=null,change:(Float,Boolean)->Unit):View{
        val column=a.column().apply{setPadding(0,a.d(10),0,a.d(6))}
        val head=a.line();val valueView=a.text(format(value),14f,a.muted).apply{gravity=Gravity.END}
        head.addView(a.text(title,15f),LinearLayout.LayoutParams(0,-2,1f));head.addView(valueView);column.addView(head)
        val row=a.line();lead?.let{row.addView(it)}
        val bar=SettingSlider(a,from,to,step,value,default){v,done->valueView.text=format(v);change(v,done);refresh();if(done)a.savePreferences()}
        row.addView(bar,LinearLayout.LayoutParams(0,a.d(40),1f));trail?.let{row.addView(it)};column.addView(row);return column
    }

    private fun toggle(title:String,key:String,default:Boolean)=PolkaSwitch(a).apply{
        text=title;setTextColor(a.fg);minHeight=a.d(52);isChecked=a.prefs.optBoolean(key,default)
        setOnCheckedChangeListener{_,on->a.prefs.put(key,on);a.savePreferences();typography()}
    }

    private fun modeRow():View{
        val column=a.column().apply{setPadding(0,a.d(12),0,a.d(12))}
        column.addView(a.text("Режим чтения",15f));a.space(column,10)
        val labels=listOf("Страницы","Прокрутка");val selected=if(r.paged())0 else 1
        val seg=SlidingSegment(a,selected,2,quiet,if(a.light)Color.WHITE else 0xff4a4a4e.toInt(),24)
        fun tint(index:Int){for(i in 0 until seg.childCount)(seg.getChildAt(i) as TextView).setTextColor(if(i==index)a.fg else a.muted)}
        labels.forEachIndexed{i,s->seg.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(36);a.press(this);setOnClickListener{seg.choose(i);tint(i);r.setReadingMode(i==0)}},LinearLayout.LayoutParams(0,-2,1f))}
        tint(selected);column.addView(seg,LinearLayout.LayoutParams(-1,-2));return column
    }

    private fun brightness():View{
        val column=a.column().apply{setPadding(0,a.d(10),0,a.d(8))}
        val head=a.line();head.addView(a.text("Яркость экрана",15f),LinearLayout.LayoutParams(0,-2,1f))
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0);head.addView(auto);column.addView(head)
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val row=a.line();row.addView(SunGlyph(a,false),LinearLayout.LayoutParams(a.d(26),a.d(26)))
        lateinit var bar:SettingSlider
        bar=SettingSlider(a,.02f,1f,.01f,start,null){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);bar.dim=false;r.applyBrightness();if(done)a.savePreferences()}
        bar.dim=auto.on
        row.addView(bar,LinearLayout.LayoutParams(0,a.d(40),1f));row.addView(SunGlyph(a,true),LinearLayout.LayoutParams(a.d(26),a.d(26)));column.addView(row)
        auto.setOnClickListener{val on=!auto.on;auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value().toDouble());a.savePreferences();r.applyBrightness()}
        return column
    }

    private fun preview():View{
        previewBox.clipToOutline=true;previewBox.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,a.d(20).toFloat())}}
        previewText.text="Он долго смотрел на огонь, и в памяти всплывали картины давно минувших дней: тихие вечера, запах старых книг и неторопливые разговоры обо всём на свете."
        previewText.includeFontPadding=false;previewText.breakStrategy=android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        previewBox.addView(previewText,FrameLayout.LayoutParams(-1,-2))
        previewBox.addView(previewFade,FrameLayout.LayoutParams(-1,a.d(34),Gravity.BOTTOM))
        return previewBox
    }

    private fun themeRow():View{
        val row=a.line().apply{gravity=Gravity.CENTER}
        val current=a.prefs.str("rt","night")
        themes.forEachIndexed{i,(key,label)->
            val (bg,ink)=colors(key)
            val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
            val swatch=ThemeSwatch(a,bg,ink,key==current);swatches.add(swatch)
            val name=a.text(label,12f,if(key==current)a.fg else a.muted).apply{gravity=Gravity.CENTER};swatchLabels.add(name)
            col.addView(swatch,LinearLayout.LayoutParams(a.d(58),a.d(58)));a.space(col,6);col.addView(name)
            col.setOnClickListener{a.prefs.put("rt",key);a.savePreferences();swatches.forEachIndexed{j,s->s.select(j==i)};swatchLabels.forEachIndexed{j,t->t.setTextColor(if(j==i)a.fg else a.muted)};typography()}
            a.press(col);row.addView(col,LinearLayout.LayoutParams(0,-2,1f))
        };return row
    }

    private fun fontRow():View{
        val row=a.line()
        fonts.forEachIndexed{i,(key,label)->
            val cardView=a.column().apply{gravity=Gravity.CENTER;setPadding(a.d(6),a.d(12),a.d(6),a.d(10))}
            cardView.addView(TextView(a).apply{text="Аа";textSize=26f;setTextColor(a.fg);typeface=PolkaFonts.reader(a,key);gravity=Gravity.CENTER;includeFontPadding=false})
            a.space(cardView,6);cardView.addView(a.text(label,12f,a.muted).apply{gravity=Gravity.CENTER;maxLines=1})
            cardView.setOnClickListener{a.prefs.put("font",key);a.savePreferences();paintFonts(true);typography()}
            a.press(cardView);fontCards.add(cardView)
            row.addView(cardView,LinearLayout.LayoutParams(0,-2,1f).apply{if(i>0)leftMargin=a.d(8)})
        };paintFonts(false);return row
    }
    private fun paintFonts(animate:Boolean){
        val current=a.prefs.str("font","literata")
        fontCards.forEachIndexed{i,c->
            val on=fonts[i].first==current
            val target=GradientDrawable().apply{cornerRadius=a.d(16).toFloat();setColor(card);if(on)setStroke(a.d(2),a.accent) else setStroke(a.d(1),if(a.light)0x14000000 else 0x18ffffff)}
            c.background=target
            if(on&&animate&&a.prefs.str("motion")!="reduced"){c.scaleX=.96f;c.scaleY=.96f;c.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(PathInterpolator(.2f,1.4f,.4f,1f)).start()}
        }
    }

    // ---- state -----------------------------------------------------------------------------

    private fun colors(key:String)=when(key){"black"->0xff000000.toInt() to 0xffc9c7c1.toInt();"sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt();"paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt();else->0xff18181a.toInt() to 0xffdedcd6.toInt()}
    private fun typography(){r.applyTypography();refresh()}
    /** The preview is drawn with exactly the reader's values, scaled to the card. */
    private fun refresh(){
        val (bg,ink)=colors(a.prefs.str("rt","night"))
        previewBox.background=GradientDrawable().apply{cornerRadius=a.d(20).toFloat();setColor(bg);setStroke(1,if(Color.red(bg)>128)0x1a000000 else 0x1effffff)}
        previewFade.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(bg and 0x00ffffff,bg))
        val size=a.prefs.optDouble("size",19.0).toFloat();val lh=r.lineHeight()
        previewText.setTextColor(ink);previewText.typeface=PolkaFonts.reader(a,a.prefs.str("font","literata"));previewText.textSize=size
        previewText.letterSpacing=a.prefs.optDouble("ls",0.0).toFloat()
        if(Build.VERSION.SDK_INT>=29)previewText.paint.wordSpacing=a.prefs.optDouble("ws",0.0).toFloat()*previewText.textSize
        if(Build.VERSION.SDK_INT>=28)previewText.lineHeight=(previewText.textSize*lh).roundToInt() else previewText.setLineSpacing(0f,lh*.86f)
        previewText.justificationMode=if(a.prefs.optBoolean("justify",true))android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
        previewText.hyphenationFrequency=r.hyphenFreq()
        val side=(a.d(a.prefs.optInt("margin",24))*.75f).roundToInt()
        previewText.setPadding(side,a.d(16),side,0);previewText.requestLayout();previewText.invalidate()
    }
    private fun reset(){
        listOf("lhv","ls","ws","margin").forEach{a.prefs.remove(it)}
        a.prefs.put("size",19.0);a.prefs.put("lh","normal");a.prefs.put("font","literata");a.prefs.put("justify",true);a.prefs.put("hyph",true);a.prefs.put("trimEmpty",false)
        a.savePreferences();r.applyTypography();panel?.dismiss();r.view.post{ReaderSettings(a,r).show()}
    }
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
