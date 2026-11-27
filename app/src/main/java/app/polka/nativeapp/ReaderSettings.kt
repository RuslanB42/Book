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

/** A light tick under the finger for a choice made (when vibration is on in the settings). */
internal fun View.tick(a:MainActivity,kind:Int=HapticFeedbackConstants.CLOCK_TICK){if(a.prefs.optBoolean("haptics",true))performHapticFeedback(kind)}

/**
 * Text settings in the reader's bottom panel (approved «Текст А: слоями, как в Apple Books»): «Текст» with the themes
 * as cards, brightness, the size as one capsule and two rows, «Шрифт» and «Вёрстка» a level deeper. One grid: 16 dp
 * at the panel's sides and between blocks, controls 48 dp high, rows 52 dp in cards. Every choice ticks.
 */
internal class ReaderSettings(private val a:MainActivity,private val r:NativeReader) {
    private val themes=listOf("night" to "Ночь","black" to "Чёрная","sepia" to "Сепия","paper" to "Бумага")
    private val fonts=listOf("literata" to "Literata","ptserif" to "PT Serif","sans" to "Без засечек")
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val cardColor get()=if(a.light)Color.WHITE else 0xff2a2a2d.toInt()
    private val muted2 get()=if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val refreshers=mutableListOf<()->Unit>()

    fun show(){r.panel.push(ReaderPanel.Page("Текст",mainPage(),onReturn={refresh()}))}

    // ---- pages -------------------------------------------------------------------------------

    private fun mainPage():View{
        val box=a.column().apply{clipChildren=false;clipToPadding=false}
        box.addView(themeCards(),LinearLayout.LayoutParams(-1,a.d(72)))
        box.addView(brightnessRow(),LinearLayout.LayoutParams(-1,a.d(48)).apply{topMargin=a.d(16)})
        box.addView(SizeStepper(a,a.prefs.optDouble("size",19.0).roundToInt()){v->a.prefs.put("size",v.toDouble());a.savePreferences();typography()}.also{s->refreshers.add{s.sync(a.prefs.optDouble("size",19.0).roundToInt())}},
            LinearLayout.LayoutParams(-1,a.d(48)).apply{topMargin=a.d(8)})
        val (fontRow,fontValue)=row("Шрифт"){r.panel.push(ReaderPanel.Page("Шрифт",fontPage()))}
        refreshers.add{val k=a.prefs.str("font","literata");fontValue.text=fontName(k);fontValue.typeface=PolkaFonts.reader(a,k)}
        val (layoutRow,layoutValue)=row("Вёрстка"){r.panel.push(layoutPage())}
        refreshers.add{layoutValue.text=if(a.prefs.optBoolean("justify",true))"По ширине" else "По левому краю"}
        box.addView(card(listOf(fontRow,layoutRow)),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16)})
        refresh();return box
    }
    private fun fontName(k:String)=fonts.firstOrNull{it.first==k}?.second?:CustomFonts.name(a,k)?:"Literata"

    private fun fontPage():View{
        val box=a.column()
        fun build(){box.removeAllViews()
            val cur=a.prefs.str("font","literata")
            val rows=(fonts+CustomFonts.list(a).map{it.first to it.second}).map{(key,label)->
                val custom=key.startsWith(CustomFonts.PREFIX)
                val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(56);setPadding(a.d(16),0,a.d(14),0);isClickable=true;isFocusable=true;contentDescription=label+if(key==cur)", выбран" else ""}
                // the name itself, set in its own face, shows the font
                row.addView(TextView(a).apply{text=label;textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.reader(a,key);includeFontPadding=false;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
                if(key==cur)row.addView(FrameLayout(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(a.fg)};addView(IconView(a,"check",a.bg),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))},LinearLayout.LayoutParams(a.d(24),a.d(24)))
                // your own fonts can be removed again: a round button in the app's red for deleting
                if(custom)row.addView(FrameLayout(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(0x29d9504e)};isClickable=true;contentDescription="Удалить шрифт";a.press(this)
                    addView(IconView(a,"trash",0xffe5635f.toInt()),FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.CENTER))
                    setOnClickListener{a.confirm("Удалить шрифт?","«$label» пропадёт из списка шрифтов."){CustomFonts.remove(a,key);a.savePreferences();build();typography()}}},LinearLayout.LayoutParams(a.d(36),a.d(36)).apply{leftMargin=a.d(10)})
                row.setOnClickListener{if(a.prefs.str("font","literata")!=key){row.tick(a);a.prefs.put("font",key);a.savePreferences();build();typography()}}
                row as View}
            // add a font from a file: the new one is chosen at once
            val add=a.line().apply{gravity=Gravity.CENTER;minimumHeight=a.d(52);isClickable=true;isFocusable=true;contentDescription="Добавить шрифт"
                addView(IconView(a,"plus",a.fg),LinearLayout.LayoutParams(a.d(20),a.d(20)))
                addView(a.text("Добавить шрифт",15f,a.fg).apply{typeface=PolkaFonts.sans(500);setPadding(a.d(8),0,0,0)})
                setOnClickListener{a.pickFont{key->a.prefs.put("font",key);a.savePreferences();build();typography()}}}
            box.addView(card(rows+add),LinearLayout.LayoutParams(-1,-2))}
        build();return box
    }

    private fun layoutPage():ReaderPanel.Page{
        val box=a.column().apply{clipChildren=false;clipToPadding=false}
        box.addView(caption("ЛИСТАНИЕ",0))
        box.addView(turnCards(),LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-a.d(16);rightMargin=-a.d(16)})
        box.addView(caption("ИНТЕРВАЛЫ"))
        val sliders=mutableListOf(
            sliderRow("Межстрочный",1.2f,2.2f,.05f,r.lineHeight(),1.6f,{fmt(it,2)}){v->a.prefs.put("lhv",v.toDouble())},
            sliderRow("Поля",8f,48f,2f,a.prefs.optInt("margin",24).toFloat(),24f,{"${it.roundToInt()}"}){v->a.prefs.put("margin",v.roundToInt())},
            sliderRow("Межбуквенный",-.03f,.12f,.005f,a.prefs.optDouble("ls",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ls",v.toDouble())})
        if(Build.VERSION.SDK_INT>=29)sliders.add(sliderRow("Межсловный",0f,.6f,.02f,a.prefs.optDouble("ws",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ws",v.toDouble())})
        box.addView(card(sliders),LinearLayout.LayoutParams(-1,-2))
        box.addView(caption("ТЕКСТ"))
        fun sw(title:String,key:String,default:Boolean)=LayoutToggle(a,title,a.prefs.optBoolean(key,default),plain=true){on->r.crossfade{a.prefs.put(key,on);a.savePreferences();typography()}}
        box.addView(card(listOf(sw("По ширине","justify",true),sw("Переносы","hyph",true),sw("Убирать пустые строки","trimEmpty",false))),LinearLayout.LayoutParams(-1,-2))
        val reset=a.text("Сбросить",13f,muted2).apply{setPadding(a.d(10),a.d(8),a.d(2),a.d(8));isClickable=true;a.press(this);setOnClickListener{reset(this)}}
        return ReaderPanel.Page("Вёрстка",box,right=reset)
    }

    // ---- building blocks -----------------------------------------------------------------------

    private fun caption(t:String,top:Int=18)=a.text(t,12f,muted2,true).apply{letterSpacing=.06f;setPadding(a.d(4),a.d(top),0,a.d(9))}
    /** A group as one card, its rows split by thin lines; a tappable row lights up under the finger. */
    private fun card(rows:List<View>):View{val box=a.column().apply{background=a.shape(cardColor,20)}
        fun lit(top:Boolean,bottom:Boolean)=android.graphics.drawable.StateListDrawable().apply{setExitFadeDuration(220)
            val rad=a.d(20).toFloat();val t=if(top)rad else 0f;val b=if(bottom)rad else 0f
            addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{setColor(if(a.light)0x0c000000 else 0x0fffffff);cornerRadii=floatArrayOf(t,t,t,t,b,b,b,b)})
            addState(intArrayOf(),android.graphics.drawable.ColorDrawable(0))}
        rows.forEachIndexed{i,v->if(i>0)box.addView(View(a).apply{setBackgroundColor(if(a.light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1).apply{leftMargin=a.d(16)})
            if(v.isClickable)v.background=lit(i==0,i==rows.lastIndex)
            box.addView(v,LinearLayout.LayoutParams(-1,if(v is LayoutToggle)a.d(52) else -2))}
        return box}
    /** A row that opens a page: the name, its current value in grey and a chevron. */
    private fun row(name:String,open:()->Unit):Pair<LinearLayout,TextView>{
        val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(52);setPadding(a.d(16),0,a.d(14),0);isClickable=true;isFocusable=true;setOnClickListener{open()}}
        r.addView(a.text(name,15.5f,a.fg),LinearLayout.LayoutParams(0,-2,1f))
        val value=a.text("",15f,muted2).apply{maxLines=1};r.addView(value,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=a.d(8)})
        r.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        return r to value}
    /** The four themes as cards: «Аа» and the name in the theme's own colours; the chosen one ringed inside. */
    private fun themeCards():View{
        val row=a.line()
        themes.forEachIndexed{i,(key,label)->
            val (bg,ink)=colors(key)
            val tile=ThemeCard(a,bg,ink,label).apply{isClickable=true;contentDescription="Тема: $label"}
            refreshers.add{tile.on=a.prefs.str("rt","night")==key}
            tile.setOnClickListener{if(a.prefs.str("rt","night")!=key){tile.tick(a);r.crossfade{a.prefs.put("rt",key);a.savePreferences();r.applyTheme();refresh()}}}
            a.press(tile);row.addView(tile,LinearLayout.LayoutParams(0,-1,1f).apply{if(i>0)leftMargin=a.d(8)})}
        return row
    }
    private fun brightnessRow():View{
        val row=a.line()
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0).apply{textSize=13f;typeface=PolkaFonts.sans(600)}
        val bar=ThinSlider(a,.02f,1f,0f,start,null,pill=true){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);r.applyBrightness();if(done)a.savePreferences()}.apply{contentDescription="Яркость экрана"}
        bar.dim=auto.on
        auto.setOnClickListener{val on=!auto.on;auto.tick(a);auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value.toDouble());a.savePreferences();r.applyBrightness()}
        row.addView(bar,LinearLayout.LayoutParams(0,-1,1f));row.addView(auto,LinearLayout.LayoutParams(a.d(76),-1).apply{leftMargin=a.d(8)})
        return row
    }
    /** A layout setting in a card: the name and the value on top, a thin slider under them (a tick at the default).
     *  Held, the row lifts on its own and everything else fades, so only the text and that one control remain. */
    private fun sliderRow(title:String,from:Float,to:Float,step:Float,value:Float,default:Float,format:(Float)->String,set:(Float)->Unit):View{
        val box=a.column().apply{setPadding(a.d(16),a.d(12),a.d(16),a.d(12))}
        val head=a.line();head.addView(a.text(title,15.5f,a.fg),LinearLayout.LayoutParams(0,-2,1f))
        val shown=a.text(format(value),15.5f,muted2);head.addView(shown);box.addView(head)
        val bar=ThinSlider(a,from,to,step,value,default){v,done->shown.text=format(v);set(v);typography();if(done)a.savePreferences()}.apply{contentDescription=title}
        bar.onGrab={on->spotlight(box,on)}
        box.addView(bar,LinearLayout.LayoutParams(-1,a.d(26)).apply{topMargin=a.d(8)})
        return box
    }
    /** How pages turn: a card for each way with a little live page turning that way; the cards scroll sideways. */
    private fun turnCards():View{
        val (paper,ink)=colors(a.prefs.str("rt","night"))
        val strip=a.line().apply{setPadding(a.d(16),a.d(2),a.d(16),a.d(2));clipToPadding=false}
        listOf("curl" to "Листание","slide" to "Сдвиг","cover" to "Наложение","none" to "Без анимации","scroll" to "Прокрутка").forEachIndexed{i,(key,label)->
            val ring=RingCard(a,cardColor)
            val col=a.column().apply{gravity=Gravity.CENTER;isClickable=true;contentDescription=label;background=ring}
            col.addView(TurnTile(a,key,paper,ink,bare=true),LinearLayout.LayoutParams(a.d(56),a.d(76)))
            val name=a.text(label,12f,muted2).apply{gravity=Gravity.CENTER;maxLines=1};col.addView(name,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(9)})
            fun current()=if(!r.paged())"scroll" else a.prefs.str("turn","curl")
            val show={val on=current()==key;ring.on=on;name.setTextColor(if(on)a.fg else muted2);name.typeface=PolkaFonts.sans(if(on)600 else 500)}
            show();refreshers.add(show)
            col.setOnClickListener{if(current()==key)return@setOnClickListener;col.tick(a)
                if(key=="scroll"){refresh();r.crossfade{r.setReadingMode(false);refresh()}}
                else{a.prefs.put("turn",key);a.savePreferences();if(!r.paged())r.crossfade{r.setReadingMode(true);refresh()}}
                refresh()}
            a.press(col);strip.addView(col,LinearLayout.LayoutParams(a.d(96),a.d(118)).apply{if(i>0)leftMargin=a.d(8)})}
        return HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(strip)}
    }
    private fun fmt(v:Float,digits:Int=1):String{val t=if(digits==2)"%.2f".format(java.util.Locale.US,v) else "%.1f".format(java.util.Locale.US,v);return t.trimEnd('0').trimEnd('.').replace('.',',')}
    private fun percent(v:Float):String{val p=(v*100).roundToInt();return if(p==0)"0" else if(p>0)"+$p%" else "−${-p}%"}
    private val hidden=mutableListOf<Pair<View,Float>>();private val faded=mutableListOf<Pair<android.graphics.drawable.Drawable,ValueAnimator>>()
    /** While a slider is held, everything but its row fades away (panel included), so only the text and that one control remain. */
    private val unclip=mutableListOf<Pair<ViewGroup,Pair<Boolean,Boolean>>>()
    private fun spotlight(row:View,on:Boolean){
        val reduced=this.reduced
        if(on){hidden.clear();faded.forEach{it.second.cancel()};faded.clear()
            var child=row;var parent=child.parent as? ViewGroup
            while(parent!=null){for(i in 0 until parent.childCount){val v=parent.getChildAt(i);if(v!==child){hidden.add(v to v.alpha);if(reduced)v.alpha=0f else v.animate().alpha(0f).setStartDelay(0).setDuration(160).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).start()}}
                parent.background?.let{bg->val an=ValueAnimator.ofInt(255,0).apply{duration=if(reduced)0 else 180;addUpdateListener{bg.alpha=it.animatedValue as Int};start()};faded.add(bg to an)}
                if(parent===r.panel.view)break;child=parent;parent=parent.parent as? ViewGroup}
            // the scroll view's fading edges would still draw over the background; switch them off while focused
            var g:ViewParent?=row.parent;while(g!=null&&g!==r.panel.view){(g as? SheetScroll)?.fadeEnabled=false;g=g.parent}
            // the held row becomes its own small floating card, so the book text never runs into it; the reader bars step aside
            // the card stays inside the page (the row's frame reaches 4dp past its edges and was cut off on the right) and
            // casts no shadow: it only has to keep the text off the capsule
            unclip.clear();var up:ViewParent?=row.parent;while(up is ViewGroup){unclip.add(up to (up.clipChildren to up.clipToPadding));up.clipChildren=false;up.clipToPadding=false;if(up===r.panel.view)break;up=up.parent}
            val card=GradientDrawable().apply{setColor(sheetColor);cornerRadius=row.height/2f;alpha=0};val pad=intArrayOf(row.paddingLeft,row.paddingTop,row.paddingRight,row.paddingBottom);row.background=card;row.setPadding(pad[0],pad[1],pad[2],pad[3]);row.elevation=0f
            ValueAnimator.ofFloat(0f,1f).apply{duration=if(reduced)0 else 180;addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt()};start()}
            r.fadeChrome(true)
        }else{
            (row.background as? android.graphics.drawable.Drawable)?.let{card->ValueAnimator.ofFloat(1f,0f).apply{duration=if(reduced)0 else 220;startDelay=if(reduced)0 else 80
                addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(row.background===card){val pad=intArrayOf(row.paddingLeft,row.paddingTop,row.paddingRight,row.paddingBottom);row.background=null;row.setPadding(pad[0],pad[1],pad[2],pad[3]);row.elevation=0f}}});start()}}
            r.fadeChrome(false)
            hidden.forEach{(v,alpha)->if(reduced)v.alpha=alpha else v.animate().alpha(alpha).setStartDelay(60).setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()};hidden.clear()
            faded.forEach{(bg,an)->an.cancel();ValueAnimator.ofInt(bg.alpha,255).apply{duration=if(reduced)0 else 240;startDelay=if(reduced)0 else 40;addUpdateListener{bg.alpha=it.animatedValue as Int};start()}};faded.clear()
            var g:ViewParent?=row.parent;while(g!=null&&g!==r.panel.view){(g as? SheetScroll)?.fadeEnabled=true;g=g.parent}
            val restore=unclip.toList();unclip.clear();row.postDelayed({restore.forEach{(v,c)->v.clipChildren=c.first;v.clipToPadding=c.second;v.invalidate()}},if(reduced)0L else 320L)
        }
    }

    // ---- state -----------------------------------------------------------------------------

    private fun colors(key:String)=when(key){"black"->0xff000000.toInt() to 0xffc9c7c1.toInt();"sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt();"paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt();else->0xff18181a.toInt() to 0xffdedcd6.toInt()}
    private fun typography(){r.applyTypography();refresh()}
    private fun refresh(){refreshers.forEach{it()}}
    private fun reset(v:View){
        listOf("lhv","ls","ws","margin").forEach{a.prefs.remove(it)}
        a.prefs.put("lh","normal");a.prefs.put("justify",true);a.prefs.put("hyph",true);a.prefs.put("trimEmpty",false)
        v.tick(a,if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        a.savePreferences();typography();r.panel.replace(layoutPage())
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
        val fx=w*value
        p.shader=null;p.color=SliderInk.fill(a)
        SliderInk.fillTo(c,p,w,h,fx)
        fun sun(cx:Float,big:Boolean,ink:Int){val k=if(big)1f else .78f;p.color=ink;p.style=Paint.Style.FILL;c.drawCircle(cx,h/2,(if(big)3.6f else 2.9f)*d,p)
            p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=(if(big)1.6f else 1.4f)*d;val r0=(if(big)6.5f else 5.2f)*d;val r1=(if(big)9f else 6.9f)*d
            for(i in 0 until 8){val t=Math.PI*i/4;c.drawLine(cx+(cos(t)*r0).toFloat(),h/2+(sin(t)*r0).toFloat(),cx+(cos(t)*r1).toFloat(),h/2+(sin(t)*r1).toFloat(),p)};p.style=Paint.Style.FILL}
        // both suns in two inks, split exactly at the fill's edge — like the labels of the other sliders
        val onFill=if(a.light)Color.WHITE else 0xff1d1d20.toInt();val off=if(a.light)0xff55555a.toInt() else 0xffcfcfd4.toInt()
        c.save();c.clipRect(fx,0f,w,h);sun(h/2,false,off);sun(w-h/2,true,off);c.restore()
        c.save();c.clipRect(0f,0f,fx,h);sun(h/2,false,onFill);sun(w-h/2,true,onFill);c.restore()}
}

/** A drawn chevron for «go to a page» (or back when [back]), rounded and in the caption colour. */
internal class Chevron(private val a:MainActivity,private val back:Boolean=false):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val w=9*d;val h=16*d;p.strokeWidth=2.2f*d;p.color=if(back)a.fg else (if(a.light)0xff7a7a80.toInt() else 0xffa3a3a9.toInt())
        c.save();c.translate((width-w)/2f,(height-h)/2f);if(back)c.scale(-1f,1f,w/2f,h/2f)
        path.rewind();path.moveTo(1.5f*d,1.8f*d);path.lineTo(7.3f*d,8f*d);path.lineTo(1.5f*d,14.2f*d);c.drawPath(path,p);c.restore()}
}

/** «Авто» pill used for brightness. */
internal class AutoChip(private val a:MainActivity,initial:Boolean):TextView(a){
    var on=initial;private set
    init{text="Авто";textSize=13f;gravity=Gravity.CENTER;setPadding(a.d(14),a.d(6),a.d(14),a.d(6));minHeight=a.d(30);a.press(this);paint()}
    fun set(value:Boolean){if(value==on)return;on=value;paint()}
    private fun paint(){background=a.shape(if(on)a.fg else (if(a.light)0xffe6e6e3.toInt() else 0xff333336.toInt()),100);setTextColor(if(on)a.bg else a.muted)}
}

/** A theme as a card: «Аа» and its name in the theme's own ink on its own paper; the chosen one gets a ring in that
 *  ink drawn just inside its edge (the card keeps its size), drawing in softly. */
internal class ThemeCard(private val a:MainActivity,private val bg:Int,private val ink:Int,private val name:String):View(a){
    private var k=0f;private var anim:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||!isAttachedToWindow||a.prefs.str("motion")=="reduced"){first=false;k=target;invalidate();return}
        anim?.cancel();anim=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val sd=a.resources.displayMetrics.scaledDensity;val w=width.toFloat();val h=height.toFloat();val rad=16*d
        p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(0f,0f,w,h,rad,rad,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d;p.color=if(Color.red(bg)>128)0x1a000000 else 0x1fffffff;c.drawRoundRect(d/2,d/2,w-d/2,h-d/2,rad,rad,p)
        if(k>0f){p.strokeWidth=2*d;p.color=ink;p.alpha=(255*k).toInt();val o=d;c.drawRoundRect(o,o,w-o,h-o,rad-o,rad-o,p);p.alpha=255}
        p.style=Paint.Style.FILL;p.color=ink;p.textAlign=Paint.Align.CENTER
        p.textSize=22*sd;p.typeface=PolkaFonts.reader(a,"literata");p.isFakeBoldText=true;val big=-p.ascent()
        val small=Paint(p).apply{textSize=11*sd;typeface=PolkaFonts.sans(500);isFakeBoldText=false;alpha=204}
        val total=big+5*d+(-small.ascent());val top=(h-total)/2
        c.drawText("Аа",w/2,top+big,p);c.drawText(name,w/2,top+big+5*d-small.ascent(),small)}
}

/** A card's ground with a ring just inside its edge when [on] (the card keeps its size). */
internal class RingCard(private val a:MainActivity,private val fill:Int):android.graphics.drawable.Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var k=0f;private var anim:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||a.prefs.str("motion")=="reduced"){first=false;k=target;invalidateSelf();return}
        anim?.cancel();anim=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidateSelf()};start()}}
    override fun draw(c:Canvas){val d=a.resources.displayMetrics.density;val b=RectF(bounds);val rad=18*d
        p.style=Paint.Style.FILL;p.color=fill;c.drawRoundRect(b,rad,rad,p)
        if(k>0f){p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;p.alpha=(255*k).toInt();b.inset(d,d);c.drawRoundRect(b,rad-d,rad-d,p);p.alpha=255}}
    override fun setAlpha(alpha:Int){p.alpha=alpha}
    override fun setColorFilter(cf:ColorFilter?){p.colorFilter=cf}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** The text size as one capsule: a smaller «A» on the left, the number in the middle, a larger «A» on the right.
 *  Each tap moves one step (held, it keeps going) with a tick; at the end of the range it refuses with a firmer one. */
internal class SizeStepper(private val a:MainActivity,start:Int,private val change:(Int)->Unit):LinearLayout(a){
    private val from=14;private val to=32
    private var value=start.coerceIn(from,to)
    private val number=a.text("",16f,a.fg,true)
    private val less:FrameLayout;private val more:FrameLayout
    private val repeat=android.os.Handler(android.os.Looper.getMainLooper())
    init{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=a.shape(if(a.light)0x0f000000 else 0x11ffffff,24);clipToOutline=true
        fun seg(size:Float,label:String,dir:Int)=FrameLayout(a).apply{isClickable=true;contentDescription=label
            background=android.graphics.drawable.StateListDrawable().apply{setExitFadeDuration(220);addState(intArrayOf(android.R.attr.state_pressed),android.graphics.drawable.ColorDrawable(if(a.light)0x0c000000 else 0x0fffffff));addState(intArrayOf(),android.graphics.drawable.ColorDrawable(0))}
            addView(a.text("A",size,a.fg).apply{typeface=PolkaFonts.sans(500);includeFontPadding=false},FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            // held, it keeps stepping
            val seg=this;var repeated=false
            val go=object:Runnable{override fun run(){if(!seg.isPressed)return;repeated=true;step(dir,seg);repeat.postDelayed(this,90)}}
            setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{repeated=false;repeat.postDelayed(go,420)};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->repeat.removeCallbacks(go)};false}
            setOnClickListener{if(!repeated)step(dir,seg);repeated=false}}
        less=seg(14f,"Меньше",-1);more=seg(22f,"Больше",1)
        fun line()=View(a).apply{setBackgroundColor(if(a.light)0x1a000000 else 0x1fffffff)}
        addView(less,LayoutParams(a.d(72),-1));addView(line(),LayoutParams(1,a.d(22)))
        addView(number.apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f))
        addView(line(),LayoutParams(1,a.d(22)));addView(more,LayoutParams(a.d(72),-1))
        paint()}
    private fun step(dir:Int,v:View){val next=value+dir
        if(next<from||next>to){v.tick(a,if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS);return}
        value=next;v.tick(a);paint();pulse();change(value)}
    fun sync(v:Int){val n=v.coerceIn(from,to);if(n==value)return;value=n;paint()}
    private fun paint(){val s=android.text.SpannableStringBuilder("$value")
        val tail="  размер";s.append(tail);s.setSpan(android.text.style.ForegroundColorSpan(if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()),s.length-tail.length,s.length,0)
        s.setSpan(android.text.style.AbsoluteSizeSpan(13,true),s.length-tail.length,s.length,0);s.setSpan(android.text.style.StyleSpan(Typeface.NORMAL),s.length-tail.length,s.length,0)
        number.text=s;number.contentDescription="Размер текста $value"
        less.alpha=if(value>from)1f else .35f;more.alpha=if(value<to)1f else .35f}
    private fun pulse(){if(a.prefs.str("motion")=="reduced")return;number.animate().cancel();number.scaleX=1.12f;number.scaleY=1.12f;number.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
    override fun onDetachedFromWindow(){repeat.removeCallbacksAndMessages(null);super.onDetachedFromWindow()}
}

/** A thin slider: a 4 dp track filled in the text colour up to a round white knob; [pill]: on its own capsule with a
 *  small sun at the start and a big one at the end (brightness). [step] 0 is smooth; otherwise it settles on whole
 *  steps with a tick on each, and catches on [default] (shown as a small mark on the track). */
internal class ThinSlider(private val a:MainActivity,private val from:Float,private val to:Float,private val step:Float,start:Float,private val default:Float?,private val pill:Boolean=false,private val change:(Float,Boolean)->Unit):View(a){
    var value=snap(start);private set
    var dim=false;set(v){field=v;invalidate()}
    var onGrab:((Boolean)->Unit)?=null
    private var press=0f;private var pressAnim:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    private val d=a.resources.displayMetrics.density
    init{isClickable=true}
    private fun snap(v:Float)=if(step<=0f)v.coerceIn(from,to) else (from+((v-from)/step).roundToInt()*step).coerceIn(from,to)
    private fun frac(v:Float)=((v-from)/(to-from)).coerceIn(0f,1f)
    private val left get()=if(pill)44*d else 11*d
    private val right get()=width-(if(pill)48*d else 11*d)
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);onGrab?.invoke(true);grab(true);track(e.x,false)}
        MotionEvent.ACTION_MOVE->track(e.x,false)
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{track(e.x,true);grab(false);onGrab?.invoke(false)}};return true}
    private fun grab(on:Boolean){if(a.prefs.str("motion")=="reduced")return;pressAnim?.cancel()
        pressAnim=ValueAnimator.ofFloat(press,if(on)1f else 0f).apply{duration=if(on)140 else 240;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{press=it.animatedValue as Float;invalidate()};start()}}
    private fun track(x:Float,done:Boolean){var f=((x-left)/(right-left).coerceAtLeast(1f)).coerceIn(0f,1f)
        default?.let{dv->if(abs(f-frac(dv))<.025f)f=frac(dv)}
        val n=snap(from+f*(to-from));dim=false
        // a tick on each step; a smooth one ticks only at its two ends
        if(n!=value){val edge=step<=0f&&(n<=from||n>=to)&&value>from&&value<to;if(step>0f||edge)tick(a);value=n}
        invalidate();change(value,done)}
    override fun onDetachedFromWindow(){pressAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val w=width.toFloat();val cy=h/2
        if(pill){p.style=Paint.Style.FILL;p.color=if(a.light)0x0f000000 else 0x11ffffff;c.drawRoundRect(0f,0f,w,h,h/2,h/2,p)
            val muted=if(a.light)0xff8a8a8f.toInt() else 0xffa4a4a4.toInt()
            sun(c,16*d+8*d,cy,false,muted);sun(c,w-16*d-10*d,cy,true,a.fg)}
        val x0=left;val x1=right;val fx=x0+(x1-x0)*frac(value)
        p.style=Paint.Style.FILL;p.color=if(a.light)0x1f000000 else 0xff3a3a3c.toInt();c.drawRoundRect(x0,cy-2*d,x1,cy+2*d,2*d,2*d,p)
        default?.let{dv->val dx=x0+(x1-x0)*frac(dv);p.color=if(a.light)0x33000000 else 0x4dffffff;c.drawRoundRect(dx-d,cy-5*d,dx+d,cy+5*d,d,d,p)}
        p.color=if(dim)(if(a.light)0xffb0b0b4.toInt() else 0xff6a6a6e.toInt()) else SliderInk.fill(a);c.drawRoundRect(x0,cy-2*d,fx.coerceAtLeast(x0+4*d),cy+2*d,2*d,2*d,p)
        val kr=(if(pill)10f else 11f)*d*(1f+.12f*press)
        FloatShade.draw(c,a,RectF(fx-kr,cy-kr,fx+kr,cy+kr),kr,a.light,lift=Lift.TOUCH)
        p.color=Color.WHITE;c.drawCircle(fx,cy,kr,p)}
    private fun sun(c:Canvas,cx:Float,cy:Float,big:Boolean,ink:Int){p.color=ink;p.style=Paint.Style.FILL;c.drawCircle(cx,cy,(if(big)3.6f else 2.9f)*d,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=(if(big)1.6f else 1.4f)*d;val r0=(if(big)6.5f else 5.2f)*d;val r1=(if(big)9f else 6.9f)*d
        for(i in 0 until 8){val t=Math.PI*i/4;c.drawLine(cx+(cos(t)*r0).toFloat(),cy+(sin(t)*r0).toFloat(),cx+(cos(t)*r1).toFloat(),cy+(sin(t)*r1).toFloat(),p)};p.style=Paint.Style.FILL}
}

/** A switch with its name: on its own capsule, or [plain], a row of a settings card (the card draws the ground and
 *  lights the row under a finger). The switch is the platforms' size, 52 x 32 dp. */
internal class LayoutToggle(private val a:MainActivity,private val title:String,initial:Boolean,private val plain:Boolean=false,private val change:(Boolean)->Unit):View(a){
    private var on=initial
    /** Where the knob's two ends are, 0 the left place and 1 the right one; equal at rest. */
    private var lead=if(initial)1f else 0f;private var trail=lead
    private var press=0f;private var anim:ValueAnimator?=null;private var pressAnim:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG);private val face by lazy{a.text("",1f).typeface}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{isClickable=true;contentDescription=title;if(!plain)a.press(this);setOnClickListener{flip()}}
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.isCheckable=true;info.isChecked=on;info.className="android.widget.Switch"}
    override fun drawableStateChanged(){super.drawableStateChanged();if(reduced)return;val to=if(isPressed)1f else 0f;if(press==to)return
        pressAnim?.cancel();pressAnim=ValueAnimator.ofFloat(press,to).apply{duration=if(isPressed)160 else 240;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{press=it.animatedValue as Float;invalidate()};start()}}
    private fun flip(){on=!on;anim?.cancel();if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val to=if(on)1f else 0f
        if(reduced){lead=to;trail=to;invalidate()}
        else{val l0=lead;val t0=trail;val reach=PathInterpolator(.3f,0f,.1f,1f);val follow=android.view.animation.OvershootInterpolator(1.7f)
            anim=ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener{val x=it.animatedValue as Float;lead=l0+(to-l0)*reach.getInterpolation((x/.55f).coerceIn(0f,1f))
                    trail=t0+(to-t0)*follow.getInterpolation(((x-.22f)/.78f).coerceIn(0f,1f));invalidate()};start()}}
        // let the knob start moving before the page is rebuilt
        postDelayed({change(on)},60)}
    override fun onDetachedFromWindow(){anim?.cancel();pressAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val w=width.toFloat();val d=a.resources.displayMetrics.density
        if(!plain){p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,h/2,h/2,p)}
        t.typeface=face;t.textSize=(if(plain)16f else 13f)*a.resources.displayMetrics.scaledDensity;t.color=if(plain)a.fg else if(a.light)0xff3a3a3e.toInt() else 0xffcfcfd4.toInt()
        // on a capsule the switch sits in its end at the same distance from every side
        val sw=52*d;val sh=32*d;val sx=w-(if(plain)16*d else (h-sh)/2)-sw;val sy=(h-sh)/2;val tx=(if(plain)18 else 14)*d
        val room=sx-tx-8*d;var label=title;if(t.measureText(label)>room){while(label.length>1&&t.measureText("$label…")>room)label=label.dropLast(1);label="$label…"}
        c.drawText(label,tx,h/2f-(t.ascent()+t.descent())/2f,t)
        val k=((lead+trail)/2f).coerceIn(0f,1f)
        val offTrack=if(a.light)0x29000000 else 0xff3a3a3d.toInt();val onTrack=if(a.light)0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
        p.color=androidx.core.graphics.ColorUtils.blendARGB(offTrack,onTrack,k);c.drawRoundRect(sx,sy,sx+sw,sy+sh,sh/2,sh/2,p)
        val kr=(sh-8*d)/2;val left=sx+4*d+kr;val span=sw-8*d-2*kr
        val a0=left+span*minOf(lead,trail);val b0=left+span*maxOf(lead,trail)
        // stretched it thins a little; pressed it swells toward the other side
        val stretch=abs(lead-trail);val swell=.5f*kr*press;val ry=kr*(1f-.12f*stretch-.08f*press)
        val l=a0-kr-(if(on)swell else 0f);val r=b0+kr+(if(on)0f else swell)
        val offKnob=if(a.light)Color.WHITE else 0xffcfcfd4.toInt();val onKnob=if(a.light)Color.WHITE else 0xff1d1d20.toInt()
        // the knob stands a little above its track: the app's shade at a touch's height, kept inside the track
        c.save();c.clipPath(Path().apply{addRoundRect(sx,sy,sx+sw,sy+sh,sh/2,sh/2,Path.Direction.CW)});FloatShade.draw(c,a,RectF(l,h/2-ry,r,h/2+ry),ry,a.light,lift=Lift.TOUCH);c.restore()
        p.color=androidx.core.graphics.ColorUtils.blendARGB(offKnob,onKnob,k);c.drawRoundRect(l,h/2-ry,r,h/2+ry,ry,ry,p)}
}

/** One look for every pill slider: a solid fill in the text colour (no grey gradient, never dimmed), growing exactly
 *  with the value inside the rounded track — empty at zero, full at the end. */
internal object SliderInk{
    fun fill(a:MainActivity)=if(a.light)0xff1d1d1f.toInt() else 0xfff3f2ef.toInt()
    fun fillTo(c:Canvas,p:Paint,w:Float,h:Float,fx:Float){if(fx<=0f)return;val r=h/2;c.save();c.clipPath(Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)})
        c.drawRoundRect(-r,0f,fx.coerceAtMost(w),h,r,r,p);c.restore()}
}
